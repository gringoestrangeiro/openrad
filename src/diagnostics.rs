//! Bounded, asynchronous operational logs. Never pass credentials or packet bodies.
use anyhow::Result;
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc::{self, SyncSender},
        Arc,
    },
    thread::{self, JoinHandle},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const FILE_BYTES: u64 = 4 * 1024 * 1024;
const FILE_COUNT: usize = 4;

#[derive(Clone, Default)]
pub struct Diagnostics {
    inner: Option<Arc<Inner>>,
    session: Option<u64>,
}
struct Inner {
    sender: Option<SyncSender<Value>>,
    writer: Option<JoinHandle<()>>,
    dropped: Arc<AtomicU64>,
    next_session: AtomicU64,
    started: Instant,
}
impl Drop for Inner {
    fn drop(&mut self) {
        // Closing the final producer drains the queue before process shutdown.
        drop(self.sender.take());
        if let Some(writer) = self.writer.take() {
            let _ = writer.join();
        }
    }
}
impl Diagnostics {
    pub fn open(directory: &Path) -> Result<Self> {
        let mut builder = fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(directory)?;
        let mut output = RotatingFile::open(directory, FILE_BYTES)?;
        let (sender, receiver) = mpsc::sync_channel::<Value>(1024);
        let dropped = Arc::new(AtomicU64::new(0));
        let lost = dropped.clone();
        let writer = thread::Builder::new()
            .name("openrad-diagnostics".into())
            .spawn(move || {
                let mut failures = 0u64;
                for mut record in receiver {
                    record["dropped_events"] = lost.load(Ordering::Relaxed).into();
                    record["write_failures"] = failures.into();
                    if let Err(error) = output.write(&record) {
                        failures = failures.saturating_add(1);
                        if failures == 1 {
                            eprintln!("OpenRad diagnostics write failed: {error}");
                        }
                    }
                }
            })?;
        let log = Self {
            inner: Some(Arc::new(Inner {
                sender: Some(sender),
                writer: Some(writer),
                dropped,
                next_session: AtomicU64::new(1),
                started: Instant::now(),
            })),
            session: None,
        };
        log.event(
            "diagnostics_started",
            json!({"version": env!("CARGO_PKG_VERSION"), "os": std::env::consts::OS, "arch": std::env::consts::ARCH}),
        );
        Ok(log)
    }

    pub fn new_session(&self) -> Self {
        Self {
            inner: self.inner.clone(),
            session: self
                .inner
                .as_ref()
                .map(|inner| inner.next_session.fetch_add(1, Ordering::Relaxed)),
        }
    }

    /// Never waits on disk or a full queue; diagnostic loss is counted explicitly.
    pub fn event(&self, event: &str, details: Value) {
        let Some(inner) = &self.inner else { return };
        let record = json!({
            "timestamp_ms": SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis(),
            "elapsed_ms": inner.started.elapsed().as_millis(),
            "process_id": std::process::id(),
            "session": self.session,
            "event": event,
            "details": details,
        });
        if inner.sender.as_ref().unwrap().try_send(record).is_err() {
            inner.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}

struct RotatingFile {
    directory: PathBuf,
    file: Option<File>,
    bytes: u64,
    limit: u64,
}
impl RotatingFile {
    fn open(directory: &Path, limit: u64) -> Result<Self> {
        let mut output = Self {
            directory: directory.into(),
            file: None,
            bytes: 0,
            limit,
        };
        output.reopen()?;
        Ok(output)
    }
    fn path(&self, index: usize) -> PathBuf {
        self.directory.join(if index == 0 {
            "connection.jsonl".to_owned()
        } else {
            format!("connection.{index}.jsonl")
        })
    }
    fn reopen(&mut self) -> Result<()> {
        let mut options = OpenOptions::new();
        options.create(true).append(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600).custom_flags(libc::O_NOFOLLOW);
        }
        let file = options.open(self.path(0))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(fs::Permissions::from_mode(0o600))?;
        }
        self.bytes = file.metadata()?.len();
        self.file = Some(file);
        Ok(())
    }
    fn write(&mut self, record: &Value) -> Result<()> {
        let mut bytes = serde_json::to_vec(record)?;
        bytes.push(b'\n');
        // A single unexpected error string must not defeat retention limits.
        anyhow::ensure!(
            bytes.len() as u64 <= self.limit,
            "diagnostic record too large"
        );
        if self.file.is_none() {
            self.reopen()?;
        }
        if self.bytes + bytes.len() as u64 > self.limit {
            drop(self.file.take());
            match fs::remove_file(self.path(FILE_COUNT - 1)) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                _ => {}
            }
            for index in (1..FILE_COUNT).rev() {
                match fs::rename(self.path(index - 1), self.path(index)) {
                    Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                    _ => {}
                }
            }
            self.reopen()?;
        }
        self.file.as_mut().unwrap().write_all(&bytes)?;
        self.bytes += bytes.len() as u64;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn directory() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "openrad-diagnostics-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        fs::create_dir(&path).unwrap();
        path
    }
    #[test]
    fn full_logger_queue_drops_diagnostics_without_blocking_producers() {
        let (sender, receiver) = mpsc::sync_channel(1);
        let dropped = Arc::new(AtomicU64::new(0));
        let log = Diagnostics {
            inner: Some(Arc::new(Inner {
                sender: Some(sender),
                writer: None,
                dropped: dropped.clone(),
                next_session: AtomicU64::new(1),
                started: Instant::now(),
            })),
            session: None,
        };
        log.event("first", json!({}));
        for _ in 0..100 {
            log.event("overflow", json!({}));
        }
        assert_eq!(dropped.load(Ordering::Relaxed), 100);
        assert_eq!(receiver.try_iter().count(), 1);
        log.event("recovered", json!({}));
        assert_eq!(receiver.recv().unwrap()["event"], "recovered");
    }
    #[test]
    fn final_drop_flushes_and_sessions_are_distinguishable() {
        let directory = directory();
        let log = Diagnostics::open(&directory).unwrap();
        for _ in 0..2 {
            log.new_session()
                .event("session_end", json!({"reason": "test"}));
        }
        drop(log);
        let data = fs::read_to_string(directory.join("connection.jsonl")).unwrap();
        let records: Vec<Value> = data
            .lines()
            .map(|line| serde_json::from_str(line).unwrap())
            .collect();
        assert_eq!(records.len(), 3);
        assert_eq!(records[1]["session"], 1);
        assert_eq!(records[2]["session"], 2);
        assert_eq!(records[2]["dropped_events"], 0);
        assert!(records[2]["timestamp_ms"].as_u64().unwrap() > 0);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(directory.join("connection.jsonl"))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
        fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn rotation_bounds_retention_and_keeps_complete_json_records() {
        let directory = directory();
        let mut output = RotatingFile::open(&directory, 128).unwrap();
        for index in 0..100 {
            output.write(&json!({"index": index})).unwrap();
        }
        drop(output);
        let mut indices = Vec::new();
        for entry in fs::read_dir(&directory).unwrap() {
            let entry = entry.unwrap();
            assert!(entry.metadata().unwrap().len() <= 128);
            for line in fs::read_to_string(entry.path()).unwrap().lines() {
                indices.push(
                    serde_json::from_str::<Value>(line).unwrap()["index"]
                        .as_u64()
                        .unwrap(),
                );
            }
        }
        assert_eq!(fs::read_dir(&directory).unwrap().count(), FILE_COUNT);
        assert!(indices.contains(&99));
        assert!(!indices.contains(&0));
        fs::remove_dir_all(directory).unwrap();
    }
}

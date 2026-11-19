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
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
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
            let deadline = Instant::now() + Duration::from_secs(1);
            while !writer.is_finished() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(5));
            }
            if writer.is_finished() {
                if writer.join().is_err() {
                    eprintln!("OpenRad diagnostics writer panicked");
                }
            } else {
                eprintln!(
                    "OpenRad diagnostics shutdown deadline exceeded; pending events may be lost"
                );
            }
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
        // The sender is only taken while the last handle is being dropped.
        if inner
            .sender
            .as_ref()
            .is_none_or(|sender| sender.try_send(record).is_err())
        {
            inner.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}

const SUPPORT_EVENTS: &[&str] = &[
    "diagnostics_started",
    "session_start",
    "session_attached",
    "session_attach_failed",
    "session_health",
    "session_end",
    "control_health",
    "control_closed",
    "control_heartbeat_delayed",
    "control_operation_timeout",
    "control_command_queue_full",
    "server_disconnect",
    "server_refusal",
    "membership_updated",
    "peer_drop_burst",
    "peer_retry_scheduled",
    "peer_start_failed",
    "interface_ready",
    "interface_failed",
    "interface_cleanup_failed",
];
const SUPPORT_COUNTERS: &[&str] = &[
    "elapsed_ms",
    "uptime_ms",
    "latency_ms",
    "peers",
    "networks",
    "eligible",
    "connected",
    "roster",
    "handshakes",
    "pending_incoming",
    "scheduled_retries",
    "active_retries",
    "due_retries",
    "max_loop_gap_ms",
    "late_ms",
    "delay_ms",
    "failures",
    "reason_code",
    "record_bytes",
    "receive_queue_dropped",
];

/// Export only typed counters and known event categories, never raw log strings.
pub fn support_bundle(directory: &Path, status: Option<&Value>) -> Result<Value> {
    fn counters(value: &Value, keys: &[&str]) -> Value {
        Value::Object(
            keys.iter()
                .filter_map(|key| {
                    value[*key]
                        .as_u64()
                        .map(|number| ((*key).to_owned(), json!(number)))
                })
                .collect(),
        )
    }
    let mut events = std::collections::VecDeque::with_capacity(200);
    let mut unreadable_files = 0;
    let mut discarded_records = 0;
    for index in (0..FILE_COUNT).rev() {
        let file = directory.join(if index == 0 {
            "connection.jsonl".to_owned()
        } else {
            format!("connection.{index}.jsonl")
        });
        let bytes = match crate::file_io::read_optional_bounded(&file, FILE_BYTES) {
            Ok(Some(bytes)) => bytes,
            Ok(None) => continue,
            Err(_) => {
                unreadable_files += 1;
                continue;
            }
        };
        for line in bytes
            .split(|byte| *byte == b'\n')
            .filter(|line| !line.is_empty())
        {
            let Ok(record) = serde_json::from_slice::<Value>(line) else {
                discarded_records += 1;
                continue;
            };
            let Some(event) = record["event"]
                .as_str()
                .filter(|event| SUPPORT_EVENTS.contains(event))
            else {
                continue;
            };
            let mut sanitized = json!({
                "event": event,
                "elapsed_ms": record["elapsed_ms"].as_u64(),
                "dropped_events": record["dropped_events"].as_u64(),
                "write_failures": record["write_failures"].as_u64(),
                "details": counters(&record["details"], SUPPORT_COUNTERS),
            });
            for key in ["interface_ready", "cancelled"] {
                if let Some(value) = record["details"][key].as_bool() {
                    sanitized["details"][key] = json!(value);
                }
            }
            if events.len() == 200 {
                events.pop_front();
            }
            events.push_back(sanitized);
        }
    }
    let current = status.map(|status| {
        let snapshot = &status["snapshot"];
        let phase = status["phase"].as_str().filter(|phase| matches!(*phase,
            "connected" | "connecting" | "reconnecting" | "error" | "stopping" | "stopped"));
        let peers = snapshot["peers"].as_object();
        json!({
            "phase": phase,
            "interface_ready": snapshot["interface_ready"].as_bool(),
            "interface_error": snapshot["interface_error"].is_string(),
            "latency_ms": snapshot["latency_ms"].as_u64(),
            "elapsed_secs": snapshot["elapsed_secs"].as_u64(),
            "network_count": snapshot["networks"].as_array().map(Vec::len),
            "peer_count": peers.map(serde_json::Map::len),
            "connected_count": peers.map(|peers| peers.values().filter(|peer| peer["status"] == "Connected").count()),
            "traffic": counters(&snapshot["traffic"], &["sent_bytes","received_bytes","sent_frames","received_frames","dropped"]),
        })
    });
    Ok(json!({
        "format": "openrad-support-v1", "version": env!("CARGO_PKG_VERSION"),
        "os": std::env::consts::OS, "arch": std::env::consts::ARCH,
        "current": current, "recent_events": events,
        "unreadable_files": unreadable_files, "discarded_records": discarded_records,
    }))
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
    fn support_bundle_does_not_export_addresses_names_or_raw_error_messages() {
        let directory = directory();
        let log = Diagnostics::open(&directory).unwrap();
        log.event("session_health", json!({"peers":5, "server":"192.0.2.7", "error":"synthetic secret", "rid":123, "latency_ms":12}));
        log.event("synthetic secret", json!({"peers":7}));
        drop(log);
        let status = json!({"phase":"connected", "rid":123, "node_name":"synthetic secret", "snapshot":{"vip":"26.1.2.3","interface_error":"synthetic secret","peers":{},"networks":[]}});
        let export = support_bundle(&directory, Some(&status)).unwrap();
        let serialized = export.to_string();
        for forbidden in [
            "synthetic secret",
            "192.0.2.7",
            "26.1.2.3",
            "node_name",
            "\"rid\"",
        ] {
            assert!(!serialized.contains(forbidden));
        }
        assert_eq!(export["recent_events"][1]["details"]["peers"], 5);
        assert_eq!(export["current"]["interface_error"], true);
        fs::remove_dir_all(directory).unwrap();
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

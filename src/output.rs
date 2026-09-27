//! Private operational reports and explicit identity export.
use anyhow::{ensure, Result};
use serde_json::Value;
use std::{
    fs::{DirBuilder, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

#[cfg(unix)]
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};

#[derive(Clone)]
pub struct ReportDirectory {
    pub directory: PathBuf,
    enabled: bool,
}
impl ReportDirectory {
    pub fn new(path: &Path) -> Result<Self> {
        let mut builder = DirBuilder::new();
        #[cfg(unix)]
        builder.mode(0o700);
        builder.create(path)?;
        Ok(Self {
            directory: path.to_path_buf(),
            enabled: true,
        })
    }
    /// Desktop sessions never record keys, credentials or packet contents.
    pub fn disabled() -> Self {
        Self {
            directory: PathBuf::new(),
            enabled: false,
        }
    }
    pub fn child(&self, name: &str) -> Result<Self> {
        if !self.enabled {
            return Ok(Self::disabled());
        }
        validate_name(name)?;
        Self::new(&self.directory.join(name))
    }
    fn save(&self, name: &str, data: &[u8]) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        validate_name(name)?;
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        options.mode(0o600);
        let mut f = options.open(self.directory.join(name))?;
        f.write_all(data)?;
        Ok(())
    }
    pub fn json(&self, name: &str, data: &impl serde::Serialize) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        self.save(name, &serde_json::to_vec_pretty(data)?)
    }
    pub fn events(&self, name: &str) -> Result<EventLog> {
        if !self.enabled {
            return Ok(EventLog(None));
        }
        validate_name(name)?;
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        options.mode(0o600);
        let f = options.open(self.directory.join(name))?;
        Ok(EventLog(Some(Arc::new(Mutex::new(f)))))
    }
}
#[derive(Clone)]
pub struct EventLog(Option<Arc<Mutex<File>>>);
impl EventLog {
    pub fn event(&self, v: Value) -> Result<()> {
        let Some(file) = &self.0 else {
            return Ok(());
        };
        let mut f = file.lock().unwrap();
        serde_json::to_writer(&mut *f, &v)?;
        f.write_all(b"\n")?;
        f.flush()?;
        Ok(())
    }
}

fn validate_name(name: &str) -> Result<()> {
    ensure!(
        !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\\']),
        "report name must be a single file name"
    );
    Ok(())
}

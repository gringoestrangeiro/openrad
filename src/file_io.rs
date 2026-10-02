//! Bounded reads of regular local files, including reusable identities.
use anyhow::{ensure, Context, Result};
use std::{fs::OpenOptions, io::Read, path::Path};
use zeroize::Zeroizing;

pub fn read_bounded(path: &Path, limit: u64) -> Result<Zeroizing<Vec<u8>>> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        // Opening a FIFO must not block before we can reject its file type.
        options.custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC);
    }
    let file = options.open(path).context("Cannot open local data file")?;
    let metadata = file.metadata()?;
    ensure!(metadata.is_file(), "Local data must be a regular file");
    ensure!(metadata.len() <= limit, "Local data file is too large");
    let mut bytes = Zeroizing::new(Vec::new());
    // Check the actual read as well: a file can grow after metadata was read.
    file.take(limit.checked_add(1).context("Invalid local data limit")?)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() as u64 <= limit, "Local data file is too large");
    Ok(bytes)
}

pub fn read_optional_bounded(path: &Path, limit: u64) -> Result<Option<Zeroizing<Vec<u8>>>> {
    match read_bounded(path, limit) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(error)
            if error
                .downcast_ref::<std::io::Error>()
                .is_some_and(|cause| cause.kind() == std::io::ErrorKind::NotFound)
                && std::fs::symlink_metadata(path)
                    .is_err_and(|cause| cause.kind() == std::io::ErrorKind::NotFound) =>
        {
            Ok(None)
        }
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_file_reads_enforce_limits_and_reject_directories() {
        let directory = std::env::temp_dir().join(format!(
            "openrad-file-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("synthetic.json");
        std::fs::write(&path, b"synthetic").unwrap();
        assert_eq!(read_bounded(&path, 9).unwrap().as_slice(), b"synthetic");
        assert!(read_bounded(&path, 8).is_err());
        assert!(read_bounded(&directory, 1024).is_err());
        assert!(read_optional_bounded(&directory.join("missing"), 1024)
            .unwrap()
            .is_none());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn a_broken_preferences_symlink_is_not_treated_as_missing_data() {
        let directory = std::env::temp_dir().join(format!(
            "openrad-link-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("preferences.json");
        std::os::unix::fs::symlink(directory.join("missing"), &path).unwrap();
        assert!(read_optional_bounded(&path, 1024).is_err());
        assert!(std::fs::symlink_metadata(&path)
            .unwrap()
            .file_type()
            .is_symlink());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn a_fifo_is_rejected_without_waiting_for_a_writer() {
        use std::os::unix::ffi::OsStrExt;
        let path = std::env::temp_dir().join(format!(
            "openrad-fifo-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let name = std::ffi::CString::new(path.as_os_str().as_bytes()).unwrap();
        // SAFETY: name is a live, NUL-terminated disposable FIFO path.
        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        let result = read_bounded(&path, 1024);
        std::fs::remove_file(path).unwrap();
        assert!(result.unwrap_err().to_string().contains("regular file"));
    }
}

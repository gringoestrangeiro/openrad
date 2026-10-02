//! Bounded, local-only named-pipe control channel with a current-user ACL.
//! Windows uses length-prefixed messages and a reply acknowledgement because
//! named pipes have no Unix-style write half-close.
use crate::{
    windows_io::{self, Operation},
    windows_security::{self, Security},
};
use sha2::{Digest, Sha256};
use std::{
    io,
    os::windows::{ffi::OsStrExt, io::OwnedHandle},
    path::Path,
    sync::Arc,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{ERROR_PIPE_BUSY, ERROR_SEM_TIMEOUT, GENERIC_READ, GENERIC_WRITE},
    Storage::FileSystem::{
        CreateFileW, FILE_FLAG_FIRST_PIPE_INSTANCE, FILE_FLAG_OVERLAPPED, OPEN_EXISTING,
        PIPE_ACCESS_DUPLEX, SECURITY_IDENTIFICATION, SECURITY_SQOS_PRESENT,
    },
    System::Pipes::{
        CreateNamedPipeW, WaitNamedPipeW, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS,
        PIPE_TYPE_BYTE, PIPE_WAIT,
    },
};

fn name(path: &Path) -> io::Result<Vec<u16>> {
    let mut hash = Sha256::new();
    hash.update(windows_security::user_sid()?.as_bytes());
    for code in path.as_os_str().encode_wide() {
        hash.update(code.to_le_bytes());
    }
    Ok(windows_io::wide(&format!(
        r"\\.\pipe\openrad-{}",
        hex::encode(hash.finalize())
    )))
}
pub fn exists(path: &Path) -> bool {
    let Ok(name) = name(path) else {
        return false;
    };
    if unsafe { WaitNamedPipeW(name.as_ptr(), 1) } != 0 {
        return true;
    }
    matches!(io::Error::last_os_error().raw_os_error(), Some(code) if code == ERROR_PIPE_BUSY as i32 || code == ERROR_SEM_TIMEOUT as i32)
}

pub struct Connection {
    handle: Arc<OwnedHandle>,
}
impl Connection {
    #[cfg(test)]
    pub fn connect(path: &Path) -> io::Result<Self> {
        Self::connect_timeout(path, Duration::from_secs(5))
    }
    pub fn connect_timeout(path: &Path, timeout: Duration) -> io::Result<Self> {
        if timeout.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        let name = name(path)?;
        let until = Instant::now() + timeout;
        loop {
            // SAFETY: no inheritance; identification SQOS prevents a pipe
            // server from impersonating an elevated client token.
            let handle = unsafe {
                windows_io::owned(CreateFileW(
                    name.as_ptr(),
                    GENERIC_READ | GENERIC_WRITE,
                    0,
                    std::ptr::null(),
                    OPEN_EXISTING,
                    FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION,
                    std::ptr::null_mut(),
                ))
            };
            match handle {
                Ok(handle) => {
                    return Ok(Self {
                        handle: Arc::new(handle),
                    })
                }
                Err(e)
                    if e.raw_os_error() == Some(ERROR_PIPE_BUSY as i32)
                        && Instant::now() < until =>
                unsafe {
                    WaitNamedPipeW(name.as_ptr(), milliseconds(until).min(50));
                },
                Err(e) => return Err(e),
            }
        }
    }
    fn read_exact(&mut self, bytes: &mut [u8], until: Instant) -> io::Result<()> {
        let mut at = 0;
        while at < bytes.len() {
            if Instant::now() >= until {
                return Err(io::ErrorKind::TimedOut.into());
            }
            let mut read = Operation::new(self.handle.clone(), bytes.len() - at)?;
            read.read()?;
            let len = read.finish(milliseconds(until))?;
            if len == 0 {
                return Err(io::ErrorKind::UnexpectedEof.into());
            }
            bytes[at..at + len].copy_from_slice(read.data(len));
            at += len;
        }
        Ok(())
    }
    pub fn read_message(&mut self, limit: u64, timeout: Duration) -> io::Result<Vec<u8>> {
        let until = Instant::now() + timeout;
        let mut header = [0u8; 4];
        self.read_exact(&mut header, until)?;
        let len = u32::from_le_bytes(header) as usize;
        if len == 0 || len as u64 > limit {
            return Err(io::ErrorKind::InvalidData.into());
        }
        let mut bytes = zeroize::Zeroizing::new(vec![0; len]);
        self.read_exact(&mut bytes, until)?;
        Ok(std::mem::take(&mut *bytes))
    }
    pub fn write_message(&mut self, bytes: &[u8], timeout: Duration) -> io::Result<()> {
        let len = u32::try_from(bytes.len()).map_err(|_| io::ErrorKind::InvalidInput)?;
        let mut framed = zeroize::Zeroizing::new(len.to_le_bytes().to_vec());
        framed.extend_from_slice(bytes);
        let until = Instant::now() + timeout;
        let mut write = Operation::new(self.handle.clone(), framed.len())?;
        let mut at = 0;
        while at < framed.len() {
            if Instant::now() >= until {
                return Err(io::ErrorKind::TimedOut.into());
            }
            write.write(&framed[at..])?;
            let len = write.finish(milliseconds(until))?;
            if len == 0 {
                return Err(io::ErrorKind::WriteZero.into());
            }
            at += len;
        }
        Ok(())
    }
    pub fn acknowledge(&mut self) -> io::Result<()> {
        self.write_message(b"A", Duration::from_secs(5))
    }
    pub fn wait_for_acknowledgement(&mut self) -> io::Result<()> {
        if self.read_message(1, Duration::from_secs(5))? == b"A" {
            Ok(())
        } else {
            Err(io::ErrorKind::InvalidData.into())
        }
    }
}
fn milliseconds(until: Instant) -> u32 {
    until
        .saturating_duration_since(Instant::now())
        .as_nanos()
        .div_ceil(1_000_000)
        .min((u32::MAX - 1) as u128) as u32
}

pub struct Listener {
    name: Vec<u16>,
    security: Security,
    pending: (Arc<OwnedHandle>, Operation),
}
impl Listener {
    fn instance(
        name: &[u16],
        security: &Security,
        first: bool,
    ) -> io::Result<(Arc<OwnedHandle>, Operation)> {
        let attributes = security.attributes();
        let flags = PIPE_ACCESS_DUPLEX
            | FILE_FLAG_OVERLAPPED
            | if first {
                FILE_FLAG_FIRST_PIPE_INSTANCE
            } else {
                0
            };
        // SAFETY: CreateNamedPipe copies the ACL; no raw pointers escape.
        let handle = Arc::new(unsafe {
            windows_io::owned(CreateNamedPipeW(
                name.as_ptr(),
                flags,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                64,
                65536,
                65536,
                5000,
                &attributes,
            ))
        }?);
        let mut connection = Operation::new(handle.clone(), 0)?;
        connection.connect_pipe()?;
        Ok((handle, connection))
    }
    pub fn bind(path: &Path) -> io::Result<Self> {
        let name = name(path)?;
        let security = Security::new(false)?;
        let pending = Self::instance(&name, &security, true)?;
        Ok(Self {
            name,
            security,
            pending,
        })
    }
    pub fn accept(&mut self) -> io::Result<Option<Connection>> {
        if !self.pending.1.ready(0)? {
            return Ok(None);
        }
        self.pending.1.finish(0)?;
        // Keep the namespace occupied while handing the connected instance to
        // its worker. Never leave a gap where another process can replace it.
        let next = Self::instance(&self.name, &self.security, false)?;
        let (handle, _) = std::mem::replace(&mut self.pending, next);
        Ok(Some(Connection { handle }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn local_pipe_round_trip_is_bounded_and_profile_scoped() {
        let path = std::env::temp_dir().join(format!(
            "openrad-pipe-test-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let mut listener = Listener::bind(&path).unwrap();
        assert_eq!(
            Connection::connect_timeout(&path, Duration::ZERO)
                .err()
                .unwrap()
                .kind(),
            io::ErrorKind::TimedOut
        );
        assert!(exists(&path));
        assert!(Listener::bind(&path).is_err());
        let worker = std::thread::spawn(move || {
            let mut client = Connection::connect(&path).unwrap();
            assert_eq!(
                client
                    .write_message(b"request", Duration::ZERO)
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::TimedOut
            );
            client
                .write_message(b"request", Duration::from_secs(2))
                .unwrap();
            assert_eq!(
                client.read_message(64, Duration::from_secs(2)).unwrap(),
                b"reply"
            );
            client.acknowledge().unwrap();
        });
        let until = Instant::now() + Duration::from_secs(2);
        let mut connection = loop {
            if let Some(connection) = listener.accept().unwrap() {
                break connection;
            }
            assert!(Instant::now() < until);
            std::thread::yield_now();
        };
        assert_eq!(
            connection
                .read_message(64, Duration::ZERO)
                .unwrap_err()
                .kind(),
            io::ErrorKind::TimedOut
        );
        assert_eq!(
            connection.read_message(64, Duration::from_secs(2)).unwrap(),
            b"request"
        );
        connection
            .write_message(b"reply", Duration::from_secs(2))
            .unwrap();
        connection.wait_for_acknowledgement().unwrap();
        worker.join().unwrap();
    }
}

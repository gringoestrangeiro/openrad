//! Owned, cancellable Win32 overlapped I/O. Buffers and OVERLAPPED addresses
//! remain stable until completion, including cancellation and error paths.
use std::{
    io,
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    sync::Arc,
};
use windows_sys::Win32::{
    Foundation::{
        ERROR_IO_PENDING, ERROR_PIPE_CONNECTED, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0,
        WAIT_TIMEOUT,
    },
    Storage::FileSystem::{ReadFile, WriteFile},
    System::{
        Pipes::ConnectNamedPipe,
        Threading::{CreateEventW, ResetEvent, SetEvent, WaitForSingleObject},
        IO::{CancelIoEx, DeviceIoControl, GetOverlappedResult, OVERLAPPED},
    },
};

pub fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
pub fn status(code: u32) -> io::Result<()> {
    if code == 0 {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(code as i32))
    }
}
/// Transfer a newly created Win32 handle into its Rust owner.
///
/// # Safety
/// The handle must be uniquely owned and safe to close with CloseHandle.
pub unsafe fn owned(raw: HANDLE) -> io::Result<OwnedHandle> {
    if raw.is_null() || raw == INVALID_HANDLE_VALUE {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedHandle::from_raw_handle(raw) })
}
pub fn event() -> io::Result<OwnedHandle> {
    // SAFETY: unnamed manual-reset event, no inherited handle or security pointer.
    unsafe { owned(CreateEventW(std::ptr::null(), 1, 0, std::ptr::null())) }
}
pub fn wait(handle: &OwnedHandle, milliseconds: u32) -> io::Result<bool> {
    // SAFETY: callers keep their owned handle alive throughout the wait.
    match unsafe { WaitForSingleObject(handle.as_raw_handle(), milliseconds) } {
        WAIT_OBJECT_0 => Ok(true),
        WAIT_TIMEOUT => Ok(false),
        _ => Err(io::Error::last_os_error()),
    }
}

pub struct Operation {
    handle: Arc<OwnedHandle>,
    event: OwnedHandle,
    overlapped: Box<OVERLAPPED>,
    buffer: Box<[u8]>,
    pending: bool,
    immediate: u32,
}
// SAFETY: uniquely accessed operations own stable heap storage and keep their
// device/event handles alive. Moving the owner does not move kernel pointers.
unsafe impl Send for Operation {}
impl Operation {
    pub fn new(handle: Arc<OwnedHandle>, capacity: usize) -> io::Result<Self> {
        let event = event()?;
        let overlapped = Box::new(OVERLAPPED {
            hEvent: event.as_raw_handle(),
            ..Default::default()
        });
        Ok(Self {
            handle,
            event,
            overlapped,
            buffer: vec![0; capacity].into_boxed_slice(),
            pending: false,
            immediate: 0,
        })
    }
    pub fn event_handle(&self) -> HANDLE {
        self.event.as_raw_handle()
    }
    pub fn ready(&self, milliseconds: u32) -> io::Result<bool> {
        wait(&self.event, milliseconds)
    }
    pub fn data(&self, len: usize) -> &[u8] {
        assert!(!self.pending);
        &self.buffer[..len]
    }
    fn prepare(&mut self) -> io::Result<()> {
        assert!(!self.pending, "cannot reuse a pending I/O operation");
        *self.overlapped = OVERLAPPED {
            hEvent: self.event_handle(),
            ..Default::default()
        };
        self.immediate = 0;
        // SAFETY: event belongs to this operation and no request is pending.
        if unsafe { ResetEvent(self.event_handle()) } == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    fn started(&mut self, success: i32) -> io::Result<()> {
        if success != 0 {
            // Asynchronous APIs receive a null byte-count pointer; obtain the
            // result from our stable OVERLAPPED even for immediate completion.
            if unsafe {
                GetOverlappedResult(
                    self.handle.as_raw_handle(),
                    &*self.overlapped,
                    &mut self.immediate,
                    0,
                )
            } == 0
            {
                return Err(io::Error::last_os_error());
            }
            // Like OpenVPN, signal also for an immediate completion.
            if unsafe { SetEvent(self.event_handle()) } == 0 {
                return Err(io::Error::last_os_error());
            }
        } else {
            let error = io::Error::last_os_error();
            if error.raw_os_error() != Some(ERROR_IO_PENDING as i32) {
                return Err(error);
            }
            self.pending = true;
        }
        Ok(())
    }
    pub fn read(&mut self) -> io::Result<()> {
        self.prepare()?;
        // SAFETY: all kernel-borrowed storage stays alive in this operation.
        let success = unsafe {
            ReadFile(
                self.handle.as_raw_handle(),
                self.buffer.as_mut_ptr(),
                self.buffer.len() as u32,
                std::ptr::null_mut(),
                &mut *self.overlapped,
            )
        };
        self.started(success)
    }
    pub fn connect_pipe(&mut self) -> io::Result<()> {
        self.prepare()?;
        // SAFETY: stable OVERLAPPED retained until connection or cancellation.
        let success =
            unsafe { ConnectNamedPipe(self.handle.as_raw_handle(), &mut *self.overlapped) };
        if success == 0
            && io::Error::last_os_error().raw_os_error() == Some(ERROR_PIPE_CONNECTED as i32)
        {
            // The client connected before ConnectNamedPipe. No request was
            // issued, so do not query an OVERLAPPED result for this case.
            if unsafe { SetEvent(self.event_handle()) } == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        } else {
            self.started(success)
        }
    }
    pub fn write(&mut self, data: &[u8]) -> io::Result<()> {
        self.write_transformed(data, |_| Ok(()))
    }
    /// Copy and transform in owned storage before issuing the write. The
    /// callback cannot retain or resize the kernel-borrowed buffer, and a
    /// failed transform never starts an I/O request.
    pub fn write_transformed<E>(
        &mut self,
        data: &[u8],
        transform: impl FnOnce(&mut [u8]) -> Result<(), E>,
    ) -> Result<(), E>
    where
        E: From<io::Error>,
    {
        self.prepare()?;
        if data.len() > self.buffer.len() {
            return Err(io::Error::from(io::ErrorKind::InvalidInput).into());
        }
        let buffer = &mut self.buffer[..data.len()];
        buffer.copy_from_slice(data);
        transform(buffer)?;
        // SAFETY: our own copy, not caller memory, stays alive until completion.
        let success = unsafe {
            WriteFile(
                self.handle.as_raw_handle(),
                self.buffer.as_ptr(),
                data.len() as u32,
                std::ptr::null_mut(),
                &mut *self.overlapped,
            )
        };
        self.started(success).map_err(E::from)
    }
    pub fn ioctl(&mut self, code: u32, input: &[u8]) -> io::Result<()> {
        self.prepare()?;
        if input.len() > self.buffer.len() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        self.buffer[..input.len()].copy_from_slice(input);
        // SAFETY: METHOD_BUFFERED controls use the same owned input/output area.
        let success = unsafe {
            DeviceIoControl(
                self.handle.as_raw_handle(),
                code,
                self.buffer.as_ptr().cast(),
                input.len() as u32,
                self.buffer.as_mut_ptr().cast(),
                self.buffer.len() as u32,
                std::ptr::null_mut(),
                &mut *self.overlapped,
            )
        };
        self.started(success)
    }
    pub fn finish(&mut self, timeout_ms: u32) -> io::Result<usize> {
        if !self.pending {
            return Ok(self.immediate as usize);
        }
        match self.ready(timeout_ms) {
            Ok(true) => {}
            result => {
                self.cancel();
                result?;
                return Err(io::ErrorKind::TimedOut.into());
            }
        }
        let mut len = 0;
        // SAFETY: the event signals completion and storage is still live.
        let success = unsafe {
            GetOverlappedResult(self.handle.as_raw_handle(), &*self.overlapped, &mut len, 1)
        };
        self.pending = false;
        if success == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(len as usize)
    }
    pub fn cancel(&mut self) {
        if self.pending {
            // SAFETY: cancellation alone does not end the borrow. Wait for the
            // final status before freeing/reusing OVERLAPPED or its buffer.
            unsafe {
                CancelIoEx(self.handle.as_raw_handle(), &*self.overlapped);
                let mut len = 0;
                GetOverlappedResult(self.handle.as_raw_handle(), &*self.overlapped, &mut len, 1);
            }
            self.pending = false;
        }
    }
}
impl Drop for Operation {
    fn drop(&mut self) {
        self.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::OpenOptions, os::windows::fs::OpenOptionsExt};
    use windows_sys::Win32::Storage::FileSystem::FILE_FLAG_OVERLAPPED;

    #[test]
    fn transformed_writes_keep_owned_storage_and_do_not_issue_failed_transforms() {
        let path = std::env::temp_dir().join(format!(
            "openrad-owned-write-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .custom_flags(FILE_FLAG_OVERLAPPED)
            .open(&path)
            .unwrap();
        let handle: OwnedHandle = file.into();
        let mut operation = Operation::new(Arc::new(handle), crate::tunnel::MAX_FRAME).unwrap();
        let buffer = operation.buffer.as_ptr();
        let original = b"synthetic owned packet".to_vec();
        let expected: Vec<_> = original.iter().map(|byte| byte ^ 0x5a).collect();
        operation
            .write_transformed(&original, |frame| {
                for byte in frame {
                    *byte ^= 0x5a;
                }
                Ok::<_, io::Error>(())
            })
            .unwrap();
        assert_eq!(operation.finish(1000).unwrap(), original.len());
        assert_eq!(operation.buffer.as_ptr(), buffer);
        assert_eq!(operation.data(original.len()), expected);
        assert_eq!(std::fs::read(&path).unwrap(), expected);
        let error = operation
            .write_transformed(&original, |frame| {
                frame.fill(0);
                Err::<(), _>(io::Error::from(io::ErrorKind::InvalidData))
            })
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(!operation.pending);
        assert_eq!(operation.finish(0).unwrap(), 0);
        assert_eq!(std::fs::read(&path).unwrap(), expected);
        assert_eq!(
            operation
                .write(&vec![0; crate::tunnel::MAX_FRAME + 1])
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );
        operation.write(&original).unwrap();
        assert_eq!(operation.finish(1000).unwrap(), original.len());
        assert_eq!(operation.buffer.as_ptr(), buffer);
        assert_eq!(std::fs::read(&path).unwrap(), original);
        drop(operation);
        std::fs::remove_file(path).unwrap();
    }
}

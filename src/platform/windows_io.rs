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
        self.prepare()?;
        if data.len() > self.buffer.len() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        self.buffer[..data.len()].copy_from_slice(data);
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
        self.started(success)
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

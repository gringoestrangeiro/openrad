//! Start the detached Windows service without inheriting parent pipe handles.
//! `std::process::Command` may inherit the CLI's captured stdout/stderr when
//! spawning a long-lived child, leaving callers waiting for EOF indefinitely.

use std::{
    ffi::{OsStr, OsString},
    io,
    mem::size_of,
    os::windows::{
        ffi::OsStrExt,
        io::{AsRawHandle, FromRawHandle, OwnedHandle},
    },
    path::Path,
};
use windows_sys::Win32::{
    Foundation::{WAIT_OBJECT_0, WAIT_TIMEOUT},
    System::Threading::{
        CreateProcessW, GetExitCodeProcess, WaitForSingleObject, CREATE_NEW_PROCESS_GROUP,
        CREATE_NO_WINDOW, PROCESS_INFORMATION, STARTUPINFOW,
    },
};

pub(crate) struct ServiceProcess {
    process: OwnedHandle,
    pid: u32,
}

impl ServiceProcess {
    pub(crate) fn id(&self) -> u32 {
        self.pid
    }

    pub(crate) fn try_exit_code(&self) -> io::Result<Option<u32>> {
        // SAFETY: the owned process handle remains valid for this wait.
        let wait = unsafe { WaitForSingleObject(self.process.as_raw_handle(), 0) };
        match wait {
            WAIT_TIMEOUT => Ok(None),
            WAIT_OBJECT_0 => {
                let mut code = 0u32;
                // SAFETY: code points to a valid u32; the process handle is owned.
                if unsafe { GetExitCodeProcess(self.process.as_raw_handle(), &mut code) } == 0 {
                    return Err(io::Error::last_os_error());
                }
                Ok(Some(code))
            }
            _ => Err(io::Error::last_os_error()),
        }
    }
}

fn nul_terminated(value: &OsStr) -> io::Result<Vec<u16>> {
    let mut wide: Vec<u16> = value.encode_wide().collect();
    if wide.contains(&0) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "service path contains NUL",
        ));
    }
    wide.push(0);
    Ok(wide)
}

// Windows argv quoting: escape embedded quotes and double trailing backslashes.
fn quoted(value: &OsStr) -> io::Result<Vec<u16>> {
    let mut output = vec![b'"' as u16];
    let mut slashes = 0usize;
    for code in value.encode_wide() {
        if code == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "NUL in argument",
            ));
        }
        if code == b'\\' as u16 {
            slashes += 1;
            continue;
        }
        if code == b'"' as u16 {
            output.extend(std::iter::repeat_n(b'\\' as u16, slashes * 2 + 1));
            output.push(code);
        } else {
            output.extend(std::iter::repeat_n(b'\\' as u16, slashes));
            output.push(code);
        }
        slashes = 0;
    }
    output.extend(std::iter::repeat_n(b'\\' as u16, slashes * 2));
    output.push(b'"' as u16);
    Ok(output)
}

pub(crate) fn spawn(
    executable: &Path,
    data_dir: &Path,
    disable_interface: bool,
) -> io::Result<ServiceProcess> {
    let application = nul_terminated(executable.as_os_str())?;
    let mut args: Vec<OsString> = vec![
        executable.as_os_str().to_owned(),
        "--data-dir".into(),
        data_dir.as_os_str().to_owned(),
        "__daemon".into(),
    ];
    if disable_interface {
        args.push("--no-tap".into());
    }
    let mut command_line = Vec::new();
    for arg in &args {
        if !command_line.is_empty() {
            command_line.push(b' ' as u16);
        }
        command_line.extend(quoted(arg)?);
    }
    command_line.push(0);

    // SAFETY: the structures are plain Windows API records and are fully
    // initialized before use, including their required size and zero flags.
    let mut startup: STARTUPINFOW = unsafe { std::mem::zeroed() };
    startup.cb = size_of::<STARTUPINFOW>() as u32;
    // SAFETY: CreateProcessW fills every field of PROCESS_INFORMATION on success.
    let mut created: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    // SAFETY: all input pointers reference live buffers. In particular,
    // bInheritHandles=FALSE prevents leaking CLI stdout/stderr capture pipes.
    let ok = unsafe {
        CreateProcessW(
            application.as_ptr(),
            command_line.as_mut_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            0,
            CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP,
            std::ptr::null(),
            std::ptr::null(),
            &startup,
            &mut created,
        )
    };
    if ok == 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: successful CreateProcessW transfers two owned handles to us.
    let process = unsafe { OwnedHandle::from_raw_handle(created.hProcess) };
    let thread = unsafe { OwnedHandle::from_raw_handle(created.hThread) };
    drop(thread);
    Ok(ServiceProcess {
        process,
        pid: created.dwProcessId,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_line_quotes_windows_paths_and_trailing_backslashes() {
        fn decoded(value: &[u16]) -> String {
            String::from_utf16(value).unwrap()
        }
        assert_eq!(
            decoded(&quoted(OsStr::new(r"C:\Program Files\OpenRad\")).unwrap()),
            r#""C:\Program Files\OpenRad\\""#
        );
        assert_eq!(
            decoded(&quoted(OsStr::new("with\"quote")).unwrap()),
            "\"with\\\"quote\""
        );
    }
}

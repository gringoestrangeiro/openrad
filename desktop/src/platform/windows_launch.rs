//! Elevate GUI startup after parsing help/version, keeping test harnesses headless.
fn command_line(arguments: impl IntoIterator<Item = Vec<u16>>) -> Vec<u16> {
    let mut result = Vec::new();
    for argument in arguments {
        if !result.is_empty() {
            result.push(b' ' as u16);
        }
        result.push(b'"' as u16);
        let mut slashes = 0;
        for code in argument {
            if code == b'\\' as u16 {
                slashes += 1;
                continue;
            }
            result.extend(std::iter::repeat_n(
                b'\\' as u16,
                if code == b'"' as u16 {
                    slashes * 2 + 1
                } else {
                    slashes
                },
            ));
            slashes = 0;
            result.push(code);
        }
        result.extend(std::iter::repeat_n(b'\\' as u16, slashes * 2));
        result.push(b'"' as u16);
    }
    result.push(0);
    result
}
#[cfg(windows)]
pub fn ensure_elevated() -> anyhow::Result<bool> {
    use std::os::windows::{ffi::OsStrExt, io::AsRawHandle};
    use windows_sys::{
        core::w,
        Win32::{
            Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY},
            System::Threading::{GetCurrentProcess, OpenProcessToken},
            UI::{
                Shell::{ShellExecuteExW, SEE_MASK_NOASYNC, SHELLEXECUTEINFOW},
                WindowsAndMessaging::SW_SHOWNORMAL,
            },
        },
    };
    let mut raw = std::ptr::null_mut();
    anyhow::ensure!(
        unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw) } != 0,
        "Cannot check Windows administrator permissions: {}",
        std::io::Error::last_os_error()
    );
    let token = unsafe { openrad::windows_io::owned(raw) }?;
    let mut elevation = TOKEN_ELEVATION::default();
    let mut size = 0;
    anyhow::ensure!(
        unsafe {
            GetTokenInformation(
                token.as_raw_handle(),
                TokenElevation,
                (&mut elevation as *mut TOKEN_ELEVATION).cast(),
                std::mem::size_of_val(&elevation) as u32,
                &mut size,
            )
        } != 0,
        "Cannot check Windows administrator permissions: {}",
        std::io::Error::last_os_error()
    );
    if elevation.TokenIsElevated != 0 {
        return Ok(true);
    }
    let executable = std::env::current_exe()?;
    let executable: Vec<u16> = executable
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let parameters = command_line(
        std::env::args_os()
            .skip(1)
            .map(|arg| arg.encode_wide().collect()),
    );
    let mut request = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOASYNC,
        lpVerb: w!("runas"),
        lpFile: executable.as_ptr(),
        lpParameters: parameters.as_ptr(),
        nShow: SW_SHOWNORMAL,
        ..Default::default()
    };
    anyhow::ensure!(
        unsafe { ShellExecuteExW(&mut request) } != 0,
        "Windows administrator approval is required to open OpenRad: {}",
        std::io::Error::last_os_error()
    );
    Ok(false)
}

/// Keep a small elevated parent alive to record native crashes even when Rust's
/// panic hook cannot run. Only the child creates the UI, profile lock and VPN.
#[cfg(windows)]
pub fn supervise_desktop() -> anyhow::Result<()> {
    use std::{
        os::windows::process::CommandExt,
        process::{Command, Stdio},
    };
    let mut child = Command::new(std::env::current_exe()?)
        .args(std::env::args_os().skip(1))
        .arg("--diagnostic-child")
        .env(
            "OPENRAD_DESKTOP_LOG_DIR",
            crate::startup_log::path().parent().unwrap(),
        )
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::from(crate::startup_log::stderr_file()?))
        .creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW)
        .spawn()?;
    crate::startup_log::event(format_args!(
        "Crash monitor started desktop child; child_pid={}",
        child.id()
    ));
    let status = child.wait()?;
    let code = status.code().unwrap_or(-1) as u32;
    crate::startup_log::event(format_args!(
        "Desktop child exited; child_pid={}; exit_code={code}; exit_hex=0x{code:08X}; success={}",
        child.id(),
        status.success()
    ));
    if !status.success() {
        anyhow::bail!("Desktop process exited unexpectedly (0x{code:08X}). See the startup log for the last completed stage and panic/graphics details.");
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn elevation_preserves_unicode_spaces_quotes_and_trailing_backslashes() {
        let args = ["--data-dir", "C:\\Users\\João\\VPN files\\", "a\"b", ""];
        let result = command_line(args.map(|a| a.encode_utf16().collect()));
        assert_eq!(
            String::from_utf16(&result[..result.len() - 1]).unwrap(),
            "\"--data-dir\" \"C:\\Users\\João\\VPN files\\\\\" \"a\\\"b\" \"\""
        );
        assert_eq!(result.last(), Some(&0));
    }
}

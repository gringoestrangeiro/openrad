//! Bounded HTTPS release request through the system curl client.
use anyhow::{ensure, Context, Result};
use std::{
    io::Read,
    process::{Command, Stdio},
};

const API_URL: &str = "https://api.github.com/repos/gringoestrangeiro/openrad/releases/latest";
const RESPONSE_LIMIT: u64 = 1024 * 1024;

/// Use the operating system's HTTPS client rather than adding a TLS stack to
/// the VPN. curl verifies certificates and enforces connection/transfer limits.
pub(crate) fn fetch_latest() -> Result<Vec<u8>> {
    #[cfg(windows)]
    let executable = crate::windows_security::system_directory()?.join("curl.exe");
    #[cfg(not(windows))]
    let executable = "/usr/bin/curl";
    let mut command = Command::new(executable);
    command
        .args([
            "--disable",
            "--silent",
            "--show-error",
            "--fail",
            "--location",
            "--proto",
            "=https",
            "--proto-redir",
            "=https",
            "--connect-timeout",
            "5",
            "--max-time",
            "15",
            "--max-filesize",
            "1048576",
            "--user-agent",
            concat!("OpenRad/", env!("CARGO_PKG_VERSION")),
            "--header",
            "Accept: application/vnd.github+json",
            API_URL,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
    }
    let mut child = command.spawn().context("Release check is unavailable")?;
    let mut bytes = Vec::new();
    let read = child
        .stdout
        .take()
        .context("Release response is unavailable")?
        .take(RESPONSE_LIMIT + 1)
        .read_to_end(&mut bytes);
    if read.is_err() || bytes.len() as u64 > RESPONSE_LIMIT {
        let _ = child.kill();
    }
    let status = child.wait()?;
    read?;
    ensure!(
        bytes.len() as u64 <= RESPONSE_LIMIT,
        "Release response is too large"
    );
    ensure!(status.success(), "Release check failed");
    Ok(bytes)
}

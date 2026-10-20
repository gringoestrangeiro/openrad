//! Start the Windows uninstall flow already shipped with the official NSIS installer.
//! The uninstaller preserves local profiles, saved identities and settings.
use anyhow::{bail, ensure, Context, Result};
use std::{fs, os::windows::ffi::OsStrExt};
use windows_sys::{
    core::w,
    Win32::UI::{
        Shell::{ShellExecuteExW, SEE_MASK_NOASYNC, SHELLEXECUTEINFOW},
        WindowsAndMessaging::SW_SHOWNORMAL,
    },
};

/// Ask the Windows shell to elevate and launch the installed NSIS uninstaller.
///
/// NSIS requires administrator approval, so CreateProcess/Command::spawn can
/// fail with ERROR_ELEVATION_REQUIRED. The shell displays the normal UAC dialog.
pub(crate) fn launch_installed_uninstaller() -> Result<()> {
    let executable = std::env::current_exe().context("Locate OpenRad Desktop")?;
    let directory = executable
        .parent()
        .context("Desktop has no installation folder")?;
    let uninstaller = directory.join("Uninstall-OpenRad.exe");
    let info = fs::symlink_metadata(&uninstaller)
        .context("Installed uninstaller not found. Use Windows Installed apps instead")?;
    if !info.is_file() || info.file_type().is_symlink() {
        bail!("OpenRad uninstaller is not a regular file");
    }
    // The Windows setup handles the user's confirmation, elevation, VPN stop,
    // dedicated adapter removal, and removal of only its installed binaries.
    // /FROMSETTINGS gives the desktop time to close before NSIS starts.
    let path: Vec<u16> = uninstaller
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    ensure!(
        !path[..path.len() - 1].contains(&0),
        "Uninstaller path contains an invalid NUL character"
    );
    let mut request = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOASYNC,
        lpVerb: w!("runas"),
        lpFile: path.as_ptr(),
        lpParameters: w!("/FROMSETTINGS"),
        nShow: SW_SHOWNORMAL,
        ..Default::default()
    };
    ensure!(
        unsafe { ShellExecuteExW(&mut request) } != 0,
        "Could not start {}: {}",
        uninstaller.display(),
        std::io::Error::last_os_error()
    );
    Ok(())
}

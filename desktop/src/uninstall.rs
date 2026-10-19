//! Start the Windows uninstall flow already shipped with the official NSIS installer.
//! The uninstaller preserves local profiles, saved identities and settings.
use anyhow::{bail, Context, Result};
use std::{fs, process::Command};

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
    Command::new(&uninstaller)
        .arg("/FROMSETTINGS")
        .spawn()
        .with_context(|| format!("Could not start {}", uninstaller.display()))?;
    Ok(())
}

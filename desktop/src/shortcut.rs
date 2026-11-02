//! Optional first-run Windows desktop shortcut. Never modifies existing user
//! settings or the VPN service.
use anyhow::{ensure, Context, Result};
use std::process::Command;

pub fn create_desktop_shortcut() -> Result<()> {
    let exe = std::env::current_exe().context("Locate OpenRad executable")?;
    let script = r#"
$ErrorActionPreference = 'Stop'
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
if ([string]::IsNullOrWhiteSpace($desktop)) { throw 'Desktop folder is unavailable' }
$path = Join-Path $desktop 'OpenRad.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($path)
$shortcut.TargetPath = $env:OPENRAD_SHORTCUT_EXE
$shortcut.WorkingDirectory = Split-Path $env:OPENRAD_SHORTCUT_EXE -Parent
$ico = Join-Path (Split-Path $env:OPENRAD_SHORTCUT_EXE -Parent) 'logo.ico'
if (Test-Path -LiteralPath $ico -PathType Leaf) {
    $shortcut.IconLocation = "$ico,0"
} else {
    $shortcut.IconLocation = "$($env:OPENRAD_SHORTCUT_EXE),0"
}
$shortcut.Description = 'OpenRad'
$shortcut.Save()
"#;
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .env("OPENRAD_SHORTCUT_EXE", exe)
        .output()
        .context("Start Windows shortcut helper")?;
    ensure!(
        output.status.success(),
        "Shortcut creation failed: {}",
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(())
}

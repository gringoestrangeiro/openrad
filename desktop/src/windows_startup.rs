//! Windows logon task for starting the existing VPN daemon without a GUI.
//! Enabled by default on Windows, and users can turn it off in Settings.
//! Registration occurs only after a saved identity is available and uses
//! Task Scheduler's interactive, highest-available token; no password is stored.
use crate::storage::{Paths, Settings};
use anyhow::{bail, ensure, Context, Result};
use std::{
    ffi::OsString,
    os::windows::ffi::OsStringExt,
    path::{Path, PathBuf},
    process::Command,
};

const SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
$taskName = $env:OPENRAD_STARTUP_TASK
$exe = $env:OPENRAD_STARTUP_EXEC
$dataDir = $env:OPENRAD_STARTUP_DIR
$enabled = $env:OPENRAD_STARTUP_ENABLE -eq '1'
$user = [Security.Principal.WindowsIdentity]::GetCurrent()
$existing = Get-ScheduledTask -TaskName $taskName -TaskPath '\' -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    $owner = $existing.Principal.UserId
    try {
        if ($owner -match '^S-1-') {
            $ownerSid = ([Security.Principal.SecurityIdentifier]::new($owner)).Value
        } else {
            $ownerSid = ([Security.Principal.NTAccount]::new($owner)).Translate([Security.Principal.SecurityIdentifier]).Value
        }
    } catch {
        throw 'The owner of the existing OpenRad startup task could not be verified.'
    }
    if ($ownerSid -ne $user.User.Value) {
        throw 'A different Windows user owns the named OpenRad startup task.'
    }
}
if (-not $enabled) {
    if ($null -ne $existing) {
        Unregister-ScheduledTask -TaskName $taskName -TaskPath '\' -Confirm:$false -ErrorAction Stop
    }
    return
}
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw 'The OpenRad desktop executable no longer exists.'
}
if ($exe.Contains('"') -or $dataDir.Contains('"')) {
    throw 'An executable or profile path contains unsupported quote characters.'
}
$trustedRoots = @(
    [Environment]::GetFolderPath('ProgramFiles'),
    [Environment]::GetFolderPath('ProgramFilesX86')
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$trusted = $false
foreach ($root in $trustedRoots) {
    if ($exe.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        $trusted = $true
        break
    }
}
if (-not $trusted) {
    throw 'Automatic elevated startup requires OpenRad installed under Program Files, not a user-writable folder.'
}
if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $exe -Parent) 'openrad.exe') -PathType Leaf)) {
    throw 'The OpenRad CLI is missing next to the desktop executable.'
}
$arguments = '--background --data-dir "' + $dataDir + '"'
$action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory ([IO.Path]::GetDirectoryName($exe))
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user.Name
$principal = New-ScheduledTaskPrincipal -UserId $user.User.Value -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -TaskPath '\' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force -ErrorAction Stop | Out-Null
$registered = Get-ScheduledTask -TaskName $taskName -TaskPath '\' -ErrorAction Stop
if ($registered.Actions.Count -ne 1 -or $registered.Actions[0].Execute -ne $exe -or $registered.Actions[0].Arguments -ne $arguments) {
    throw 'The startup task was not registered as requested.'
}
"#;

/// Stable task name per Windows profile path, including for installations
/// that use --data-dir. Avoids overwriting a task from another profile.
fn task_name(path: &Path) -> String {
    let mut fingerprint = 0xcbf29ce484222325u64;
    let normalized = path.to_string_lossy().to_lowercase();
    for code in normalized.encode_utf16() {
        for byte in code.to_le_bytes() {
            fingerprint ^= u64::from(byte);
            fingerprint = fingerprint.wrapping_mul(0x100000001b3);
        }
    }
    format!("OpenRad-VPN-{fingerprint:016x}")
}

pub fn background_allowed(settings: &Settings) -> bool {
    settings.onboarding_complete && settings.start_with_windows
}

/// Never schedule a privileged VPN launcher before provisioning has saved an
/// identity. The first-run welcome page may save preferences before Connect.
pub fn ready_to_register(settings: &Settings, identity_persisted: bool) -> bool {
    background_allowed(settings) && identity_persisted
}

fn system_powershell() -> Result<PathBuf> {
    use windows_sys::Win32::System::SystemInformation::GetSystemDirectoryW;
    let mut buffer = [0u16; 32768];
    let length = unsafe { GetSystemDirectoryW(buffer.as_mut_ptr(), buffer.len() as u32) };
    ensure!(
        length > 0 && (length as usize) < buffer.len(),
        "Could not locate the Windows system directory"
    );
    let system_dir = PathBuf::from(OsString::from_wide(&buffer[..length as usize]));
    let powershell = system_dir.join("WindowsPowerShell\\v1.0\\powershell.exe");
    ensure!(powershell.is_file(), "Windows PowerShell is unavailable");
    Ok(powershell)
}

const REMOVE_SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
$exe = $env:OPENRAD_STARTUP_EXEC
$tasks = @(Get-ScheduledTask -TaskPath '\' -TaskName 'OpenRad-VPN-*' -ErrorAction SilentlyContinue)
foreach ($task in $tasks) {
    $actions = @($task.Actions)
    if ($actions.Count -ne 1 -or [string]::IsNullOrWhiteSpace($actions[0].Execute)) { continue }
    try { $actual = [IO.Path]::GetFullPath($actions[0].Execute) } catch { continue }
    if ($actual -eq $exe) {
        Unregister-ScheduledTask -TaskName $task.TaskName -TaskPath '\' -Confirm:$false -ErrorAction Stop
    }
}
"#;

/// Remove only logon entries whose action targets this exact installed binary.
/// The NSIS uninstaller uses this before removing the executable.
pub fn remove_tasks_for_install() -> Result<()> {
    let exe = std::env::current_exe().context("Locate installed OpenRad Desktop")?;
    let output = Command::new(system_powershell()?)
        .args(["-NoProfile", "-NonInteractive", "-Command", REMOVE_SCRIPT])
        .env("OPENRAD_STARTUP_EXEC", exe)
        .output()
        .context("Clean OpenRad logon tasks before uninstall")?;
    ensure!(
        output.status.success(),
        "Could not remove the OpenRad Windows startup tasks: {}",
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(())
}

pub fn set_start_with_windows(paths: &Paths, enabled: bool) -> Result<()> {
    let exe = std::env::current_exe().context("Locate OpenRad Desktop for Windows startup")?;
    ensure!(
        exe.is_file(),
        "OpenRad Desktop executable was not found at {}",
        exe.display()
    );
    ensure!(
        !exe.as_os_str().to_string_lossy().contains('"')
            && !paths.directory.as_os_str().to_string_lossy().contains('"'),
        "Windows startup does not support quotes in executable or profile paths"
    );

    // No user input is interpolated into the PowerShell source. Inputs are
    // supplied only to this subprocess's environment.
    let output = Command::new(system_powershell()?)
        .args(["-NoProfile", "-NonInteractive", "-Command", SCRIPT])
        .env("OPENRAD_STARTUP_TASK", task_name(&paths.directory))
        .env("OPENRAD_STARTUP_EXEC", &exe)
        .env("OPENRAD_STARTUP_DIR", &paths.directory)
        .env("OPENRAD_STARTUP_ENABLE", if enabled { "1" } else { "0" })
        .output()
        .context("Run the Windows Task Scheduler registration helper")?;
    if !output.status.success() {
        let message = String::from_utf8_lossy(&output.stderr);
        let detail = message.trim();
        if detail.is_empty() {
            bail!(
                "Windows could not {} the OpenRad background startup task (exit code: {}).",
                if enabled { "register" } else { "remove" },
                output.status
            );
        }
        bail!("Windows background startup task: {detail}");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn windows_default_starts_only_after_onboarding_and_identity_persistence() {
        let mut settings = Settings::default();
        assert!(settings.start_with_windows);
        assert!(!background_allowed(&settings));
        assert!(!ready_to_register(&settings, true));
        settings.onboarding_complete = true;
        assert!(background_allowed(&settings));
        assert!(!ready_to_register(&settings, false));
        assert!(ready_to_register(&settings, true));
        settings.start_with_windows = false;
        assert!(!background_allowed(&settings));
        assert!(!ready_to_register(&settings, true));
    }

    #[test]
    fn logon_tasks_are_distinct_by_profile_and_stable_for_case_variations() {
        let first = task_name(&PathBuf::from(r"C:\Users\Example\AppData\Local\OpenRad"));
        let same = task_name(&PathBuf::from(r"c:\users\EXAMPLE\appdata\local\openrad"));
        let other = task_name(&PathBuf::from(
            r"C:\Users\Example\AppData\Local\OpenRad-alt",
        ));
        assert_eq!(first, same);
        assert_ne!(first, other);
        assert!(first.starts_with("OpenRad-VPN-"));
    }
}

//! Platform boundary for the Ethernet interface and setup-only privilege helper.
#[cfg(target_os = "linux")]
#[path = "platform/linux.rs"]
mod implementation;
#[cfg(windows)]
#[path = "platform/windows.rs"]
mod implementation;
#[cfg(not(any(target_os = "linux", windows)))]
#[path = "platform/unsupported.rs"]
mod implementation;
pub use implementation::*;

#[cfg(any(windows, test))]
#[path = "platform/tap_windows_contract.rs"]
mod windows_contract;

pub fn is_privileged() -> bool {
    #[cfg(unix)]
    {
        unsafe { libc::geteuid() == 0 }
    }
    #[cfg(not(unix))]
    {
        false
    }
}
pub fn user_id() -> u32 {
    #[cfg(unix)]
    {
        unsafe { libc::getuid() }
    }
    #[cfg(not(unix))]
    {
        0
    }
}

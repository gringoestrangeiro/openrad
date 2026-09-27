//! Platform boundary for the Ethernet interface and setup-only privilege helper.
#[cfg(target_os = "linux")]
#[path = "platform/linux.rs"]
mod implementation;
#[cfg(not(target_os = "linux"))]
#[path = "platform/unsupported.rs"]
mod implementation;
pub use implementation::*;

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

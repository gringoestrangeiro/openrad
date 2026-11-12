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

pub const DEFAULT_LINUX_INTERFACE_NAME: &str = "radminvpn0";

/// Linux IFNAMSIZ is 16 bytes including the terminating NUL. Names stay
/// ASCII-only to prevent whitespace, metacharacters, and truncation.
pub fn validate_linux_interface_name(name: &str) -> anyhow::Result<()> {
    use anyhow::ensure;
    let bytes = name.as_bytes();
    ensure!(
        (1..=15).contains(&bytes.len())
            && bytes[0].is_ascii_alphanumeric()
            && bytes.iter().skip(1).all(|byte| {
                byte.is_ascii_alphanumeric() || matches!(*byte, b'_' | b'-' | b'.')
            }),
        "Linux interface name must be 1-15 ASCII characters, start with a letter or digit, and contain only letters, digits, '.', '_' or '-'"
    );
    Ok(())
}

#[cfg(test)]
mod interface_name_tests {
    use super::*;

    #[test]
    fn accepts_valid_linux_interface_names() {
        for name in [
            "radminvpn0",
            "openrad0",
            "vpn-lucas",
            "minecraft0",
            "v_0.1",
            "a",
            "a12345678901234",
        ] {
            validate_linux_interface_name(name).unwrap();
        }
    }

    #[test]
    fn rejects_unsafe_or_truncated_linux_interface_names() {
        for name in [
            "",
            "a123456789012345",
            "-dev",
            ".hidden",
            "../tap",
            "a/b",
            "a b",
            "a\n",
            "é",
            "a:0",
            "a;ip",
            "a\\b",
            "--",
            "🚀",
        ] {
            assert!(validate_linux_interface_name(name).is_err(), "{name:?}");
        }
    }
}

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

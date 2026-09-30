//! Reusable protocol, session, peer, tunnel and long-lived application engine.
pub mod client;
mod config;
pub mod crypto;
pub mod diagnostics;
#[doc(hidden)]
pub mod early_log;
pub mod i18n;
pub mod incoming;
pub mod network;
pub mod output;
pub mod peer;
pub mod protocol;
pub mod runtime;
mod scheduling;
pub mod session;
#[doc(hidden)]
pub mod setup;
pub mod tap;
pub mod tunnel;
pub mod udp;
mod wake;
#[cfg(windows)]
#[path = "platform/windows_crash.rs"]
mod windows_crash;
#[cfg(windows)]
#[doc(hidden)]
#[path = "platform/windows_io.rs"]
pub mod windows_io;
#[cfg(any(windows, test))]
#[path = "platform/windows_radmin.rs"]
mod windows_radmin;
#[cfg(windows)]
#[doc(hidden)]
#[path = "platform/windows_security.rs"]
pub mod windows_security;
#[cfg(windows)]
#[doc(hidden)]
#[path = "platform/windows_setup.rs"]
pub mod windows_setup;

pub use config::{DEFAULT_BOOTSTRAP_HOST, SERVER_MODULUS};

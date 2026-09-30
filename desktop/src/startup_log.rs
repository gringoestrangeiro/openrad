//! Desktop checkpoints on the shared early diagnostics writer.
pub use openrad::early_log::{event, fatal_error};
#[cfg(windows)]
pub use openrad::early_log::{path, stderr_file};
use std::fmt;

pub fn init() {
    openrad::early_log::init("desktop");
}

pub enum Stage {
    ProcessReturned,
    #[cfg(windows)]
    CheckingElevation,
    #[cfg(windows)]
    UacRelaunch,
    #[cfg(windows)]
    Elevated,
    #[cfg(windows)]
    MonitoredChild,
    OpeningProfile,
    LockingProfile,
    ProfileLocked,
    AppCreator,
    ConfiguringFonts,
    FontsReady,
    StartingBackend,
    BackendStarted,
    BackendSettings,
    BackendVault,
    BackendIdentity,
    BackendIdentityReady,
    BackendReady,
    FirstLogic,
    CloseRequested,
    BackendStopped,
    FirstUi,
    FirstUiCompleted,
}

pub fn checkpoint(stage: Stage) {
    let message = match stage {
        Stage::ProcessReturned => "Process returned normally",
        #[cfg(windows)]
        Stage::CheckingElevation => "Checking administrator elevation",
        #[cfg(windows)]
        Stage::UacRelaunch => "UAC relaunch requested; original process is exiting",
        #[cfg(windows)]
        Stage::Elevated => "Administrator elevation confirmed",
        #[cfg(windows)]
        Stage::MonitoredChild => "Monitored desktop child entered startup",
        Stage::OpeningProfile => "Opening desktop profile directory",
        Stage::LockingProfile => "Acquiring desktop profile lock",
        Stage::ProfileLocked => "Desktop profile lock acquired",
        Stage::AppCreator => "App creator entered; graphics initialization completed",
        Stage::ConfiguringFonts => "Configuring desktop fonts and appearance",
        Stage::FontsReady => "Desktop fonts and appearance configured; loading settings",
        Stage::StartingBackend => "Starting desktop backend thread",
        Stage::BackendStarted => "Desktop backend thread started",
        Stage::BackendSettings => "Backend loading settings",
        Stage::BackendVault => "Backend opening credential store",
        Stage::BackendIdentity => "Backend reading identity from credential store",
        Stage::BackendIdentityReady => "Backend identity loading completed",
        Stage::BackendReady => "Backend initialization completed",
        Stage::FirstLogic => "First desktop logic pass entered",
        Stage::CloseRequested => "Window close requested; shutting down backend",
        Stage::BackendStopped => "Backend stopped; closing desktop window",
        Stage::FirstUi => "First desktop UI pass entered",
        Stage::FirstUiCompleted => "First desktop UI pass completed",
    };
    event(format_args!("{message}"));
}

pub fn arguments(renderer: impl fmt::Debug) {
    event(format_args!("Arguments parsed; renderer={renderer:?}"));
}

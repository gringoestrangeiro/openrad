//! Early, synchronous diagnostics, independent of profiles and the VPN backend.
use std::{
    backtrace::Backtrace,
    fmt,
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const MAX_BYTES: u64 = 4 * 1024 * 1024;
static LOGGER: OnceLock<StartupLog> = OnceLock::new();

struct StartupLog {
    file: Mutex<File>,
    path: PathBuf,
    started: Instant,
    #[cfg(windows)]
    component: &'static str,
}

fn open_file(path: &Path) -> std::io::Result<File> {
    let mut options = OpenOptions::new();
    options.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

fn open_log(directory: &Path, component: &'static str) -> anyhow::Result<StartupLog> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(directory)?;
    crate::output::secure_directory(directory)?;
    let path = directory.join(format!("{component}-startup.log"));
    if fs::metadata(&path).is_ok_and(|metadata| metadata.len() >= MAX_BYTES) {
        let previous = directory.join(format!("{component}-startup.previous.log"));
        let _ = fs::remove_file(&previous);
        let _ = fs::rename(&path, previous);
    }
    Ok(StartupLog {
        file: Mutex::new(open_file(&path)?),
        path,
        started: Instant::now(),
        #[cfg(windows)]
        component,
    })
}

fn graphics_target(target: &str) -> bool {
    [
        "eframe",
        "egui_wgpu",
        "egui_glow",
        "wgpu",
        "glutin",
        "winit",
    ]
    .iter()
    .any(|prefix| target.starts_with(prefix))
}

impl StartupLog {
    fn write(&self, target: &str, message: String, checkpoint: bool) {
        let thread = std::thread::current();
        let record = serde_json::json!({
            "timestamp_ms": SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis(),
            "elapsed_ms": self.started.elapsed().as_millis(),
            "pid": std::process::id(),
            "thread": thread.name().unwrap_or("unnamed"),
            "target": target,
            "message": message,
        });
        let Ok(mut bytes) = serde_json::to_vec(&record) else {
            return;
        };
        bytes.push(b'\n');
        // A panic can occur while another record is being written. Never
        // deadlock a panic hook or delay the crash-monitor process on this lock.
        let Ok(mut file) = self.file.try_lock() else {
            return;
        };
        if !checkpoint
            && file
                .metadata()
                .is_ok_and(|metadata| metadata.len() >= MAX_BYTES)
        {
            return;
        }
        let _ = file.write_all(&bytes);
        if checkpoint {
            let _ = file.sync_data();
        }
    }
}

impl log::Log for StartupLog {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        metadata.level() <= log::Level::Debug && graphics_target(metadata.target())
    }
    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            self.write(
                record.target(),
                format!("{} {}", record.level(), record.args()),
                false,
            );
        }
    }
    fn flush(&self) {
        if let Ok(file) = self.file.try_lock() {
            let _ = file.sync_data();
        }
    }
}

pub fn init(component: &'static str) {
    let directory = std::env::var_os("OPENRAD_LOG_DIR")
        .or_else(|| {
            (component == "desktop")
                .then(|| std::env::var_os("OPENRAD_DESKTOP_LOG_DIR"))
                .flatten()
        })
        .map(PathBuf::from)
        .or_else(|| {
            directories::BaseDirs::new().map(|base| base.data_local_dir().join("OpenRad/logs"))
        })
        .unwrap_or_else(|| std::env::temp_dir().join("OpenRad/logs"));
    let logger = open_log(&directory, component)
        .or_else(|_| open_log(&std::env::temp_dir().join("OpenRad/logs"), component));
    match logger {
        Ok(logger) => {
            if LOGGER.set(logger).is_ok() {
                let _ = log::set_logger(LOGGER.get().unwrap());
                log::set_max_level(log::LevelFilter::Debug);
            }
        }
        Err(error) => eprintln!("OpenRad startup logging unavailable: {error:#}"),
    }
    std::panic::set_hook(Box::new(|info| {
        let location = info.location().map_or_else(
            || "unknown location".into(),
            |location| {
                format!(
                    "{}:{}:{}",
                    location.file(),
                    location.line(),
                    location.column()
                )
            },
        );
        event(format_args!("Rust panic at {location}"));
        // Graphics error messages contain driver/shader diagnostics. Do not
        // dump arbitrary VPN/backend panic payloads or in-memory credentials.
        if info.location().is_some_and(|location| {
            let file = location.file().replace('\\', "/");
            [
                "/eframe-",
                "/egui",
                "/wgpu",
                "/glutin",
                "/winit",
                "desktop/src/graphics.rs",
            ]
            .iter()
            .any(|name| file.contains(name))
        }) {
            if let Some(message) = info.payload().downcast_ref::<&str>() {
                event(format_args!("Graphics panic: {message}"));
            } else if let Some(message) = info.payload().downcast_ref::<String>() {
                event(format_args!("Graphics panic: {message}"));
            }
        }
        event(format_args!(
            "Panic backtrace:\n{}",
            Backtrace::force_capture()
        ));
        eprintln!(
            "OpenRad Rust panic at {location}. Startup log: {}",
            path().display()
        );
    }));
    event(format_args!(
        "Process started; version={}; component={component}; os={}; arch={}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH
    ));
    #[cfg(windows)]
    crate::windows_crash::install();
    #[cfg(windows)]
    event(format_args!(
        "Windows account SID={:?}; administrator={:?}; session log={}",
        crate::windows_security::user_sid(),
        crate::windows_security::token_is_elevated(),
        path().display()
    ));
}

pub fn event(message: fmt::Arguments<'_>) {
    if let Some(logger) = LOGGER.get() {
        logger.write("openrad_startup", message.to_string(), true);
    }
}

pub fn path() -> PathBuf {
    LOGGER.get().map_or_else(
        || std::env::temp_dir().join("OpenRad/logs/desktop-startup.log"),
        |logger| logger.path.clone(),
    )
}

#[cfg(windows)]
pub fn stderr_file() -> anyhow::Result<File> {
    let component = LOGGER.get().map_or("desktop", |log| log.component);
    let path = path().with_file_name(format!("{component}-stderr.log"));
    if fs::metadata(&path).is_ok_and(|metadata| metadata.len() >= MAX_BYTES) {
        let _ = fs::remove_file(path.with_file_name(format!("{component}-stderr.previous.log")));
        let _ = fs::rename(
            &path,
            path.with_file_name(format!("{component}-stderr.previous.log")),
        );
    }
    Ok(open_file(&path)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn graphics_logging_excludes_vpn_and_credential_targets() {
        for target in [
            "wgpu_hal::dx12",
            "eframe::native",
            "egui_glow",
            "glutin",
            "winit",
        ] {
            assert!(graphics_target(target));
        }
        for target in [
            "openrad::session",
            "openrad::protocol",
            "keyring",
            "desktop::backend",
        ] {
            assert!(!graphics_target(target));
        }
    }
}

/// Command names and operation checkpoints only; never command arguments.
#[derive(Debug)]
pub enum Stage {
    ProfileOpen,
    ProfileReady,
    ServiceStarting,
    ServiceReady,
    ServiceExited,
    ServiceTimeout,
    ServiceProfileLoading,
    ServiceLock,
    ServiceListening,
    SessionStarting,
    ProcessReturned,
}

pub fn checkpoint(stage: Stage) {
    event(format_args!("CLI stage={stage:?}"));
}
pub fn command(kind: &str) {
    event(format_args!("CLI command={kind}; arguments omitted"));
}
pub fn child_started(pid: u32) {
    event(format_args!("Service spawned; child_pid={pid}"));
}
pub fn child_exited(status: std::process::ExitStatus) {
    event(format_args!(
        "Service child exited; code={:?}; status={status}",
        status.code()
    ));
}
pub fn reply(ok: bool, last_error: Option<&str>) {
    event(format_args!("CLI reply; ok={ok}"));
    if let Some(error) = last_error {
        event(format_args!("Last interface/connection error: {error}"));
    }
}
pub fn fatal_error(error: &anyhow::Error) {
    event(format_args!("Fatal process error: {error:#}"));
}

/// Each checkpoint is persisted before its Win32 call so even a native fault
/// or abrupt exit leaves the operation that was in progress.
#[derive(Debug)]
pub enum TapStage {
    DiscoverAdapter,
    ConvertInterfaceAliasToLuid,
    GetIfEntry2,
    CheckAddresses,
    CreateFileW,
    GetVersion,
    GetMac,
    GetMtu,
    SetMediaStatus,
    GetIpInterfaceEntry,
    SetIpInterfaceEntry,
    CreateUnicastIpAddressEntry,
    ConfigureRoutes,
    StartRead,
    Ready,
}
pub fn tap_step(stage: TapStage) {
    event(format_args!("TAP stage={stage:?}"));
}
pub fn tap_adapter(guid: &str) {
    event(format_args!("Selected TAP-Windows6 adapter; guid={guid}"));
}
pub fn tap_mac(mac: [u8; 6]) {
    event(format_args!(
        "TAP driver MAC={}",
        mac.map(|n| format!("{n:02x}")).join(":")
    ));
}

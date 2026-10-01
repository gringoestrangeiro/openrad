#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
mod app;
mod backend;
mod graphics;
mod network_ui;
mod startup_log;
mod storage;
#[cfg(any(windows, test))]
#[path = "platform/windows_launch.rs"]
mod windows_launch;
use clap::Parser;
use openrad::i18n::{self, LanguagePreference};
use std::{collections::BTreeSet, path::PathBuf};

#[derive(Parser)]
#[command(version, about = "OpenRad native desktop VPN client")]
struct Args {
    /// Display language: system, en, pt, ru or vi.
    #[arg(long)]
    language: Option<LanguagePreference>,
    /// Import an owned identity into the platform credential store once.
    #[arg(long)]
    identity: Option<PathBuf>,
    /// Isolated profile directory, useful for controlled interoperability tests.
    #[arg(long)]
    data_dir: Option<PathBuf>,
    /// Restrict application traffic to these owned test peers (normal mode permits all members).
    #[arg(long)]
    traffic_peer: Vec<u64>,
    /// Graphics renderer: auto, opengl, wgpu or software.
    #[arg(long, value_enum, default_value_t = graphics::RendererPreference::Auto)]
    renderer: graphics::RendererPreference,
    #[cfg(windows)]
    #[arg(long, hide = true)]
    diagnostic_child: bool,
}
fn main() {
    startup_log::init();
    #[cfg(unix)]
    openrad::resource_limits::configure_open_file_limit();
    let args: Vec<_> = std::env::args_os().collect();
    let language = i18n::language_for_args(&args, LanguagePreference::System);
    if let Err(e) = execute() {
        startup_log::fatal_error(&e);
        let message = language.message(&format!("{e:#}"));
        eprintln!("OpenRad: {}", message);
        #[cfg(windows)]
        {
            let location =
                language.message(&format!("Startup log: {}", startup_log::path().display()));
            let message = format!("{message}\n\n{location}");
            let message: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
            // The desktop has no console on Windows, so startup failures must
            // also be visible when started from Explorer.
            unsafe {
                windows_sys::Win32::UI::WindowsAndMessaging::MessageBoxW(
                    std::ptr::null_mut(),
                    message.as_ptr(),
                    windows_sys::core::w!("OpenRad"),
                    windows_sys::Win32::UI::WindowsAndMessaging::MB_OK
                        | windows_sys::Win32::UI::WindowsAndMessaging::MB_ICONERROR,
                );
            }
        }
        std::process::exit(1);
    }
    crate::startup_log::checkpoint(crate::startup_log::Stage::ProcessReturned);
}
fn execute() -> anyhow::Result<()> {
    let (args, _) = i18n::parse_localized::<Args>(LanguagePreference::System);
    startup_log::arguments(args.renderer);
    #[cfg(windows)]
    {
        crate::startup_log::checkpoint(crate::startup_log::Stage::CheckingElevation);
        if !windows_launch::ensure_elevated()? {
            crate::startup_log::checkpoint(crate::startup_log::Stage::UacRelaunch);
            return Ok(());
        }
        crate::startup_log::checkpoint(crate::startup_log::Stage::Elevated);
        if !args.diagnostic_child {
            return windows_launch::supervise_desktop();
        }
        crate::startup_log::checkpoint(crate::startup_log::Stage::MonitoredChild);
    }
    anyhow::ensure!(
        !openrad::tap::is_privileged(),
        "Run the desktop as your normal user. Only the TAP setup helper uses sudo."
    );
    crate::startup_log::checkpoint(crate::startup_log::Stage::OpeningProfile);
    let paths = storage::Paths::new(args.data_dir)?;
    crate::startup_log::checkpoint(crate::startup_log::Stage::LockingProfile);
    let lock = paths.lock()?;
    crate::startup_log::checkpoint(crate::startup_log::Stage::ProfileLocked);
    let options = openrad::runtime::Options {
        helper: Some(std::env::current_exe()?.with_file_name(if cfg!(windows) {
            "openrad.exe"
        } else {
            "openrad"
        })),
        traffic_peers: if args.traffic_peer.is_empty() {
            None
        } else {
            Some(BTreeSet::from_iter(args.traffic_peer))
        },
        ..Default::default()
    };
    let mut startup = Some((paths, lock, args.identity, args.language, options));
    graphics::run(args.renderer, |cc| {
        crate::startup_log::checkpoint(crate::startup_log::Stage::AppCreator);
        let (paths, lock, identity, language, options) = startup
            .take()
            .expect("desktop application must only be created once");
        Box::new(app::App::new(cc, paths, lock, identity, language, options))
    })
}

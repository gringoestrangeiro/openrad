#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
mod app;
mod backend;
mod storage;
use clap::Parser;
use std::{collections::BTreeSet, path::PathBuf};

#[derive(Parser)]
#[command(version, about = "OpenRad native desktop VPN client")]
struct Args {
    /// Import an owned identity into the platform credential store once.
    #[arg(long)]
    identity: Option<PathBuf>,
    /// Isolated profile directory, useful for controlled interoperability tests.
    #[arg(long)]
    data_dir: Option<PathBuf>,
    /// Restrict application traffic to these owned test peers (normal mode permits all members).
    #[arg(long)]
    traffic_peer: Vec<u64>,
}
fn main() {
    if let Err(e) = execute() {
        eprintln!("OpenRad: {e}");
        std::process::exit(1);
    }
}
fn execute() -> anyhow::Result<()> {
    let args = Args::parse();
    anyhow::ensure!(
        !openrad::tap::is_privileged(),
        "Run the desktop as your normal user. Only the TAP setup helper uses sudo."
    );
    let paths = storage::Paths::new(args.data_dir)?;
    let lock = paths.lock()?;
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
    };
    let native = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([1120., 800.])
            .with_min_inner_size([780., 540.])
            .with_app_id("org.openrad.desktop"),
        renderer: eframe::Renderer::Glow,
        ..Default::default()
    };
    eframe::run_native(
        "OpenRad",
        native,
        Box::new(move |cc| {
            Ok(Box::new(app::App::new(
                cc,
                paths,
                lock,
                args.identity,
                options,
            )))
        }),
    )
    .map_err(|e| anyhow::anyhow!("Native window could not be started: {e}"))
}

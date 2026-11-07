//! Private installer worker. It neither provisions credentials nor starts a VPN.
#![cfg_attr(windows, windows_subsystem = "windows")]
use clap::{Parser, Subcommand};
use std::path::PathBuf;

#[derive(Parser)]
#[command(version, about = "OpenRad Windows installer worker")]
struct Args {
    #[arg(long)]
    error_file: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    /// Close official Radmin VPN before any adapter setup or repeat-run launch.
    PrepareRadmin,
    Check {
        #[arg(long)]
        install_dir: PathBuf,
        #[arg(long)]
        manifest: PathBuf,
    },
    Configure {
        #[arg(long)]
        install_dir: PathBuf,
    },
    RemoveAdapter {
        #[arg(long)]
        install_dir: PathBuf,
    },
}
impl Command {
    /// Command name and install folder only; the setup log never needs more.
    fn describe(&self) -> String {
        match self {
            Command::PrepareRadmin => "prepare-radmin".into(),
            Command::Check { install_dir, .. } => {
                format!("check; install_dir={}", install_dir.display())
            }
            Command::Configure { install_dir } => {
                format!("configure; install_dir={}", install_dir.display())
            }
            Command::RemoveAdapter { install_dir } => {
                format!("remove-adapter; install_dir={}", install_dir.display())
            }
        }
    }
}
fn main() {
    // Same folder as setup.log and the desktop/CLI startup logs, so one folder
    // holds the whole install and first launch (docs/windows.md). The worker
    // only bails elsewhere, so it creates no log folder there.
    #[cfg(windows)]
    openrad::early_log::init("setup-worker");
    let args = Args::try_parse().unwrap_or_else(|error| {
        openrad::early_log::event(format_args!("Setup worker arguments rejected: {error}"));
        error.exit()
    });
    openrad::early_log::event(format_args!(
        "Setup worker command={}",
        args.command.describe()
    ));
    let result = execute(args.command);
    let code = match result {
        Ok(code) => code,
        Err(error) => {
            openrad::early_log::fatal_error(&error);
            if let Some(path) = args.error_file {
                // NSIS FileReadUTF16LE displays paths/errors without losing Unicode.
                let message = format!("{error:#}\r\n");
                let bytes: Vec<u8> = message.encode_utf16().flat_map(u16::to_le_bytes).collect();
                let _ = std::fs::write(path, bytes);
            }
            eprintln!("OpenRad setup: {error:#}");
            1
        }
    };
    openrad::early_log::event(format_args!("Setup worker exit code={code}"));
    std::process::exit(code);
}
#[cfg(windows)]
fn execute(command: Command) -> anyhow::Result<i32> {
    match command {
        Command::PrepareRadmin => openrad::windows_setup::prepare_radmin(),
        Command::Check {
            install_dir,
            manifest,
        } => openrad::windows_setup::check(&install_dir, &manifest),
        Command::Configure { install_dir } => openrad::windows_setup::configure(&install_dir),
        Command::RemoveAdapter { install_dir } => {
            openrad::windows_setup::remove_adapter(&install_dir)
        }
    }
}
#[cfg(not(windows))]
fn execute(_: Command) -> anyhow::Result<i32> {
    anyhow::bail!("This worker configures TAP-Windows6 on Windows only")
}

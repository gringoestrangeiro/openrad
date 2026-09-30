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
fn main() {
    let args = Args::parse();
    let result = execute(args.command);
    let code = match result {
        Ok(code) => code,
        Err(error) => {
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
    std::process::exit(code);
}
#[cfg(windows)]
fn execute(command: Command) -> anyhow::Result<i32> {
    match command {
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

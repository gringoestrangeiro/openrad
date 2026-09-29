//! Commands for the persistent per-user VPN service.
#[cfg(unix)]
mod daemon;

use anyhow::{ensure, Context, Result};
use clap::{Parser, Subcommand};
use openrad::{output::ReportDirectory, session::Session, tap};
use serde_json::{json, Value};
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    net::Ipv4Addr,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

#[derive(Parser)]
#[command(
    version,
    about = "OpenRad VPN: one persistent connection, simple local commands"
)]
struct Cli {
    /// Profile directory (default: $XDG_STATE_HOME/openrad or ~/.local/state/openrad).
    #[arg(long, global = true)]
    data_dir: Option<PathBuf>,
    /// Print machine-readable JSON.
    #[arg(long, global = true)]
    json: bool,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Create or import an identity once for this profile.
    #[command(alias = "provision")]
    Init {
        #[arg(
            long,
            conflicts_with = "identity",
            required_unless_present = "identity"
        )]
        node_name: Option<String>,
        /// Import an existing OpenRad identity file.
        #[arg(long, conflicts_with = "node_name")]
        identity: Option<PathBuf>,
        /// Registration server override, for controlled deployments.
        #[arg(long, default_value = openrad::DEFAULT_BOOTSTRAP_HOST)]
        host: String,
        /// Public RSA modulus override, for controlled deployments.
        #[arg(long)]
        modulus: Option<PathBuf>,
    },
    /// Start the VPN service in the background.
    Start {
        /// Connect without creating a TAP interface.
        #[arg(long)]
        no_tap: bool,
    },
    /// Show connection, interface, network, and peer summary.
    Status,
    /// Stop the VPN service and remove its TAP interface.
    Stop,
    /// List joined networks and your role in each.
    Networks,
    /// List current peers and their live connection states.
    Peers,
    /// Search public networks.
    #[command(alias = "public-networks")]
    Search {
        #[arg(default_value = "")]
        query: String,
        #[arg(long, default_value_t = 0)]
        cursor: u64,
    },
    /// Join a public or private network.
    Join {
        network: String,
        /// Private-network password stored in a UTF-8 file.
        #[arg(long)]
        password_file: Option<PathBuf>,
    },
    /// Create a private network.
    #[command(alias = "create-network")]
    Create {
        network: String,
        #[arg(long)]
        password_file: PathBuf,
    },
    /// Leave a network by exact name or ID.
    Leave { network: String },
    /// Delete a network for all members.
    #[command(alias = "delete-network")]
    Delete {
        network: String,
        #[arg(long, required = true)]
        yes: bool,
    },
    /// Remove a member from a network you administer.
    Kick { network: String, member: u64 },
    /// Grant network administration to a member.
    GrantAdmin { network: String, member: u64 },
    /// Revoke network administration from a member.
    RevokeAdmin { network: String, member: u64 },
    /// Retry failed peer channels now.
    RetryPeers,
    /// Retry TAP creation after making sudo authorization available.
    RetryInterface,
    #[command(name = "__daemon", hide = true)]
    __Daemon {
        #[arg(long)]
        no_tap: bool,
    },
    #[command(hide = true)]
    TapHelper {
        #[arg(long)]
        vip: Ipv4Addr,
        #[arg(long)]
        owner: u32,
        #[arg(long)]
        peer: Vec<Ipv4Addr>,
        #[arg(long)]
        lan: bool,
    },
}

fn password_file(path: &Path) -> Result<Zeroizing<String>> {
    let mut contents = Zeroizing::new(String::new());
    fs::File::open(path)?
        .take(4097)
        .read_to_string(&mut contents)?;
    ensure!(contents.len() <= 4096, "network password file too large");
    if contents.ends_with('\n') {
        contents.pop();
        if contents.ends_with('\r') {
            contents.pop();
        }
    }
    openrad::network::NetworkPassword::new(contents.to_string())?;
    Ok(contents)
}

#[cfg(unix)]
fn initialize(
    dir: &daemon::DataDir,
    name: Option<String>,
    source: Option<PathBuf>,
    host: String,
    modulus_path: Option<PathBuf>,
) -> Result<daemon::Reply> {
    ensure!(
        !dir.path.join("profile").exists(),
        "Profile already exists at {}. Use `openrad start` or choose another --data-dir",
        dir.path.display()
    );
    let modulus = if let Some(path) = modulus_path {
        let metadata = fs::metadata(&path)?;
        ensure!(
            (1..=4096).contains(&metadata.len()),
            "public modulus size is invalid"
        );
        Some(fs::read(path)?)
    } else {
        None
    };
    let reports = ReportDirectory::new(&dir.path.join("profile"))?;
    if let Some(modulus) = &modulus {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(dir.modulus())?;
        file.write_all(modulus)?;
    }
    let identity = match source {
        Some(path) => {
            let id = openrad::protocol::Identity::load(&path)?;
            id.save(&reports)?;
            id
        }
        None => Session::provision(
            modulus.as_deref().unwrap_or(openrad::SERVER_MODULUS),
            name.as_deref().context("--node-name is required")?,
            &host,
            &reports,
        )?,
    };
    Ok(daemon::Reply {
        ok: true,
        message: "Identity saved. Run `openrad start` to connect.".into(),
        data: json!({"rid":identity.rid,"vip":identity.vip,"identity":dir.identity()}),
    })
}

#[cfg(unix)]
fn run_command(cli: Cli) -> Result<daemon::Reply> {
    use daemon::{MemberActionWire, Request};
    let dir = daemon::DataDir::open(cli.data_dir)?;
    Ok(match cli.command {
        Command::Init {
            node_name,
            identity,
            host,
            modulus,
        } => initialize(&dir, node_name, identity, host, modulus)?,
        Command::Start { no_tap } => {
            let started = daemon::spawn(&dir, no_tap)?;
            let status = daemon::request(&dir, &Request::Status)?;
            let mut data = status.data;
            data["started"] = Value::Bool(started);
            data["start_command"] = Value::Bool(true);
            daemon::Reply {
                ok: true,
                message: if started {
                    "Service started. It stays open after this terminal closes."
                } else {
                    "Service is already running."
                }
                .into(),
                data,
            }
        }
        Command::Status => match daemon::request(&dir, &Request::Status) {
            Ok(reply) => reply,
            Err(_) if !dir.socket().exists() => daemon::Reply {
                ok: true,
                message: "Service stopped".into(),
                data: json!({"phase":"stopped"}),
            },
            Err(error) => return Err(error),
        },
        Command::Stop => match daemon::request(&dir, &Request::Stop) {
            Ok(reply) => {
                let until = Instant::now() + Duration::from_secs(10);
                while dir.socket().exists() && Instant::now() < until {
                    std::thread::sleep(Duration::from_millis(50));
                }
                reply
            }
            Err(_) if !dir.socket().exists() => daemon::Reply {
                ok: true,
                message: "Service already stopped".into(),
                data: Value::Null,
            },
            Err(error) => return Err(error),
        },
        Command::Networks => daemon::request(&dir, &Request::Networks)?,
        Command::Peers => daemon::request(&dir, &Request::Peers)?,
        Command::Search { query, cursor } => {
            daemon::request(&dir, &Request::Search { query, cursor })?
        }
        Command::Join {
            network,
            password_file: path,
        } => {
            let password = path.as_deref().map(password_file).transpose()?;
            daemon::request(
                &dir,
                &Request::Join {
                    name: network,
                    password: password.as_ref().map(|p| p.to_string()),
                },
            )?
        }
        Command::Create {
            network,
            password_file: path,
        } => {
            let password = password_file(&path)?;
            daemon::request(
                &dir,
                &Request::Create {
                    name: network,
                    password: password.to_string(),
                },
            )?
        }
        Command::Leave { network } => daemon::request(&dir, &Request::Leave { network })?,
        Command::Delete { network, yes } => {
            ensure!(yes, "network deletion requires --yes");
            daemon::request(&dir, &Request::Delete { network })?
        }
        Command::Kick { network, member } => daemon::request(
            &dir,
            &Request::Member {
                network,
                member,
                action: MemberActionWire::Kick,
            },
        )?,
        Command::GrantAdmin { network, member } => daemon::request(
            &dir,
            &Request::Member {
                network,
                member,
                action: MemberActionWire::GrantAdmin,
            },
        )?,
        Command::RevokeAdmin { network, member } => daemon::request(
            &dir,
            &Request::Member {
                network,
                member,
                action: MemberActionWire::RevokeAdmin,
            },
        )?,
        Command::RetryPeers => daemon::request(&dir, &Request::RetryPeers)?,
        Command::RetryInterface => daemon::request(&dir, &Request::RetryInterface)?,
        Command::__Daemon { no_tap } => {
            daemon::run(dir, no_tap)?;
            daemon::Reply {
                ok: true,
                message: "Service stopped".into(),
                data: Value::Null,
            }
        }
        Command::TapHelper { .. } => unreachable!(),
    })
}

#[cfg(unix)]
fn print_reply(reply: &daemon::Reply, machine: bool) -> Result<()> {
    if machine {
        println!("{}", serde_json::to_string(reply)?);
    } else if !reply.ok {
        eprintln!("openrad: {}", reply.message);
    } else if let Some(phase) = reply.data.get("phase").and_then(Value::as_str) {
        if reply.data["start_command"] == true {
            println!("{}", reply.message);
        }
        println!("Service: {phase}");
        if let Some(rid) = reply.data.get("rid") {
            println!(
                "Device: {} (RID {rid})",
                reply.data["node_name"].as_str().unwrap_or("unknown")
            );
        }
        if let Some(snapshot) = reply.data.get("snapshot").filter(|v| !v.is_null()) {
            println!(
                "VPN address: {}",
                snapshot["vip"].as_str().unwrap_or("unknown")
            );
            println!(
                "Interface: {}",
                if reply.data["interface_disabled"] == true {
                    "disabled"
                } else if snapshot["interface_ready"] == true {
                    "ready"
                } else {
                    "unavailable"
                }
            );
            if let Some(error) = snapshot["interface_error"].as_str() {
                println!("Interface error: {error}");
            }
            let connected = snapshot["peers"]
                .as_object()
                .map(|peers| {
                    peers
                        .values()
                        .filter(|p| p["status"] == "Connected")
                        .count()
                })
                .unwrap_or(0);
            println!(
                "Networks: {} · Peers connected: {connected}",
                snapshot["networks"].as_array().map_or(0, Vec::len)
            );
        }
        if let Some(error) = reply.data["error"].as_str() {
            println!("Last error: {error}");
        }
    } else if let Some(networks) = reply.data.as_array() {
        if networks.is_empty() {
            println!("No joined networks. Use `openrad join NAME` or `openrad create NAME --password-file FILE`.");
        }
        for network in networks {
            let role = match network["role"].as_u64() {
                Some(0) => "pending approval",
                Some(1) => "member",
                Some(2) => "admin",
                _ => "unknown",
            };
            println!(
                "{}  [{}]  {role}",
                network["name"].as_str().unwrap_or("?"),
                network["id"].as_str().unwrap_or("?")
            );
        }
    } else if reply.data.get("id").is_some() {
        println!("{}", reply.message);
    } else if let Some(peers) = reply.data.as_object() {
        if let Some(networks) = peers.get("networks").and_then(Value::as_array) {
            if networks.is_empty() {
                println!("No public networks found.");
            }
            for network in networks {
                println!(
                    "{}  ({} members reported)",
                    network["name"].as_str().unwrap_or("?"),
                    network["reported_count"].as_u64().unwrap_or(0)
                );
            }
            if peers["cursor"] != 0 {
                println!("Next page: --cursor {}", peers["cursor"]);
            }
        } else {
            if peers.is_empty() {
                println!("No peers in joined networks yet.");
            }
            for (rid, peer) in peers {
                println!(
                    "{rid}  {}  {}  {}  {}",
                    peer["peer"]["name"].as_str().unwrap_or("?"),
                    peer["peer"]["vip"].as_str().unwrap_or("?"),
                    peer["status"].as_str().unwrap_or("?"),
                    peer["detail"].as_str().unwrap_or("")
                );
            }
        }
    } else {
        println!("{}", reply.message);
        if let Some(path) = reply.data.get("identity") {
            println!("Identity: {path}");
        }
    }
    Ok(())
}

fn execute() -> Result<()> {
    let cli = Cli::parse();
    if let Command::TapHelper {
        vip,
        owner,
        ref peer,
        lan,
    } = cli.command
    {
        return tap::helper_with_lan(vip, owner, peer, lan);
    }
    ensure!(
        !tap::is_privileged(),
        "run OpenRad as your normal user; only the TAP helper uses sudo"
    );
    #[cfg(unix)]
    {
        let machine = cli.json;
        let reply = run_command(cli)?;
        print_reply(&reply, machine)?;
        if !reply.ok {
            std::process::exit(1);
        }
        Ok(())
    }
    #[cfg(not(unix))]
    {
        anyhow::bail!("persistent CLI service currently requires Unix")
    }
}

fn main() {
    if let Err(error) = execute() {
        eprintln!("openrad: {error:#}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deletion_requires_explicit_confirmation() {
        assert!(Cli::try_parse_from(["openrad", "delete", "Example"]).is_err());
        assert!(matches!(
            Cli::try_parse_from(["openrad", "delete", "Example", "--yes"])
                .unwrap()
                .command,
            Command::Delete { yes: true, .. }
        ));
    }
    #[test]
    fn private_passwords_do_not_enter_arguments() {
        assert!(
            Cli::try_parse_from(["openrad", "join", "Example", "--password", "secret"]).is_err()
        );
        assert!(matches!(
            Cli::try_parse_from([
                "openrad",
                "join",
                "Example",
                "--password-file",
                "private-file"
            ])
            .unwrap()
            .command,
            Command::Join {
                password_file: Some(_),
                ..
            }
        ));
    }
}

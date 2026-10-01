//! Commands for the persistent per-user VPN service.
#[cfg(any(unix, windows))]
use openrad::daemon;

use anyhow::{ensure, Context, Result};
use clap::{Parser, Subcommand};
use openrad::{
    early_log::{self, Stage},
    i18n::{self, Language, LanguagePreference},
    output::ReportDirectory,
    session::Session,
    tap,
};
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
    /// Display language: system, en, pt, ru or vi.
    #[arg(long, global = true, default_value = "system")]
    language: LanguagePreference,
    /// Profile directory (Windows: %LOCALAPPDATA%/openrad; Unix: $XDG_STATE_HOME/openrad).
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
    /// Retry interface setup after fixing driver or privilege requirements.
    RetryInterface,
    /// Test the RTT of a connected peer (RID from `openrad peers`).
    Ping { peer: u64 },
    /// Change the node name without replacing the identity.
    Rename { node_name: String },
    /// Use relays only, without direct UDP or TCP attempts.
    ForceRelay {
        #[arg(action = clap::ArgAction::Set)]
        enabled: bool,
    },
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

impl Command {
    fn kind(&self) -> &'static str {
        match self {
            Self::Init { .. } => "init",
            Self::Start { .. } => "start",
            Self::Status => "status",
            Self::Stop => "stop",
            Self::Networks => "networks",
            Self::Peers => "peers",
            Self::Search { .. } => "search",
            Self::Join { .. } => "join",
            Self::Create { .. } => "create",
            Self::Leave { .. } => "leave",
            Self::Delete { .. } => "delete",
            Self::Kick { .. } => "kick",
            Self::GrantAdmin { .. } => "grant_admin",
            Self::RevokeAdmin { .. } => "revoke_admin",
            Self::RetryPeers => "retry_peers",
            Self::RetryInterface => "retry_interface",
            Self::Ping { .. } => "ping",
            Self::Rename { .. } => "rename",
            Self::ForceRelay { .. } => "force_relay",
            Self::__Daemon { .. } => "daemon",
            Self::TapHelper { .. } => "tap_helper",
        }
    }
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

#[cfg(any(unix, windows))]
fn initialize(
    dir: &daemon::DataDir,
    name: Option<String>,
    source: Option<PathBuf>,
    host: String,
    modulus_path: Option<PathBuf>,
) -> Result<daemon::Reply> {
    let _profile_lock = dir.profile_lock()?;
    ensure!(
        !dir.path.join("profile").exists()
            && daemon::request(dir, &daemon::Request::Status).is_err()
            && !dir
                .entry()
                .and_then(|entry| entry.get_secret().map_err(anyhow::Error::from))
                .is_ok(),
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
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        options.mode(0o600);
        let mut file = options.open(dir.modulus())?;
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

#[cfg(any(unix, windows))]
fn run_command(cli: Cli) -> Result<daemon::Reply> {
    use daemon::{MemberActionWire, Request};
    early_log::checkpoint(Stage::ProfileOpen);
    let dir = daemon::DataDir::open(cli.data_dir)?;
    early_log::checkpoint(Stage::ProfileReady);
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
            if status.data["phase"] == "error" {
                let reply = daemon::request(&dir, &Request::Reconnect)?;
                ensure!(reply.ok, "{}", reply.message);
            }
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
            Err(_) if !dir.endpoint_exists() => daemon::Reply {
                ok: true,
                message: "Service stopped".into(),
                data: json!({"phase":"stopped"}),
            },
            Err(error) => return Err(error),
        },
        Command::Stop => match daemon::request(&dir, &Request::Stop) {
            Ok(reply) => {
                let until = Instant::now() + Duration::from_secs(10);
                while dir.endpoint_exists() && Instant::now() < until {
                    std::thread::sleep(Duration::from_millis(50));
                }
                reply
            }
            Err(_) if !dir.endpoint_exists() => daemon::Reply {
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
        Command::Ping { peer } => daemon::request(&dir, &Request::Ping { peer })?,
        Command::Rename { node_name } => {
            openrad::protocol::validate_node_name(&node_name)?;
            if dir.endpoint_exists() {
                daemon::request(&dir, &Request::Rename { node_name })?
            } else {
                let mut identity = dir.load_identity()?;
                identity.node_name = node_name;
                dir.save_identity(&identity)?;
                daemon::Reply {
                    ok: true,
                    message: "Device name saved".into(),
                    data: Value::Null,
                }
            }
        }
        Command::ForceRelay { enabled } => {
            let mut preferences = if let Ok(reply) = daemon::request(&dir, &Request::Status) {
                serde_json::from_value(reply.data["preferences"].clone())?
            } else {
                dir.preferences()?
            };
            preferences.force_relay = enabled;
            if dir.endpoint_exists() {
                daemon::request(&dir, &Request::Configure { preferences })?
            } else {
                dir.save_preferences(&preferences)?;
                daemon::Reply {
                    ok: true,
                    message: "Settings saved".into(),
                    data: Value::Null,
                }
            }
        }
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

#[cfg(any(unix, windows))]
fn print_reply(reply: &daemon::Reply, machine: bool, language: Language) -> Result<()> {
    if machine {
        println!("{}", serde_json::to_string(reply)?);
    } else if !reply.ok {
        eprintln!("openrad: {}", language.message(&reply.message));
    } else {
        println!("{}", human_reply(reply, language));
    }
    Ok(())
}

#[cfg(any(unix, windows))]
fn human_reply(reply: &daemon::Reply, language: Language) -> String {
    if let Some(rtt_ms) = reply.data["rtt_ms"].as_f64() {
        return language.format("RTT: {ms} ms", &[("ms", &format!("{rtt_ms:.1}"))]);
    }
    let mut lines = Vec::new();
    if let Some(phase) = reply.data.get("phase").and_then(Value::as_str) {
        if reply.data["start_command"] == true {
            lines.push(language.message(&reply.message));
        }
        lines.push(language.format("Service: {phase}", &[("phase", language.text(phase))]));
        if let Some(rid) = reply.data.get("rid") {
            lines.push(
                language.format(
                    "Device: {0} (RID {rid})",
                    &[
                        (
                            "0",
                            reply.data["node_name"]
                                .as_str()
                                .unwrap_or(language.text("unknown")),
                        ),
                        ("rid", &rid.to_string()),
                    ],
                ),
            );
        }
        if let Some(snapshot) = reply.data.get("snapshot").filter(|v| !v.is_null()) {
            lines.push(language.format(
                "VPN address: {0}",
                &[(
                    "0",
                    snapshot["vip"].as_str().unwrap_or(language.text("unknown")),
                )],
            ));
            let interface = if reply.data["interface_disabled"] == true {
                "disabled"
            } else if snapshot["interface_ready"] == true {
                "ready"
            } else {
                "unavailable"
            };
            lines.push(language.format("Interface: {0}", &[("0", language.text(interface))]));
            if let Some(error) = snapshot["interface_error"].as_str() {
                lines.push(language.format(
                    "Interface error: {error}",
                    &[("error", &language.message(error))],
                ));
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
            lines.push(
                language.format(
                    "Networks: {0} · Peers connected: {connected}",
                    &[
                        (
                            "0",
                            &snapshot["networks"]
                                .as_array()
                                .map_or(0, Vec::len)
                                .to_string(),
                        ),
                        ("connected", &connected.to_string()),
                    ],
                ),
            );
        }
        if let Some(error) = reply.data["error"].as_str() {
            lines.push(language.format(
                "Last error: {error}",
                &[("error", &language.message(error))],
            ));
        }
    } else if let Some(networks) = reply.data.as_array() {
        if networks.is_empty() {
            lines.push(language.text("No joined networks. Use `openrad join NAME` or `openrad create NAME --password-file FILE`.").to_owned());
        }
        for network in networks {
            let role = match network["role"].as_u64() {
                Some(0) => "pending approval",
                Some(1) => "member",
                Some(2) => "admin",
                _ => "unknown",
            };
            lines.push(format!(
                "{}  [{}]  {}",
                network["name"].as_str().unwrap_or("?"),
                network["id"].as_str().unwrap_or("?"),
                language.text(role),
            ));
        }
    } else if reply.data.get("id").is_some() {
        lines.push(language.message(&reply.message));
    } else if let Some(data) = reply.data.as_object() {
        if let Some(networks) = data.get("networks").and_then(Value::as_array) {
            if networks.is_empty() {
                lines.push(language.text("No public networks found.").to_owned());
            }
            for network in networks {
                lines.push(language.format(
                    "{0}  ({1} members reported)",
                    &[
                        ("0", network["name"].as_str().unwrap_or("?")),
                        (
                            "1",
                            &network["reported_count"].as_u64().unwrap_or(0).to_string(),
                        ),
                    ],
                ));
            }
            if data["cursor"] != 0 {
                lines.push(language.format(
                    "Next page: --cursor {0}",
                    &[("0", &data["cursor"].to_string())],
                ));
            }
        } else if data.contains_key("identity") {
            lines.push(language.message(&reply.message));
            lines.push(language.format(
                "Identity: {path}",
                &[("path", &data["identity"].to_string())],
            ));
        } else {
            if data.is_empty() {
                lines.push(language.text("No peers in joined networks yet.").to_owned());
            }
            for (rid, peer) in data {
                lines.push(format!(
                    "{rid}  {}  {}  {}  {}",
                    peer["peer"]["name"].as_str().unwrap_or("?"),
                    peer["peer"]["vip"].as_str().unwrap_or("?"),
                    language.text(peer["status"].as_str().unwrap_or("?")),
                    language.message(peer["detail"].as_str().unwrap_or("")),
                ));
            }
        }
    } else {
        lines.push(language.message(&reply.message));
    }
    lines.join("\n")
}

fn execute() -> Result<()> {
    let (cli, language) = i18n::parse_localized::<Cli>(LanguagePreference::System);
    early_log::command(cli.command.kind());
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
    #[cfg(any(unix, windows))]
    {
        let machine = cli.json;
        let reply = run_command(cli)?;
        early_log::reply(reply.ok, reply.data["error"].as_str());
        print_reply(&reply, machine, language)?;
        if !reply.ok {
            std::process::exit(1);
        }
        Ok(())
    }
    #[cfg(not(any(unix, windows)))]
    {
        anyhow::bail!("persistent CLI service currently requires Unix")
    }
}

fn main() {
    let args: Vec<_> = std::env::args_os().collect();
    early_log::init(if args.iter().any(|arg| arg == "__daemon") {
        "cli-daemon"
    } else {
        "cli"
    });
    #[cfg(unix)]
    openrad::resource_limits::configure_open_file_limit();
    let language = i18n::language_for_args(&args, LanguagePreference::System);
    if let Err(error) = execute() {
        early_log::fatal_error(&error);
        eprintln!("openrad: {}", language.message(&format!("{error:#}")));
        std::process::exit(1);
    }
    early_log::checkpoint(Stage::ProcessReturned);
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

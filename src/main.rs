use anyhow::{ensure, Result};
use clap::{Args, Parser, Subcommand};
use openrad::{
    client::{self, RunOptions},
    network::{MemberAction, NetworkPassword, NetworkRequest},
    output::ReportDirectory,
    protocol::Identity,
    session::Session,
    tap,
};
use serde_json::json;
use std::{
    collections::BTreeSet,
    net::Ipv4Addr,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};

#[derive(Parser)]
#[command(
    version,
    about = "Native VPN client with private networks, administration and Ethernet TAP"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}
#[derive(Args)]
struct SessionArgs {
    #[arg(long)]
    identity: PathBuf,
    /// Optional public RSA modulus override; defaults to the bundled service key.
    #[arg(long)]
    modulus: Option<PathBuf>,
    /// New private report directory. Existing files are never overwritten.
    #[arg(long)]
    output: PathBuf,
    #[arg(long,default_value_t=90,value_parser=clap::value_parser!(u64).range(10..=300))]
    duration: u64,
}
#[derive(Subcommand)]
enum Command {
    /// Register a new device identity.
    Provision {
        /// Optional public RSA modulus override; defaults to the bundled service key.
        #[arg(long)]
        modulus: Option<PathBuf>,
        #[arg(long)]
        node_name: String,
        #[arg(long, default_value = openrad::DEFAULT_BOOTSTRAP_HOST)]
        host: String,
        #[arg(long)]
        output: PathBuf,
    },
    /// Authenticate and print the assigned VIP and saved memberships.
    Connect {
        #[command(flatten)]
        session: SessionArgs,
    },
    /// List a page of public networks with the normal server request.
    PublicNetworks {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long, default_value = "")]
        query: String,
    },
    /// Join public or password-protected networks, or verify existing membership.
    Join {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long, required = true)]
        network: Vec<String>,
        /// UTF-8 password file; use only with a single private network. One trailing newline is removed.
        #[arg(long)]
        password_file: Option<PathBuf>,
    },
    /// Create a password-protected private network.
    CreateNetwork {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
        #[arg(long)]
        password_file: PathBuf,
    },
    /// Leave a network by ID or exact name.
    Leave {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
    },
    /// Permanently delete a network you administer, removing all members.
    DeleteNetwork {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
        /// Explicitly confirm deletion for every member.
        #[arg(long, required = true)]
        confirm: bool,
    },
    /// Remove a member from a network you administer.
    Kick {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
        #[arg(long)]
        member: u64,
    },
    /// Give another member network administration permissions.
    GrantAdmin {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
        #[arg(long)]
        member: u64,
    },
    /// Remove another member's administration permissions.
    RevokeAdmin {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long)]
        network: String,
        #[arg(long)]
        member: u64,
    },
    /// List authenticated memberships and peers without peer connections.
    Peers {
        #[command(flatten)]
        session: SessionArgs,
    },
    /// Connect every eligible member once; Ctrl-C disconnects and removes TAP.
    Run {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long, required = true)]
        network: Vec<String>,
        /// Optional diagnostic selection; omit to connect all eligible members.
        #[arg(long)]
        peer: Vec<u64>,
        /// TAP traffic is restricted to these controlled/consenting peer RIDs.
        #[arg(long)]
        traffic_peer: Vec<u64>,
        #[arg(long)]
        tap: bool,
        /// Wait for authenticated incoming offers without initiating peers.
        #[arg(long)]
        passive: bool,
        /// Restrict incoming transport attempts (all, tcp, udp, or relay).
        #[arg(long, default_value = "all")]
        incoming_transport: openrad::incoming::Policy,
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
fn load_modulus(path: Option<&std::path::Path>) -> Result<Vec<u8>> {
    Ok(match path {
        Some(path) => std::fs::read(path)?,
        None => openrad::SERVER_MODULUS.to_vec(),
    })
}
fn attach(args: &SessionArgs) -> Result<(client::AttachedClient, ReportDirectory)> {
    let id = Identity::load(&args.identity)?;
    let modulus = load_modulus(args.modulus.as_deref())?;
    let reports = ReportDirectory::new(&args.output)?;
    let (session, membership, vip) = Session::attach(
        &id,
        &modulus,
        &reports,
        Duration::from_secs(args.duration + 30),
    )?;
    Ok((
        client::AttachedClient {
            session,
            membership,
            vip,
            identity: id,
            modulus,
        },
        reports,
    ))
}
fn password_file(path: &std::path::Path) -> Result<NetworkPassword> {
    use std::io::Read;
    use zeroize::Zeroizing;
    let mut contents = Zeroizing::new(String::new());
    std::fs::File::open(path)?
        .take(4097)
        .read_to_string(&mut contents)?;
    ensure!(contents.len() <= 4096, "network password file too large");
    if contents.ends_with('\n') {
        contents.pop();
        if contents.ends_with('\r') {
            contents.pop();
        }
    }
    NetworkPassword::new(std::mem::take(&mut *contents))
}
fn resolve_network(membership: &openrad::protocol::Membership, name: &str) -> Result<String> {
    let matches: Vec<_> = membership
        .networks
        .values()
        .filter(|n| n.network_id == name || n.name == name)
        .collect();
    ensure!(
        matches.len() == 1,
        "network not found or ambiguous; use the network ID from peers"
    );
    Ok(matches[0].network_id.clone())
}
fn manage(
    args: &SessionArgs,
    request: impl FnOnce(&client::AttachedClient) -> Result<NetworkRequest>,
) -> Result<()> {
    let (mut client, reports) = attach(args)?;
    let request = request(&client)?;
    let result = client
        .session
        .network_operation(request, 2, 1, &mut client.membership)?;
    let output = json!({"result":if result.error {"refused"} else {"completed"},"message":result.message,"membership":client.membership});
    reports.json("result.json", &output)?;
    println!("{output}");
    ensure!(!result.error, "{}", result.message);
    Ok(())
}
fn execute() -> Result<()> {
    let cli = Cli::parse();
    if let Command::TapHelper {
        vip,
        owner,
        peer,
        lan,
    } = cli.command
    {
        return tap::helper_with_lan(vip, owner, &peer, lan);
    }
    ensure!(
        !tap::is_privileged(),
        "run as your normal user; only the TAP helper uses sudo"
    );
    let stop = Arc::new(AtomicBool::new(false));
    let signal_stop = stop.clone();
    ctrlc::set_handler(move || signal_stop.store(true, Ordering::Relaxed))?;
    match cli.command {
        Command::Provision {
            modulus,
            node_name,
            host,
            output,
        } => {
            let reports = ReportDirectory::new(&output)?;
            let id = Session::provision(
                &load_modulus(modulus.as_deref())?,
                &node_name,
                &host,
                &reports,
            )?;
            println!(
                "{}",
                json!({"result":"provisioned","rid":id.rid,"vip":id.vip,"server":id.server_address})
            );
        }
        Command::Connect { session: args } | Command::Peers { session: args } => {
            let (client, reports) = attach(&args)?;
            let result =
                json!({"result":"authenticated","vip":client.vip,"membership":client.membership});
            reports.json("result.json", &result)?;
            println!("{}", serde_json::to_string(&result)?);
        }
        Command::PublicNetworks {
            session: args,
            query,
        } => {
            let (mut client, reports) = attach(&args)?;
            let (networks, cursor) = client.session.list_public(&query)?;
            let result =
                json!({"result":"public_networks_listed","networks":networks,"cursor":cursor});
            reports.json("result.json", &result)?;
            println!("{result}");
        }
        Command::Join {
            session: args,
            network,
            password_file: password_path,
        } => {
            ensure!(
                password_path.is_none() || network.len() == 1,
                "--password-file requires exactly one --network"
            );
            let password = password_path.as_deref().map(password_file).transpose()?;
            let (client, reports) = attach(&args)?;
            let client::AttachedClient {
                mut session,
                mut membership,
                ..
            } = client;
            for (i, name) in network.iter().enumerate() {
                if membership.networks.values().any(|n| &n.name == name) {
                    println!("{}", json!({"event":"membership_verified","name":name}));
                } else {
                    let result = session.network_operation(
                        NetworkRequest::Join {
                            name: name.clone(),
                            password: password.clone(),
                        },
                        i as u64 + 2,
                        i as u32 + 1,
                        &mut membership,
                    )?;
                    ensure!(!result.error, "{}", result.message);
                    println!(
                        "{}",
                        json!({"event":if result.pending_approval {"approval_pending"} else {"join_approved"},"name":name,"message":result.message})
                    );
                }
            }
            reports.json("result.json", &membership)?;
        }
        Command::CreateNetwork {
            session: args,
            network,
            password_file: path,
        } => {
            let password = password_file(&path)?;
            manage(&args, |_| {
                Ok(NetworkRequest::Create {
                    name: network,
                    password,
                })
            })?;
        }
        Command::Leave {
            session: args,
            network,
        } => {
            manage(&args, |c| {
                Ok(NetworkRequest::Leave {
                    network: resolve_network(&c.membership, &network)?,
                })
            })?;
        }
        Command::DeleteNetwork {
            session: args,
            network,
            confirm,
        } => {
            ensure!(confirm, "network deletion requires --confirm");
            manage(&args, |c| {
                Ok(NetworkRequest::Delete {
                    network: resolve_network(&c.membership, &network)?,
                })
            })?;
        }
        Command::Kick {
            session: args,
            network,
            member,
        } => {
            manage(&args, |c| {
                Ok(NetworkRequest::Member {
                    network: resolve_network(&c.membership, &network)?,
                    member,
                    action: MemberAction::Kick,
                })
            })?;
        }
        Command::GrantAdmin {
            session: args,
            network,
            member,
        } => {
            manage(&args, |c| {
                Ok(NetworkRequest::Member {
                    network: resolve_network(&c.membership, &network)?,
                    member,
                    action: MemberAction::GrantAdmin,
                })
            })?;
        }
        Command::RevokeAdmin {
            session: args,
            network,
            member,
        } => {
            manage(&args, |c| {
                Ok(NetworkRequest::Member {
                    network: resolve_network(&c.membership, &network)?,
                    member,
                    action: MemberAction::RevokeAdmin,
                })
            })?;
        }
        Command::Run {
            session: args,
            network,
            peer,
            traffic_peer,
            tap,
            passive,
            incoming_transport,
        } => {
            ensure!(
                !tap || !traffic_peer.is_empty(),
                "--tap requires explicit --traffic-peer consent"
            );
            let (client, reports) = attach(&args)?;
            let options = RunOptions {
                networks: network,
                only_peers: BTreeSet::from_iter(peer),
                traffic_peers: BTreeSet::from_iter(traffic_peer),
                tap,
                passive,
                incoming_transport,
                duration: Duration::from_secs(args.duration),
            };
            let events = reports.events("events.jsonl")?;
            client::run(client, options, &reports, stop, &|event| {
                println!("{event}");
                let _ = events.event(event);
            })?;
        }
        Command::TapHelper { .. } => unreachable!(),
    }
    Ok(())
}
fn main() {
    if let Err(error) = execute() {
        eprintln!("openrad: {error:#}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod cli_tests {
    use super::*;
    #[test]
    fn deletion_requires_explicit_confirmation() {
        let args = [
            "openrad",
            "delete-network",
            "--identity",
            "synthetic.json",
            "--output",
            "unused",
            "--network",
            "Example",
        ];
        assert!(Cli::try_parse_from(args).is_err());
        let cli = Cli::try_parse_from(args.into_iter().chain(["--confirm"])).unwrap();
        assert!(matches!(
            cli.command,
            Command::DeleteNetwork { confirm: true, .. }
        ));
    }
    #[test]
    fn private_join_accepts_password_file_but_not_plaintext_password_arguments() {
        let args = [
            "openrad",
            "join",
            "--identity",
            "synthetic.json",
            "--output",
            "unused",
            "--network",
            "Example",
        ];
        assert!(Cli::try_parse_from(args.into_iter().chain(["--password", "secret"])).is_err());
        let cli = Cli::try_parse_from(args.into_iter().chain(["--password-file", "private-file"]))
            .unwrap();
        assert!(matches!(
            cli.command,
            Command::Join {
                password_file: Some(_),
                ..
            }
        ));
    }
}

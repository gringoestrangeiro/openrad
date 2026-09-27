use anyhow::{ensure, Result};
use clap::{Args, Parser, Subcommand};
use openrad::{
    client::{self, RunOptions},
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
    about = "Native headless public-network client with an Ethernet TAP data path"
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
    /// Join public networks, or verify already-established membership.
    Join {
        #[command(flatten)]
        session: SessionArgs,
        #[arg(long, required = true)]
        network: Vec<String>,
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
        } => {
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
                    let joined =
                        session.join_public(name, i as u64 + 2, i as u32 + 1, &mut membership)?;
                    println!("{}", json!({"event":"join_approved","network":joined}));
                }
            }
            reports.json("result.json", &membership)?;
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

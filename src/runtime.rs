//! Long-lived application engine. UI adapters send commands and consume snapshots;
//! control I/O, peer handshakes and Ethernet forwarding never run on the UI thread.
use crate::{
    incoming::{Hub, Policy, Setup},
    output::ReportDirectory,
    peer::{PeerChannel, TransportPath},
    protocol::*,
    session::Session,
    tap::Tap,
    tunnel,
};
use anyhow::{bail, ensure, Result};
use serde::Serialize;
use std::{
    collections::{BTreeMap, BTreeSet},
    net::Ipv4Addr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender},
        Arc,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

#[derive(Clone, Debug)]
pub enum Command {
    Search { query: String, cursor: u64 },
    Join(String),
    Leave(String),
    RetryPeers,
    RetryInterface,
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub enum PeerState {
    #[default]
    Offline,
    Online,
    Connecting,
    Connected,
    Refused,
    Failed,
    Unavailable,
}
impl PeerState {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Offline => "Offline",
            Self::Online => "Online · queued",
            Self::Connecting => "Connecting",
            Self::Connected => "Connected",
            Self::Refused => "Refused",
            Self::Failed => "Failed",
            Self::Unavailable => "Unavailable",
        }
    }
}
#[derive(Clone, Serialize)]
pub struct PeerView {
    pub peer: Peer,
    pub status: PeerState,
    pub detail: String,
    pub transport: Option<TransportPath>,
}
#[derive(Clone, Default, Serialize)]
pub struct Traffic {
    pub sent_bytes: u64,
    pub received_bytes: u64,
    pub sent_frames: u64,
    pub received_frames: u64,
    pub dropped: u64,
}
#[derive(Clone, Default, Serialize)]
pub struct Snapshot {
    pub vip: Option<Ipv4Addr>,
    pub networks: Vec<Network>,
    pub peers: BTreeMap<u64, PeerView>,
    pub interface_ready: bool,
    pub interface_error: Option<String>,
    pub traffic: Traffic,
    pub latency_ms: u32,
    pub elapsed_secs: u64,
    pub restricted_traffic: bool,
}
#[derive(Clone)]
pub enum Update {
    State(Snapshot),
    Catalog {
        query: String,
        networks: Vec<PublicNetwork>,
        cursor: u64,
        append: bool,
    },
    Operation {
        message: String,
        error: bool,
    },
}
#[derive(Clone, Default)]
pub struct Options {
    /// None permits normal application traffic to all authenticated members.
    /// Some is useful for controlled interoperability testing only.
    pub traffic_peers: Option<BTreeSet<u64>>,
    /// Setup-only executable. Desktop passes the sibling CLI, never the GUI.
    pub helper: Option<std::path::PathBuf>,
}
impl Options {
    fn allows(&self, rid: u64) -> bool {
        self.traffic_peers.as_ref().is_none_or(|r| r.contains(&rid))
    }
}

enum Message {
    Membership(Membership),
    Update(Update),
    ControlFailed(String),
    Connected(u64, [u8; 6], TransportPath, bool),
    PeerRecord(Vec<u8>),
    Frame(u64, Vec<u8>),
    Sent(usize),
    Dropped,
    Closed(u64, Option<String>),
}
struct Pending {
    command: Command,
    id: u64,
    sequence: u32,
    until: Instant,
    joined: Option<Network>,
}
fn control_loop(
    mut session: Session,
    mut membership: Membership,
    commands: Receiver<Command>,
    wire: Receiver<Vec<u8>>,
    events: SyncSender<Message>,
    stop: Arc<AtomicBool>,
) {
    let result = (|| -> Result<()> {
        let mut next_id = 100;
        let mut sequence = 0;
        let mut pending: Option<Pending> = None;
        let mut heartbeat = Instant::now();
        loop {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            for bytes in wire.try_iter().take(64) {
                session.send(&bytes)?;
            }
            if pending.is_none() {
                if let Ok(command) = commands.try_recv() {
                    next_id += 1;
                    sequence += 1;
                    let bytes = match &command {
                        Command::Search { query, cursor } => public_list(query, next_id, *cursor)?,
                        Command::Join(name) => join(name, next_id, sequence)?,
                        Command::Leave(id) => leave(id, next_id, sequence)?,
                        _ => continue,
                    };
                    session.send(&bytes)?;
                    pending = Some(Pending {
                        command,
                        id: next_id,
                        sequence,
                        until: Instant::now() + Duration::from_secs(20),
                        joined: None,
                    });
                }
            }
            if pending.as_ref().is_some_and(|p| Instant::now() >= p.until) {
                // A timed-out mutation has an unknown remote outcome. Reattach before
                // allowing more commands; never optimistically report a successful leave.
                bail!("network operation timed out; reconnect to reload server membership");
            }
            if Instant::now() >= heartbeat {
                session.send(&u32v(CLIENT_OP, 4))?;
                heartbeat = Instant::now() + Duration::from_secs(10);
            }
            if !session.stream.ready(20)? {
                continue;
            }
            let data = session.receive()?;
            let operation = op(&data)?;
            match operation {
                38 => {
                    membership.snapshot(&data)?;
                    events.send(Message::Membership(membership.clone()))?;
                }
                41 => {
                    membership.changes(&data)?;
                    events.send(Message::Membership(membership.clone()))?;
                }
                16 => bail!("server disconnected the session"),
                11 | 6 | 7 | 23 | 29 => events.send(Message::PeerRecord(data.clone()))?,
                _ => {}
            }
            let Some(p) = pending.as_mut() else {
                continue;
            };
            let mut complete = None;
            match (&p.command, operation) {
                (Command::Search { query, cursor }, 45) => {
                    let (networks, next) = listing(&data, p.id)?;
                    events.send(Message::Update(Update::Catalog {
                        query: query.clone(),
                        networks,
                        cursor: next,
                        append: *cursor != 0,
                    }))?;
                    complete = Some(("Public networks updated".to_owned(), false));
                }
                (Command::Join(name), 37) => match join_result(&data, p.id)? {
                    Some(JoinResult::Refused(code)) => {
                        complete = Some((
                            format!("Network could not be joined (server error {code})"),
                            true,
                        ));
                    }
                    Some(JoinResult::Membership(root)) => {
                        membership.snapshot(&tlv(0x1316, root))?;
                        p.joined = membership
                            .networks
                            .values()
                            .find(|n| n.name == *name)
                            .cloned();
                        ensure!(p.joined.is_some(), "JOIN network name mismatch");
                    }
                    None => {}
                },
                (Command::Join(_), 42) if p.joined.is_some() => {
                    let n = p.joined.as_ref().unwrap();
                    let r = records(&data)?;
                    let f = records(field(&r, 0x131f)?)?;
                    for record in f.iter().filter(|r| r.tag == 0x131e) {
                        let f = records(record.value)?;
                        if hex::encode(field(&f, 0x0d000309)?) == n.network_id {
                            complete = Some((format!("Joined {}", n.name), false));
                        }
                    }
                }
                (Command::Leave(network), 37) => {
                    match leave_result(&data, p.id, p.sequence, network)? {
                        Some(None) => {
                            membership.remove_network(network);
                            complete = Some(("Left network".into(), false));
                        }
                        Some(Some(code)) => {
                            complete = Some((
                                format!("Network could not be left (server error {code})"),
                                true,
                            ))
                        }
                        None => {}
                    }
                }
                _ => {}
            }
            if let Some((message, error)) = complete {
                events.send(Message::Membership(membership.clone()))?;
                events.send(Message::Update(Update::Operation { message, error }))?;
                pending = None;
            }
        }
        Ok(())
    })();
    if !stop.load(Ordering::Relaxed) {
        let _ = events.send(Message::ControlFailed(
            result
                .err()
                .map(|e| e.to_string())
                .unwrap_or_else(|| "Control connection closed".into()),
        ));
    }
}

struct Worker {
    stop: Arc<AtomicBool>,
    sender: SyncSender<Vec<u8>>,
    join: JoinHandle<()>,
    mac: Option<[u8; 6]>,
    incoming: bool,
}
#[allow(clippy::too_many_arguments)]
fn peer_loop(
    identity: Identity,
    modulus: Arc<Vec<u8>>,
    vip: Ipv4Addr,
    peer: Peer,
    stop: Arc<AtomicBool>,
    events: SyncSender<Message>,
    frames: Receiver<Vec<u8>>,
    incoming: Option<Setup>,
) {
    let rid = peer.rid;
    let result = (|| -> Result<()> {
        let mut channel = if let Some(setup) = incoming {
            setup.accept(
                identity.rid,
                vip,
                peer,
                &ReportDirectory::disabled(),
                stop.clone(),
            )?
        } else {
            PeerChannel::connect_with_stop(
                &identity,
                &modulus,
                vip,
                peer,
                &ReportDirectory::disabled(),
                Duration::from_secs(45),
                Some(stop.clone()),
            )?
        };
        channel.stream.sustain();
        events.send(Message::Connected(
            rid,
            channel.mac,
            channel.transport.path.unwrap(),
            channel.transport.incoming,
        ))?;
        let mut heartbeat = Instant::now();
        let mut sequence = 0;
        while !stop.load(Ordering::Relaxed) {
            for frame in frames.try_iter().take(32) {
                if channel.send(&tunnel::encode(&frame)?)? {
                    events.send(Message::Sent(frame.len()))?;
                } else {
                    events.send(Message::Dropped)?;
                }
            }
            if Instant::now() >= heartbeat {
                sequence += 1;
                channel.send(&tunnel::keepalive(sequence, false))?;
                heartbeat = Instant::now() + Duration::from_secs(15);
            }
            if !channel.stream.ready(20)? {
                continue;
            }
            let data = channel.receive()?;
            match tunnel::decode(&data)? {
                tunnel::Packet::Keepalive {
                    sequence,
                    reply: false,
                } => {
                    channel.send(&tunnel::keepalive(sequence, true))?;
                }
                tunnel::Packet::Frames(frames) => {
                    for frame in frames {
                        let _ = events.try_send(Message::Frame(rid, frame.to_vec()));
                    }
                }
                _ => {}
            }
        }
        Ok(())
    })();
    let error = if stop.load(Ordering::Relaxed) {
        None
    } else {
        result.err().map(|e| e.to_string())
    };
    let _ = events.send(Message::Closed(rid, error));
}

pub fn base_peer_state(peer: &Peer) -> PeerState {
    if ![1, 5].contains(&peer.state) {
        PeerState::Offline
    } else if peer.server.is_none() {
        PeerState::Unavailable
    } else {
        PeerState::Online
    }
}
pub fn failure_state(error: &str) -> PeerState {
    if error.contains("refused") || error.contains("rejected") {
        PeerState::Refused
    } else {
        PeerState::Failed
    }
}
/// Accept authenticated IPv4 unicast, broadcast, multicast and directed ARP.
pub fn valid_inbound(frame: &[u8], vip: Ipv4Addr, peer: Ipv4Addr, mac: [u8; 6]) -> bool {
    tunnel::deliver_to(frame, peer, mac, vip, tunnel::mac(vip))
}

pub fn run(
    identity: Identity,
    modulus: Vec<u8>,
    options: Options,
    commands: Receiver<Command>,
    stop: Arc<AtomicBool>,
    report: impl Fn(Update),
) -> Result<()> {
    let (mut session, mut membership, vip) = Session::attach_with_stop(
        &identity,
        &modulus,
        &ReportDirectory::disabled(),
        Duration::from_secs(45),
        Some(stop.clone()),
    )?;
    session.stream.sustain();
    let mut snapshot = Snapshot {
        vip: Some(vip),
        latency_ms: session.latency,
        restricted_traffic: options.traffic_peers.is_some(),
        ..Default::default()
    };
    let (tx, rx) = mpsc::sync_channel(512);
    let (control_tx, control_rx) = mpsc::sync_channel(8);
    let (wire_tx, wire_rx) = mpsc::sync_channel(64);
    let mut incoming = Hub::new(
        session.stream.socket.local_addr()?.ip(),
        session.ues.clone(),
        wire_tx,
    );
    let (events, control_stop, members) = (tx.clone(), stop.clone(), membership.clone());
    let control = thread::spawn(move || {
        control_loop(session, members, control_rx, wire_rx, events, control_stop)
    });
    let modulus = Arc::new(modulus);
    let mut workers: BTreeMap<u64, Worker> = BTreeMap::new();
    let mut retired = Vec::new();
    let mut tap: Option<Tap> = None;
    let mut attempted_interface = false;
    let started = Instant::now();
    let mut next_report = Instant::now();
    let result = (|| -> Result<()> {
        loop {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            match commands.try_recv() {
                Ok(Command::RetryInterface) => {
                    attempted_interface = false;
                }
                Ok(Command::RetryPeers) => {
                    for p in snapshot.peers.values_mut() {
                        if matches!(p.status, PeerState::Failed | PeerState::Refused)
                            && !workers.contains_key(&p.peer.rid)
                        {
                            p.status = base_peer_state(&p.peer);
                            p.detail.clear();
                        }
                    }
                }
                Ok(c) => {
                    control_tx
                        .try_send(c)
                        .map_err(|_| anyhow::anyhow!("Control worker unavailable"))?;
                }
                Err(mpsc::TryRecvError::Disconnected) => break,
                Err(mpsc::TryRecvError::Empty) => {}
            }
            for message in rx.try_iter().take(512) {
                match message {
                    Message::Membership(m) => {
                        membership = m;
                    }
                    Message::Update(u) => report(u),
                    Message::ControlFailed(e) => bail!(e),
                    Message::PeerRecord(data) => {
                        if let Err(e) = incoming.ingest(&data) {
                            report(Update::Operation {
                                message: format!("Incoming request rejected: {e}"),
                                error: true,
                            });
                        }
                    }
                    Message::Connected(rid, mac, path, is_incoming) => {
                        if workers
                            .get(&rid)
                            .is_none_or(|w| w.stop.load(Ordering::Relaxed))
                        {
                            continue;
                        }
                        incoming.reject(rid);
                        incoming.finish(rid);
                        if let Some(w) = workers.get_mut(&rid) {
                            w.mac = Some(mac);
                        }
                        if let Some(p) = snapshot.peers.get_mut(&rid) {
                            p.status = PeerState::Connected;
                            p.transport = Some(path);
                            p.detail = format!(
                                "Authenticated {} · {}",
                                path.label(),
                                if is_incoming { "Incoming" } else { "Outgoing" }
                            );
                        }
                    }
                    Message::Closed(rid, error) => {
                        incoming.finish(rid);
                        if let Some(p) = snapshot.peers.get_mut(&rid) {
                            p.status = error
                                .as_ref()
                                .map(|e| failure_state(e))
                                .unwrap_or_else(|| base_peer_state(&p.peer));
                            p.detail = error.unwrap_or_else(|| "Channel closed".into());
                            p.transport = None;
                        }
                        if let Some(w) = workers.remove(&rid) {
                            retired.push(w.join);
                        }
                    }
                    Message::Sent(bytes) => {
                        snapshot.traffic.sent_bytes += bytes as u64;
                        snapshot.traffic.sent_frames += 1;
                    }
                    Message::Dropped => snapshot.traffic.dropped += 1,
                    Message::Frame(rid, frame) => {
                        let valid = options.allows(rid)
                            && membership.peers.get(&rid).is_some_and(|p| {
                                workers
                                    .get(&rid)
                                    .filter(|w| !w.stop.load(Ordering::Relaxed))
                                    .and_then(|w| w.mac)
                                    .is_some_and(|mac| valid_inbound(&frame, vip, p.vip, mac))
                            });
                        if let Some(tap) = tap.as_mut().filter(|_| valid) {
                            match tap.send(&frame) {
                                Ok(()) => {
                                    snapshot.traffic.received_bytes += frame.len() as u64;
                                    snapshot.traffic.received_frames += 1;
                                }
                                Err(_) => {
                                    snapshot.traffic.dropped += 1;
                                }
                            }
                        } else {
                            snapshot.traffic.dropped += 1;
                        }
                    }
                }
            }
            snapshot.networks = membership.networks.values().cloned().collect();
            snapshot
                .peers
                .retain(|rid, _| membership.peers.contains_key(rid));
            for p in membership.peers.values().filter(|p| {
                p.rid != identity.rid
                    && p.network_ids
                        .iter()
                        .any(|id| membership.networks.contains_key(id))
            }) {
                let view = snapshot.peers.entry(p.rid).or_insert_with(|| PeerView {
                    peer: p.clone(),
                    status: base_peer_state(p),
                    detail: String::new(),
                    transport: None,
                });
                if view.peer.state != p.state
                    || view.peer.server != p.server
                    || view.peer.vip != p.vip
                {
                    view.status = base_peer_state(p);
                    view.detail.clear();
                    view.transport = None;
                    if let Some(w) = workers.get(&p.rid) {
                        w.stop.store(true, Ordering::Relaxed);
                    }
                }
                view.peer = p.clone();
            }
            let eligible: BTreeSet<_> = membership
                .eligible(identity.rid, &[])?
                .into_iter()
                .map(|p| p.rid)
                .collect();
            for (rid, w) in &workers {
                if !eligible.contains(rid) {
                    w.stop.store(true, Ordering::Relaxed);
                }
            }
            incoming.expire();
            let pending = incoming.pending_rids();
            for rid in &pending {
                if let Some(w) = workers.get(rid) {
                    if w.mac.is_some() {
                        incoming.reject(*rid);
                    } else if !w.incoming && identity.rid > *rid {
                        w.stop.store(true, Ordering::Relaxed);
                    }
                }
            }
            // At most four handshakes run at once. Queue every eligible member;
            // disconnected/refused members are retried only on explicit request.
            let connecting = workers.values().filter(|w| w.mac.is_none()).count();
            let available = 4usize.saturating_sub(connecting);
            let mut queued: Vec<_> = snapshot
                .peers
                .values()
                .filter(|p| {
                    (p.status == PeerState::Online || pending.contains(&p.peer.rid))
                        && eligible.contains(&p.peer.rid)
                        && !workers.contains_key(&p.peer.rid)
                })
                .map(|p| p.peer.clone())
                .collect();
            queued.sort_by_key(|p| (!pending.contains(&p.rid), !options.allows(p.rid), p.rid));
            for peer in queued.into_iter().take(available) {
                let rid = peer.rid;
                let setup = incoming.take(rid, Policy::All);
                let is_incoming = setup.is_some();
                snapshot.peers.get_mut(&rid).unwrap().status = PeerState::Connecting;
                let (sender, frames) = mpsc::sync_channel(64);
                let peer_stop = Arc::new(AtomicBool::new(false));
                let (id, key, cancel, events) = (
                    identity.clone(),
                    modulus.clone(),
                    peer_stop.clone(),
                    tx.clone(),
                );
                let join = thread::spawn(move || {
                    peer_loop(id, key, vip, peer, cancel, events, frames, setup)
                });
                workers.insert(
                    rid,
                    Worker {
                        stop: peer_stop,
                        sender,
                        join,
                        mac: None,
                        incoming: is_incoming,
                    },
                );
            }
            if !attempted_interface {
                // Keep the /8 interface stable across membership changes. Dynamic
                // peer selection below governs forwarding without resetting sockets.
                drop(tap.take());
                snapshot.interface_ready = false;
                snapshot.interface_error = None;
                report(Update::State(snapshot.clone()));
                let helper = options
                    .helper
                    .clone()
                    .map(Ok)
                    .unwrap_or_else(std::env::current_exe)?;
                match Tap::create_lan_with_helper(vip, &helper) {
                    Ok(t) => {
                        tap = Some(t);
                        snapshot.interface_ready = true;
                    }
                    Err(e) => {
                        snapshot.interface_error = Some(e.to_string());
                    }
                }
                attempted_interface = true;
            }
            if let Some(t) = tap.as_mut() {
                for _ in 0..64 {
                    if !t.ready(0)? {
                        break;
                    }
                    let frame = t.receive()?;
                    let mut forwarded = false;
                    for (rid, w) in &workers {
                        if !options.allows(*rid)
                            || !eligible.contains(rid)
                            || w.stop.load(Ordering::Relaxed)
                        {
                            continue;
                        }
                        let Some(mac) = w.mac else {
                            continue;
                        };
                        let Some(p) = membership.peers.get(rid) else {
                            continue;
                        };
                        if tunnel::deliver_to(&frame, vip, tunnel::mac(vip), p.vip, mac) {
                            if w.sender.try_send(frame.clone()).is_ok() {
                                forwarded = true;
                            } else {
                                snapshot.traffic.dropped += 1;
                            }
                        }
                    }
                    if !forwarded {
                        snapshot.traffic.dropped += 1;
                    }
                }
            }
            retired.retain(|handle| !handle.is_finished());
            if Instant::now() >= next_report {
                snapshot.elapsed_secs = started.elapsed().as_secs();
                report(Update::State(snapshot.clone()));
                next_report = Instant::now() + Duration::from_millis(250);
            }
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    })();
    drop(tap);
    stop.store(true, Ordering::Relaxed);
    for worker in workers.values() {
        worker.stop.store(true, Ordering::Relaxed);
    }
    drop(rx); // Unblock producers before joining, including full event queues.
    drop(control_tx);
    let _ = control.join();
    for worker in workers.into_values() {
        let _ = worker.join.join();
    }
    for worker in retired {
        let _ = worker.join();
    }
    result
}

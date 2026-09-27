//! Long-lived application engine. UI adapters send commands and consume snapshots;
//! control I/O, peer handshakes and Ethernet forwarding never run on the UI thread.
use crate::{
    diagnostics::Diagnostics,
    incoming::{Hub, Policy, Setup},
    network::{NetworkOperation, NetworkRequest},
    output::ReportDirectory,
    peer::{PeerChannel, TransportPath},
    protocol::*,
    scheduling::{peer_retry_delay, HandshakeBudget},
    session::Session,
    tap::Tap,
    tunnel,
};
use anyhow::{bail, Context, Result};
use serde::Serialize;
use serde_json::json;
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    net::Ipv4Addr,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
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
    Network(NetworkRequest),
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
    pub roles: BTreeMap<String, BTreeMap<u64, u32>>,
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
    pub diagnostics: Diagnostics,
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
    Closed(u64, Option<String>),
}

/// Packet accounting cannot block peer keepalives behind the engine event queue.
#[derive(Default)]
struct TrafficCounters {
    sent_bytes: AtomicU64,
    sent_frames: AtomicU64,
    dropped: AtomicU64,
    receive_queue_dropped: AtomicU64,
}

/// Retain lifecycle events in order while continuing attachment heartbeats.
/// The control reader pauses while this outbox is full, so memory stays bounded.
struct ControlOutbox {
    sender: SyncSender<Message>,
    pending: VecDeque<Message>,
}
impl ControlOutbox {
    fn push(&mut self, message: Message) {
        self.pending.push_back(message);
    }
    fn flush(&mut self) -> Result<bool> {
        while let Some(message) = self.pending.pop_front() {
            match self.sender.try_send(message) {
                Ok(()) => {}
                Err(mpsc::TrySendError::Full(message)) => {
                    self.pending.push_front(message);
                    return Ok(false);
                }
                Err(mpsc::TrySendError::Disconnected(_)) => bail!("engine event receiver closed"),
            }
        }
        Ok(true)
    }
}
enum PendingKind {
    Search { query: String, cursor: u64 },
    Network(Box<NetworkOperation>),
}
struct Pending {
    kind: PendingKind,
    id: u64,
    until: Instant,
}
struct ControlConfig {
    diagnostics: Diagnostics,
    heartbeat_interval: Duration,
    operation_timeout: Duration,
}
impl Default for ControlConfig {
    fn default() -> Self {
        Self {
            diagnostics: Diagnostics::default(),
            heartbeat_interval: Duration::from_secs(10),
            operation_timeout: Duration::from_secs(20),
        }
    }
}
fn forward_command(sender: &SyncSender<Command>, command: Command) -> Result<Option<Update>> {
    match sender.try_send(command) {
        Ok(()) => Ok(None),
        Err(mpsc::TrySendError::Full(_)) => Ok(Some(Update::Operation {
            message: "Too many network commands are waiting. Try again shortly.".into(),
            error: true,
        })),
        Err(mpsc::TrySendError::Disconnected(_)) => bail!("Control worker unavailable"),
    }
}
fn control_loop(
    mut session: Session,
    mut membership: Membership,
    commands: Receiver<Command>,
    wire: Receiver<Vec<u8>>,
    events: SyncSender<Message>,
    stop: Arc<AtomicBool>,
    config: ControlConfig,
) {
    let diagnostics = config.diagnostics;
    let mut received = 0u64;
    let mut last_operation = None;
    let mut last_receive = Instant::now();
    let mut heartbeats = 0u64;
    let mut heartbeat_late_ms = 0u128;
    let mut backpressure_since = None;
    let mut outbox = ControlOutbox {
        sender: events.clone(),
        pending: VecDeque::new(),
    };
    let result = (|| -> Result<()> {
        let mut next_id = 100;
        let mut sequence = 0u32;
        let mut pending: Option<Pending> = None;
        let mut heartbeat = Instant::now();
        let mut health = Instant::now() + Duration::from_secs(30);
        loop {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            // Heartbeats have priority over incoming setup advertisements and UI work.
            if Instant::now() >= heartbeat {
                let late_ms = heartbeat.elapsed().as_millis();
                heartbeat_late_ms = heartbeat_late_ms.max(late_ms);
                if late_ms >= 1_000 {
                    diagnostics.event("control_heartbeat_delayed", json!({"late_ms": late_ms}));
                }
                session
                    .send(&u32v(CLIENT_OP, 4))
                    .context("sending attachment heartbeat")?;
                heartbeats += 1;
                heartbeat = Instant::now() + config.heartbeat_interval;
            }
            if Instant::now() >= health {
                diagnostics.event("control_health", json!({
                    "records_received": received, "last_operation": last_operation,
                    "last_receive_age_ms": last_receive.elapsed().as_millis(),
                    "heartbeats_sent": heartbeats, "max_heartbeat_late_ms": heartbeat_late_ms,
                    "pending_events": outbox.pending.len(), "operation_pending": pending.is_some(),
                }));
                health = Instant::now() + Duration::from_secs(30);
            }
            if !outbox.flush()? {
                if backpressure_since.is_none() {
                    backpressure_since = Some(Instant::now());
                    diagnostics.event(
                        "control_event_backpressure",
                        json!({"pending_events": outbox.pending.len()}),
                    );
                }
                thread::sleep(Duration::from_millis(5));
                continue;
            }
            if let Some(since) = backpressure_since.take() {
                diagnostics.event(
                    "control_event_queue_recovered",
                    json!({"duration_ms": since.elapsed().as_millis()}),
                );
            }
            for bytes in wire.try_iter().take(8) {
                session
                    .send(&bytes)
                    .context("sending incoming peer advertisement")?;
                if Instant::now() >= heartbeat {
                    break;
                }
            }
            if pending.is_none() {
                if let Ok(command) = commands.try_recv() {
                    next_id += 1;
                    sequence += 1;
                    let prepared = (|| -> Result<_> {
                        let request = match command {
                            Command::Search { query, cursor } => {
                                let bytes = public_list(&query, next_id, cursor)?;
                                return Ok((PendingKind::Search { query, cursor }, bytes));
                            }
                            Command::Join(name) => NetworkRequest::public_join(name),
                            Command::Leave(network) => NetworkRequest::Leave { network },
                            Command::Network(request) => request,
                            _ => anyhow::bail!("unsupported control command"),
                        };
                        let (operation, bytes) =
                            NetworkOperation::start(request, next_id, sequence)?;
                        Ok((PendingKind::Network(Box::new(operation)), bytes))
                    })();
                    match prepared {
                        Ok((kind, bytes)) => {
                            session.send(&bytes).context("sending network command")?;
                            diagnostics.event("control_operation_started", json!({
                                "request_id": next_id,
                                "kind": if matches!(&kind, PendingKind::Search { .. }) { "search" } else { "network" },
                            }));
                            pending = Some(Pending {
                                kind,
                                id: next_id,
                                until: Instant::now() + config.operation_timeout,
                            });
                        }
                        Err(error) => {
                            outbox.push(Message::Update(Update::Operation {
                                message: error.to_string(),
                                error: true,
                            }));
                        }
                    }
                }
            }
            if pending.as_ref().is_some_and(|p| Instant::now() >= p.until) {
                let expired = pending.take().unwrap();
                diagnostics.event("control_operation_timeout", json!({
                    "request_id": expired.id,
                    "kind": if matches!(&expired.kind, PendingKind::Search { .. }) { "search" } else { "network" },
                }));
                if matches!(expired.kind, PendingKind::Search { .. }) {
                    outbox.push(Message::Update(Update::Operation {
                        message: "Public network search timed out. You can retry the search."
                            .into(),
                        error: true,
                    }));
                    continue;
                }
                // A timed-out mutation has an unknown remote outcome. Reattach before
                // allowing more commands; never optimistically report a successful leave.
                bail!("network operation timed out; reconnect to reload server membership");
            }
            if !session
                .stream
                .ready(20)
                .context("polling attachment connection")?
            {
                continue;
            }
            let data = session.receive().context("receiving attachment record")?;
            let operation = op(&data).context("decoding attachment operation")?;
            received += 1;
            last_receive = Instant::now();
            last_operation = Some(operation);
            match operation {
                38 => {
                    membership
                        .snapshot(&data)
                        .context("decoding membership snapshot")?;
                    outbox.push(Message::Membership(membership.clone()));
                }
                41 | 42 => {
                    membership
                        .changes(&data)
                        .context("decoding membership changes")?;
                    outbox.push(Message::Membership(membership.clone()));
                }
                16 => {
                    let fields = records(&data)?;
                    diagnostics.event("server_disconnect", json!({
                        "reason_code": optional(&fields, 0x010001d2)?.map(int32).transpose()?,
                        "record_bytes": data.len(),
                        "fields": fields.iter().map(|field| json!({"tag": format!("0x{:08x}", field.tag), "bytes": field.value.len()})).collect::<Vec<_>>(),
                    }));
                    bail!("server disconnected the session");
                }
                11 | 6 | 7 | 23 | 29 => outbox.push(Message::PeerRecord(data.clone())),
                _ => {}
            }
            let Some(p) = pending.as_mut() else {
                continue;
            };
            let mut complete = None;
            match &mut p.kind {
                PendingKind::Search { query, cursor } if operation == 45 => {
                    if listing_id(&data)? != p.id {
                        diagnostics.event(
                            "stale_search_reply",
                            json!({"request_id": listing_id(&data)?, "expected_id": p.id}),
                        );
                        continue;
                    }
                    let (networks, next) = listing(&data, p.id)?;
                    outbox.push(Message::Update(Update::Catalog {
                        query: query.clone(),
                        networks,
                        cursor: next,
                        append: *cursor != 0,
                    }));
                    complete = Some(("Public networks updated".to_owned(), false));
                }
                PendingKind::Network(network) => {
                    let progress = network.handle(&data, &mut membership)?;
                    if let Some(bytes) = progress.send {
                        session.send(&bytes)?;
                    }
                    if let Some(result) = progress.complete {
                        complete = Some((result.message, result.error));
                    }
                }
                _ => {}
            }
            if let Some((message, error)) = complete {
                diagnostics.event(
                    "control_operation_completed",
                    json!({"request_id": p.id, "error": error}),
                );
                outbox.push(Message::Membership(membership.clone()));
                outbox.push(Message::Update(Update::Operation { message, error }));
                pending = None;
            }
        }
        Ok(())
    })();
    diagnostics.event("control_closed", json!({
        "cancelled": stop.load(Ordering::Relaxed), "error": result.as_ref().err().map(|e| format!("{e:#}")),
        "records_received": received, "last_operation": last_operation,
        "last_receive_age_ms": last_receive.elapsed().as_millis(),
        "heartbeats_sent": heartbeats, "max_heartbeat_late_ms": heartbeat_late_ms,
        "pending_events": outbox.pending.len(),
    }));
    if !stop.load(Ordering::Relaxed) {
        let _ = events.send(Message::ControlFailed(
            result
                .err()
                .map(|e| format!("{e:#}"))
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
    attempt: u64,
    connected_at: Option<Instant>,
}
struct PeerRetry {
    failures: u32,
    at: Instant,
}
struct PeerEvents {
    lifecycle: SyncSender<Message>,
    packets: SyncSender<(u64, Vec<u8>)>,
    traffic: Arc<TrafficCounters>,
    diagnostics: Diagnostics,
    attempt: u64,
}
impl PeerEvents {
    fn packet(&self, rid: u64, frame: &[u8]) {
        if self.packets.try_send((rid, frame.to_vec())).is_err() {
            self.traffic.dropped.fetch_add(1, Ordering::Relaxed);
            self.traffic
                .receive_queue_dropped
                .fetch_add(1, Ordering::Relaxed);
        }
    }
}
#[allow(clippy::too_many_arguments)]
fn peer_loop(
    identity: Identity,
    modulus: Arc<Vec<u8>>,
    vip: Ipv4Addr,
    peer: Peer,
    stop: Arc<AtomicBool>,
    events: PeerEvents,
    frames: Receiver<Vec<u8>>,
    incoming: Option<Setup>,
) {
    let rid = peer.rid;
    let started = Instant::now();
    let mut connected_at = None;
    let mut last_receive = None;
    let mut keepalives = 0u64;
    let mut keepalive_replies = 0u64;
    let mut transport = None;
    let observe = |transport: &crate::peer::TransportReport| {
        events.diagnostics.event(
            "peer_transport_attempts",
            json!({
                "rid": rid, "attempt": events.attempt, "transport": transport,
            }),
        );
    };
    let result =
        (|| -> Result<()> {
            let mut channel = if let Some(setup) = incoming {
                setup.accept_observed(
                    identity.rid,
                    vip,
                    peer,
                    &ReportDirectory::disabled(),
                    stop.clone(),
                    observe,
                )?
            } else {
                PeerChannel::connect_observed(
                    &identity,
                    &modulus,
                    vip,
                    peer,
                    &ReportDirectory::disabled(),
                    Duration::from_secs(45),
                    Some(stop.clone()),
                    observe,
                )?
            };
            channel.stream.sustain();
            connected_at = Some(Instant::now());
            transport = channel.transport.path;
            events.diagnostics.event("peer_connected", json!({
            "rid": rid, "attempt": events.attempt, "setup_ms": started.elapsed().as_millis(),
            "transport": transport, "incoming": channel.transport.incoming,
            "endpoint": channel.transport.endpoint,
        }));
            events.lifecycle.send(Message::Connected(
                rid,
                channel.mac,
                channel.transport.path.unwrap(),
                channel.transport.incoming,
            ))?;
            let mut heartbeat = Instant::now();
            let mut sequence = 0u32;
            while !stop.load(Ordering::Relaxed) {
                if Instant::now() >= heartbeat {
                    sequence = sequence.wrapping_add(1);
                    if channel.send(&tunnel::keepalive(sequence, false))? {
                        keepalives += 1;
                        heartbeat = Instant::now() + Duration::from_secs(15);
                    } else {
                        heartbeat = Instant::now() + Duration::from_millis(200);
                    }
                }
                let send_started = Instant::now();
                for frame in frames.try_iter().take(32) {
                    if channel.send(&tunnel::encode(&frame)?)? {
                        events
                            .traffic
                            .sent_bytes
                            .fetch_add(frame.len() as u64, Ordering::Relaxed);
                        events.traffic.sent_frames.fetch_add(1, Ordering::Relaxed);
                    } else {
                        events.traffic.dropped.fetch_add(1, Ordering::Relaxed);
                    }
                    if send_started.elapsed() >= Duration::from_millis(20) {
                        break;
                    }
                }
                if !channel.stream.ready(20)? {
                    continue;
                }
                let data = channel.receive()?;
                last_receive = Some(Instant::now());
                match tunnel::decode(&data)? {
                    tunnel::Packet::Keepalive {
                        sequence,
                        reply: false,
                    } => {
                        channel.send(&tunnel::keepalive(sequence, true))?;
                    }
                    tunnel::Packet::Keepalive { reply: true, .. } => keepalive_replies += 1,
                    tunnel::Packet::Frames(frames) => {
                        for frame in frames {
                            events.packet(rid, frame);
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
        result.err().map(|e| format!("{e:#}"))
    };
    events.diagnostics.event(
        "peer_closed",
        json!({
            "rid": rid, "attempt": events.attempt, "transport": transport,
            "cancelled": stop.load(Ordering::Relaxed), "error": error,
            "lifetime_ms": started.elapsed().as_millis(),
            "connected_ms": connected_at.map(|t| t.elapsed().as_millis()),
            "last_receive_age_ms": last_receive.map(|t| t.elapsed().as_millis()),
            "keepalives_sent": keepalives, "keepalive_replies": keepalive_replies,
        }),
    );
    let _ = events.lifecycle.send(Message::Closed(rid, error));
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

/// Membership only changes on control events. Rebuild owned UI data and the
/// forwarding allowlist there, instead of cloning every member every 5 ms.
fn refresh_membership(
    snapshot: &mut Snapshot,
    membership: &Membership,
    own_rid: u64,
    workers: &BTreeMap<u64, Worker>,
    diagnostics: &Diagnostics,
) -> Result<BTreeSet<u64>> {
    snapshot.networks = membership.networks.values().cloned().collect();
    snapshot.roles = membership.roles.clone();
    snapshot
        .peers
        .retain(|rid, _| membership.peers.contains_key(rid));
    for p in membership.peers.values().filter(|p| {
        p.rid != own_rid
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
        // States 1 and 5 are both eligible online states. A presence refresh
        // between them does not invalidate an authenticated identity/VIP binding.
        if base_peer_state(&view.peer) != base_peer_state(p)
            || view.peer.server != p.server
            || view.peer.vip != p.vip
        {
            view.status = base_peer_state(p);
            view.detail.clear();
            view.transport = None;
            if let Some(w) = workers.get(&p.rid) {
                if !w.stop.swap(true, Ordering::Relaxed) {
                    diagnostics.event("peer_cancelled", json!({
                        "rid": p.rid, "attempt": w.attempt, "reason": "membership_binding_changed",
                        "old_state": view.peer.state, "new_state": p.state,
                        "old_vip": view.peer.vip, "new_vip": p.vip,
                        "old_server": view.peer.server, "new_server": p.server,
                    }));
                }
            }
        }
        view.peer = p.clone();
    }
    let eligible: BTreeSet<_> = membership
        .eligible(own_rid, &[])?
        .into_iter()
        .map(|p| p.rid)
        .collect();
    for (rid, w) in workers {
        if !eligible.contains(rid) && !w.stop.swap(true, Ordering::Relaxed) {
            diagnostics.event(
                "peer_cancelled",
                json!({"rid": rid, "attempt": w.attempt, "reason": "membership_ineligible"}),
            );
        }
    }
    Ok(eligible)
}

pub fn run(
    identity: Identity,
    modulus: Vec<u8>,
    options: Options,
    commands: Receiver<Command>,
    stop: Arc<AtomicBool>,
    report: impl Fn(Update),
) -> Result<()> {
    let diagnostics = options.diagnostics.new_session();
    diagnostics.event("session_start", json!({"server": identity.server_address}));
    let attached = Session::attach_with_stop(
        &identity,
        &modulus,
        &ReportDirectory::disabled(),
        Duration::from_secs(45),
        Some(stop.clone()),
    );
    let (mut session, mut membership, vip) = match attached {
        Ok(attached) => attached,
        Err(error) => {
            diagnostics.event(
                "session_attach_failed",
                json!({"error": format!("{error:#}"), "cancelled": stop.load(Ordering::Relaxed)}),
            );
            return Err(error);
        }
    };
    session.stream.sustain();
    diagnostics.event("session_attached", json!({"latency_ms": session.latency, "peers": membership.peers.len(), "networks": membership.networks.len()}));
    let mut snapshot = Snapshot {
        vip: Some(vip),
        latency_ms: session.latency,
        restricted_traffic: options.traffic_peers.is_some(),
        ..Default::default()
    };
    let (tx, rx) = mpsc::sync_channel(512);
    let (packet_tx, packets) = mpsc::sync_channel::<(u64, Vec<u8>)>(512);
    let traffic = Arc::new(TrafficCounters::default());
    let (control_tx, control_rx) = mpsc::sync_channel(8);
    let (wire_tx, wire_rx) = mpsc::sync_channel(64);
    let mut incoming = Hub::new(
        session.stream.socket.local_addr()?.ip(),
        session.ues.clone(),
        wire_tx,
    );
    let (events, control_stop, members) = (tx.clone(), stop.clone(), membership.clone());
    let control_log = diagnostics.clone();
    let control = thread::spawn(move || {
        control_loop(
            session,
            members,
            control_rx,
            wire_rx,
            events,
            control_stop,
            ControlConfig {
                diagnostics: control_log,
                ..Default::default()
            },
        )
    });
    let modulus = Arc::new(modulus);
    let mut workers: BTreeMap<u64, Worker> = BTreeMap::new();
    let mut retired = Vec::new();
    let mut tap: Option<Tap> = None;
    let mut attempted_interface = false;
    let started = Instant::now();
    let mut next_report = Instant::now();
    let mut membership_changed = true;
    let mut eligible = BTreeSet::new();
    let mut retries: BTreeMap<u64, PeerRetry> = BTreeMap::new();
    let mut attempt = 0u64;
    let mut next_health = Instant::now();
    let mut previous_loop = Instant::now();
    let mut max_loop_gap_ms = 0u128;
    let mut previous_connected = 0usize;
    let mut previous_peer_drops = 0;
    let result = (|| -> Result<()> {
        loop {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            max_loop_gap_ms = max_loop_gap_ms.max(previous_loop.elapsed().as_millis());
            previous_loop = Instant::now();
            match commands.try_recv() {
                Ok(Command::RetryInterface) => {
                    attempted_interface = false;
                }
                Ok(Command::RetryPeers) => {
                    retries.clear();
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
                    if let Some(update) = forward_command(&control_tx, c)? {
                        diagnostics.event("control_command_queue_full", json!({}));
                        report(update);
                    }
                }
                Err(mpsc::TryRecvError::Disconnected) => break,
                Err(mpsc::TryRecvError::Empty) => {}
            }
            for message in rx.try_iter().take(512) {
                match message {
                    Message::Membership(m) => {
                        membership = m;
                        membership_changed = true;
                    }
                    Message::Update(u) => report(u),
                    Message::ControlFailed(e) => bail!(e),
                    Message::PeerRecord(data) => {
                        if let Err(e) = incoming.ingest(&data) {
                            diagnostics.event("incoming_record_rejected", json!({"operation": op(&data).ok(), "record_bytes": data.len(), "error": format!("{e:#}")}));
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
                            w.connected_at = Some(Instant::now());
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
                        let worker = workers.remove(&rid);
                        let cancelled = worker
                            .as_ref()
                            .is_some_and(|w| w.stop.load(Ordering::Relaxed));
                        let error = if cancelled { None } else { error };
                        if error
                            .as_ref()
                            .is_some_and(|e| failure_state(e) == PeerState::Failed)
                        {
                            let retry = retries.entry(rid).or_insert(PeerRetry {
                                failures: 0,
                                at: Instant::now(),
                            });
                            if worker
                                .as_ref()
                                .and_then(|w| w.connected_at)
                                .is_some_and(|t| t.elapsed() >= Duration::from_secs(60))
                            {
                                retry.failures = 0;
                            }
                            retry.failures = retry.failures.saturating_add(1);
                            let delay = peer_retry_delay(rid, retry.failures);
                            retry.at = Instant::now() + delay;
                            diagnostics.event("peer_retry_scheduled", json!({"rid": rid, "failures": retry.failures, "delay_ms": delay.as_millis(), "error": error}));
                        } else {
                            retries.remove(&rid);
                        }
                        if let Some(p) = snapshot.peers.get_mut(&rid) {
                            p.status = error
                                .as_ref()
                                .map(|e| failure_state(e))
                                .unwrap_or_else(|| base_peer_state(&p.peer));
                            p.detail = error.unwrap_or_else(|| "Channel closed".into());
                            if let Some(retry) = retries.get(&rid) {
                                p.detail.push_str(&format!(
                                    " · Retrying in {}s",
                                    retry.at.saturating_duration_since(Instant::now()).as_secs()
                                        + 1
                                ));
                            }
                            p.transport = None;
                        }
                        if let Some(w) = worker {
                            retired.push(w.join);
                        }
                    }
                }
            }
            if membership_changed {
                eligible = refresh_membership(
                    &mut snapshot,
                    &membership,
                    identity.rid,
                    &workers,
                    &diagnostics,
                )?;
                retries.retain(|rid, _| eligible.contains(rid));
                diagnostics.event("membership_updated", json!({
                    "peers": snapshot.peers.len(), "eligible": eligible.len(), "networks": snapshot.networks.len(),
                    "online_without_server": snapshot.peers.values().filter(|p| p.status == PeerState::Unavailable).count(),
                    "cancelling_workers": workers.values().filter(|w| w.stop.load(Ordering::Relaxed)).count(),
                }));
                membership_changed = false;
            }
            for (rid, frame) in packets.try_iter().take(512) {
                let valid = options.allows(rid)
                    && eligible.contains(&rid)
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
            incoming.expire();
            let pending = incoming.pending_rids();
            for rid in &pending {
                if let Some(w) = workers.get(rid) {
                    if w.mac.is_some() {
                        incoming.reject(*rid);
                    } else if !w.incoming
                        && identity.rid > *rid
                        && !w.stop.swap(true, Ordering::Relaxed)
                    {
                        diagnostics.event("peer_cancelled", json!({"rid": rid, "attempt": w.attempt, "reason": "incoming_collision"}));
                    }
                }
            }
            // Established channels do not occupy handshake slots. Reserve room
            // for incoming offers while slow outgoing attempts are in flight.
            let mut budget = HandshakeBudget::new(
                workers
                    .values()
                    .filter(|w| w.mac.is_none())
                    .map(|w| w.incoming),
            );
            let mut queued: Vec<_> = snapshot
                .peers
                .values()
                .filter(|p| {
                    (p.status == PeerState::Online
                        || pending.contains(&p.peer.rid)
                        || retries
                            .get(&p.peer.rid)
                            .is_some_and(|retry| Instant::now() >= retry.at))
                        && eligible.contains(&p.peer.rid)
                        && !workers.contains_key(&p.peer.rid)
                })
                .map(|p| p.peer.rid)
                .collect();
            queued.sort_by_key(|rid| (!pending.contains(rid), !options.allows(*rid), *rid));
            for rid in queued {
                if !budget.try_start(pending.contains(&rid)) {
                    continue;
                }
                let peer = snapshot.peers[&rid].peer.clone();
                let setup = incoming.take(rid, Policy::All);
                let is_incoming = setup.is_some();
                snapshot.peers.get_mut(&rid).unwrap().status = PeerState::Connecting;
                let (sender, frames) = mpsc::sync_channel(64);
                let peer_stop = Arc::new(AtomicBool::new(false));
                attempt += 1;
                diagnostics.event("peer_connecting", json!({"rid": rid, "attempt": attempt, "incoming": is_incoming, "server": peer.server}));
                let (id, key, cancel) = (identity.clone(), modulus.clone(), peer_stop.clone());
                let events = PeerEvents {
                    lifecycle: tx.clone(),
                    packets: packet_tx.clone(),
                    traffic: traffic.clone(),
                    diagnostics: diagnostics.clone(),
                    attempt,
                };
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
                        attempt,
                        connected_at: None,
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
                        diagnostics.event("interface_ready", json!({}));
                    }
                    Err(e) => {
                        snapshot.interface_error = Some(e.to_string());
                        diagnostics.event("interface_failed", json!({"error": format!("{e:#}")}));
                    }
                }
                attempted_interface = true;
            }
            if let Some(t) = tap.as_mut() {
                let forwarding = (|| -> Result<()> {
                    for _ in 0..64 {
                        if !t.ready(0)? {
                            break;
                        }
                        let frame = match t.receive() {
                            Ok(frame) => frame,
                            Err(error)
                                if error.downcast_ref::<std::io::Error>().is_some_and(|e| {
                                    matches!(
                                        e.kind(),
                                        std::io::ErrorKind::WouldBlock
                                            | std::io::ErrorKind::Interrupted
                                    )
                                }) =>
                            {
                                break
                            }
                            Err(error) => return Err(error),
                        };
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
                    Ok(())
                })();
                if let Err(error) = forwarding {
                    diagnostics.event("interface_failed", json!({"error": format!("{error:#}")}));
                    snapshot.interface_ready = false;
                    snapshot.interface_error = Some(format!("{error:#}"));
                    drop(tap.take());
                }
            }
            retired.retain(|handle| !handle.is_finished());
            if Instant::now() >= next_report {
                snapshot.traffic.sent_bytes = traffic.sent_bytes.load(Ordering::Relaxed);
                snapshot.traffic.sent_frames = traffic.sent_frames.load(Ordering::Relaxed);
                let peer_drops = traffic.dropped.load(Ordering::Relaxed);
                snapshot.traffic.dropped += peer_drops.saturating_sub(previous_peer_drops);
                previous_peer_drops = peer_drops;
                let connected = snapshot
                    .peers
                    .values()
                    .filter(|p| p.status == PeerState::Connected)
                    .count();
                if previous_connected >= 10 && connected < previous_connected / 2 {
                    diagnostics.event("peer_drop_burst", json!({"previous_connected": previous_connected, "connected": connected, "eligible": eligible.len()}));
                }
                previous_connected = connected;
                if Instant::now() >= next_health {
                    diagnostics.event("session_health", json!({
                        "connected": connected, "eligible": eligible.len(), "roster": snapshot.peers.len(),
                        "handshakes": workers.values().filter(|w| w.mac.is_none()).count(),
                        "pending_incoming": pending.len(), "scheduled_retries": retries.keys().filter(|rid| !workers.contains_key(rid)).count(),
                        "traffic": snapshot.traffic, "receive_queue_dropped": traffic.receive_queue_dropped.load(Ordering::Relaxed),
                        "max_loop_gap_ms": max_loop_gap_ms, "interface_ready": snapshot.interface_ready,
                    }));
                    max_loop_gap_ms = 0;
                    next_health = Instant::now() + Duration::from_secs(30);
                }
                snapshot.elapsed_secs = started.elapsed().as_secs();
                report(Update::State(snapshot.clone()));
                next_report = Instant::now() + Duration::from_millis(250);
            }
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    })();
    diagnostics.event("session_end", json!({
        "cancelled": stop.load(Ordering::Relaxed), "error": result.as_ref().err().map(|e| format!("{e:#}")),
        "uptime_ms": started.elapsed().as_millis(),
        "connected": workers.values().filter(|w| w.mac.is_some()).count(),
        "handshakes": workers.values().filter(|w| w.mac.is_none()).count(),
        "eligible": eligible.len(), "traffic": snapshot.traffic,
    }));
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

#[cfg(test)]
mod control_tests {
    use super::*;
    use crate::{crypto::Channel, session::Framed};
    use std::net::{TcpListener, TcpStream};

    struct Harness {
        remote: Session,
        commands: SyncSender<Command>,
        events: Receiver<Message>,
        stop: Arc<AtomicBool>,
        join: Option<JoinHandle<()>>,
        _wire: SyncSender<Vec<u8>>,
    }
    impl Harness {
        fn new(block_events: bool) -> Self {
            Self::with_diagnostics(block_events, Diagnostics::default())
        }
        fn with_diagnostics(block_events: bool, diagnostics: Diagnostics) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
            let (remote, _) = listener.accept().unwrap();
            let stop = Arc::new(AtomicBool::new(false));
            let make_session = |socket, stop| {
                let mut stream = Framed::from_socket(socket, Duration::from_secs(5), stop).unwrap();
                stream.sustain();
                Session {
                    stream,
                    channel: Channel::new(&[42; 32]).unwrap(),
                    latency: 0,
                    ues: vec![],
                }
            };
            let session = make_session(socket, Some(stop.clone()));
            let remote = make_session(remote, None);
            let (commands, command_rx) = mpsc::sync_channel(8);
            let (wire, wire_rx) = mpsc::sync_channel(8);
            let (event_tx, events) = mpsc::sync_channel(if block_events { 1 } else { 8 });
            if block_events {
                event_tx
                    .send(Message::Update(Update::Operation {
                        message: "occupied".into(),
                        error: false,
                    }))
                    .unwrap();
            }
            let cancel = stop.clone();
            let join = thread::spawn(move || {
                control_loop(
                    session,
                    Membership::default(),
                    command_rx,
                    wire_rx,
                    event_tx,
                    cancel,
                    ControlConfig {
                        diagnostics,
                        heartbeat_interval: Duration::from_millis(25),
                        operation_timeout: Duration::from_millis(300),
                    },
                )
            });
            Self {
                remote,
                commands,
                events,
                stop,
                join: Some(join),
                _wire: wire,
            }
        }
        fn receive_operation(&mut self, expected: u32) -> Vec<u8> {
            let until = Instant::now() + Duration::from_secs(3);
            loop {
                assert!(
                    Instant::now() < until,
                    "missing client operation {expected}"
                );
                if !self.remote.stream.ready(100).unwrap() {
                    continue;
                }
                let data = self.remote.receive().unwrap();
                let r = records(&data).unwrap();
                if int32(field(&r, CLIENT_OP).unwrap()).unwrap() == expected {
                    return data;
                }
            }
        }
        fn reply(&mut self, id: u64) {
            self.remote
                .send(
                    &[
                        u32v(SERVER_OP, 45),
                        tlv(
                            0x1334,
                            &[u64v(0x02000340, id), u64v(0x0200030f, 0)].concat(),
                        ),
                    ]
                    .concat(),
                )
                .unwrap();
        }
    }
    impl Drop for Harness {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            // Drain a possible failure notification before joining a full outbox.
            while !self.join.as_ref().unwrap().is_finished() {
                let _ = self.events.recv_timeout(Duration::from_millis(10));
            }
            self.join.take().unwrap().join().unwrap();
        }
    }

    #[test]
    fn attachment_heartbeats_continue_when_engine_event_queue_is_full() {
        let mut h = Harness::new(true);
        h.receive_operation(4);
        h.remote.send(&u32v(SERVER_OP, 38)).unwrap();
        for _ in 0..3 {
            h.receive_operation(4);
        }
        assert!(matches!(h.events.recv().unwrap(), Message::Update(_)));
        assert!(matches!(
            h.events.recv_timeout(Duration::from_secs(2)).unwrap(),
            Message::Membership(_)
        ));
    }

    #[test]
    fn search_timeout_and_late_response_leave_attachment_usable() {
        let mut h = Harness::new(false);
        h.commands
            .send(Command::Search {
                query: "first".into(),
                cursor: 0,
            })
            .unwrap();
        h.receive_operation(43);
        match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
            Message::Update(Update::Operation { message, error }) => {
                assert!(error && message.contains("search timed out"));
            }
            _ => panic!("search timeout must be local to the operation"),
        }
        h.commands
            .send(Command::Search {
                query: "second".into(),
                cursor: 0,
            })
            .unwrap();
        h.receive_operation(43);
        h.reply(101);
        h.reply(102);
        match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
            Message::Update(Update::Catalog { query, .. }) => assert_eq!(query, "second"),
            _ => panic!("late search reply disrupted the attachment"),
        }
        h.receive_operation(4);
    }

    #[test]
    fn uncertain_membership_mutation_still_requires_reattachment() {
        let mut h = Harness::new(false);
        h.commands
            .send(Command::Join("synthetic network".into()))
            .unwrap();
        h.receive_operation(39);
        match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
            Message::ControlFailed(error) => {
                assert!(error.contains("reconnect to reload server membership"))
            }
            _ => panic!("an uncertain mutation must not preserve stale authorization"),
        }
    }

    #[test]
    fn full_command_queue_is_an_operation_error_and_remains_usable() {
        let (tx, rx) = mpsc::sync_channel(1);
        assert!(forward_command(&tx, Command::Join("first".into()))
            .unwrap()
            .is_none());
        assert!(matches!(
            forward_command(&tx, Command::Join("overflow".into())).unwrap(),
            Some(Update::Operation { error: true, .. })
        ));
        assert!(matches!(rx.recv().unwrap(), Command::Join(name) if name == "first"));
        assert!(forward_command(&tx, Command::Join("next".into()))
            .unwrap()
            .is_none());
        drop(rx);
        assert!(forward_command(&tx, Command::Join("closed".into())).is_err());
    }

    #[test]
    fn packet_flood_is_bounded_and_cannot_fill_the_lifecycle_queue() {
        let (lifecycle, notices) = mpsc::sync_channel(1);
        let (packets, frames) = mpsc::sync_channel(8);
        let events = PeerEvents {
            lifecycle,
            packets,
            traffic: Arc::default(),
            diagnostics: Diagnostics::default(),
            attempt: 1,
        };
        for rid in 1..=150 {
            events.packet(rid, &[1, 2, 3]);
        }
        assert_eq!(
            events.traffic.receive_queue_dropped.load(Ordering::Relaxed),
            142
        );
        assert_eq!(events.traffic.dropped.load(Ordering::Relaxed), 142);
        assert_eq!(frames.try_iter().count(), 8);
        events
            .lifecycle
            .try_send(Message::Closed(42, Some("synthetic failure".into())))
            .ok()
            .unwrap();
        assert!(matches!(notices.recv().unwrap(), Message::Closed(42, _)));
    }

    #[test]
    fn server_disconnect_diagnostics_record_reason_without_payload_contents() {
        let directory = std::env::temp_dir().join(format!(
            "openrad-control-log-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        let log = Diagnostics::open(&directory).unwrap();
        let mut h = Harness::with_diagnostics(false, log.new_session());
        h.remote
            .send(
                &[
                    u32v(SERVER_OP, 16),
                    u32v(0x010001d2, 7),
                    tlv(0x0a0001cd, b"synthetic-credential-must-not-be-logged"),
                ]
                .concat(),
            )
            .unwrap();
        assert!(matches!(
            h.events.recv_timeout(Duration::from_secs(3)).unwrap(),
            Message::ControlFailed(_)
        ));
        drop(h);
        drop(log);
        let data = std::fs::read_to_string(directory.join("connection.jsonl")).unwrap();
        assert!(!data.contains("synthetic-credential"));
        let record: serde_json::Value = data
            .lines()
            .map(|line| serde_json::from_str::<serde_json::Value>(line).unwrap())
            .find(|r| r["event"] == "server_disconnect")
            .unwrap();
        assert_eq!(record["details"]["reason_code"], 7);
        assert_eq!(record["session"], 1);
        std::fs::remove_dir_all(directory).unwrap();
    }
}

#[cfg(test)]
mod membership_tests {
    use super::*;

    fn membership() -> Membership {
        let mut members = Membership::default();
        members.networks.insert(
            "n".into(),
            Network {
                name: "network".into(),
                network_id: "n".into(),
            },
        );
        members.peers.insert(
            2,
            Peer {
                rid: 2,
                name: "peer".into(),
                vip: Ipv4Addr::new(26, 0, 0, 2),
                server: Some("192.0.2.1".into()),
                state: 1,
                network_ids: BTreeSet::from(["n".into()]),
            },
        );
        members
    }

    fn worker() -> Worker {
        Worker {
            stop: Arc::new(AtomicBool::new(false)),
            sender: mpsc::sync_channel(1).0,
            join: thread::spawn(|| {}),
            mac: Some([2, 0, 0, 0, 0, 2]),
            incoming: false,
            attempt: 1,
            connected_at: Some(Instant::now()),
        }
    }

    #[test]
    fn large_roster_online_state_refresh_keeps_all_120_channels() {
        let mut members = membership();
        let template = members.peers[&2].clone();
        let mut workers = BTreeMap::new();
        for rid in 2..=151 {
            let mut peer = template.clone();
            peer.rid = rid;
            peer.vip = Ipv4Addr::from(0x1a000000 | rid as u32);
            if rid <= 121 {
                workers.insert(rid, worker());
            } else {
                peer.server = None;
            }
            members.peers.insert(rid, peer);
        }
        let mut snapshot = Snapshot::default();
        let log = Diagnostics::default();
        refresh_membership(&mut snapshot, &members, 1, &workers, &log).unwrap();
        for (&rid, view) in &mut snapshot.peers {
            if workers.contains_key(&rid) {
                view.status = PeerState::Connected;
                view.transport = Some(TransportPath::DirectUdp);
            }
        }
        for state in [5, 1, 5] {
            for peer in members.peers.values_mut() {
                peer.state = state;
            }
            let eligible = refresh_membership(&mut snapshot, &members, 1, &workers, &log).unwrap();
            assert_eq!(eligible.len(), 120);
            assert_eq!(snapshot.peers.len(), 150);
            assert_eq!(
                snapshot
                    .peers
                    .values()
                    .filter(|p| p.status == PeerState::Connected)
                    .count(),
                120
            );
            assert!(workers.values().all(|w| !w.stop.load(Ordering::Relaxed)));
        }
        // A genuine offline update still cancels exactly the affected peer.
        members.peers.get_mut(&2).unwrap().state = 0;
        let eligible = refresh_membership(&mut snapshot, &members, 1, &workers, &log).unwrap();
        assert_eq!(eligible.len(), 119);
        assert!(workers[&2].stop.load(Ordering::Relaxed));
        assert_eq!(
            workers
                .values()
                .filter(|w| w.stop.load(Ordering::Relaxed))
                .count(),
            1
        );
        for worker in workers.into_values() {
            worker.join.join().unwrap();
        }
    }

    #[test]
    fn metadata_update_preserves_connected_status_and_refreshes_ui() {
        let mut members = membership();
        let mut snapshot = Snapshot::default();
        let workers = BTreeMap::from([(2, worker())]);
        assert_eq!(
            refresh_membership(
                &mut snapshot,
                &members,
                1,
                &workers,
                &Diagnostics::default()
            )
            .unwrap(),
            BTreeSet::from([2])
        );
        snapshot.peers.get_mut(&2).unwrap().status = PeerState::Connected;
        snapshot.peers.get_mut(&2).unwrap().transport = Some(TransportPath::Relay);
        members.peers.get_mut(&2).unwrap().name = "renamed".into();
        members.networks.get_mut("n").unwrap().name = "new network name".into();
        refresh_membership(
            &mut snapshot,
            &members,
            1,
            &workers,
            &Diagnostics::default(),
        )
        .unwrap();
        assert_eq!(snapshot.peers[&2].peer.name, "renamed");
        assert_eq!(snapshot.networks[0].name, "new network name");
        assert_eq!(snapshot.peers[&2].status, PeerState::Connected);
        assert_eq!(snapshot.peers[&2].transport, Some(TransportPath::Relay));
        assert!(!workers[&2].stop.load(Ordering::Relaxed));
        workers.into_values().next().unwrap().join.join().unwrap();
    }

    #[test]
    fn binding_changes_cancel_stale_workers_and_leave_removes_forwarding() {
        for change in [
            "vip",
            "server",
            "offline",
            "leave",
            "roster_only",
            "revoked",
        ] {
            let mut members = membership();
            let mut snapshot = Snapshot::default();
            let workers = BTreeMap::from([(2, worker())]);
            refresh_membership(
                &mut snapshot,
                &members,
                1,
                &workers,
                &Diagnostics::default(),
            )
            .unwrap();
            match change {
                "vip" => members.peers.get_mut(&2).unwrap().vip = Ipv4Addr::new(26, 0, 0, 3),
                "server" => members.peers.get_mut(&2).unwrap().server = Some("192.0.2.2".into()),
                "offline" => members.peers.get_mut(&2).unwrap().state = 0,
                "leave" => members.remove_network("n"),
                "roster_only" => members.peers.get_mut(&2).unwrap().server = None,
                "revoked" => {
                    members.roles.insert("n".into(), BTreeMap::from([(2, 0)]));
                }
                _ => unreachable!(),
            }
            let eligible = refresh_membership(
                &mut snapshot,
                &members,
                1,
                &workers,
                &Diagnostics::default(),
            )
            .unwrap();
            assert!(workers[&2].stop.load(Ordering::Relaxed));
            if matches!(change, "offline" | "leave" | "roster_only" | "revoked") {
                assert!(eligible.is_empty());
            }
            if change == "leave" {
                assert!(snapshot.peers.is_empty());
                assert!(snapshot.networks.is_empty());
            }
            workers.into_values().next().unwrap().join.join().unwrap();
        }
    }
}

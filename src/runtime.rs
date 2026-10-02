//! Long-lived application engine. UI adapters send commands and consume snapshots;
//! control I/O, peer handshakes and Ethernet forwarding never run on the UI thread.
use crate::{
    diagnostics::Diagnostics,
    incoming::{Hub, Policy, Setup},
    network::{NetworkOperation, NetworkRequest},
    output::ReportDirectory,
    peer::{PeerChannel, TransportPath},
    protocol::*,
    scheduling::{
        peer_priority, peer_retry_delay, HandshakeBudget, RetryPacer, ADVERTISEMENT_QUEUE,
    },
    session::Session,
    tap::Tap,
    tunnel,
    wake::Wake,
};
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
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
    Search {
        query: String,
        cursor: u64,
    },
    Join(String),
    Leave(String),
    Network(NetworkRequest),
    RetryPeers,
    RetryInterface,
    Ping {
        peer: u64,
    },
    /// Correlate a CLI request with its eventual service acknowledgement.
    Tagged {
        id: u64,
        command: Box<Command>,
    },
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
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
#[derive(Clone, Serialize, Deserialize)]
pub struct PeerView {
    pub peer: Peer,
    pub status: PeerState,
    pub detail: String,
    pub transport: Option<TransportPath>,
}
#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Traffic {
    pub sent_bytes: u64,
    pub received_bytes: u64,
    pub sent_frames: u64,
    pub received_frames: u64,
    pub dropped: u64,
}
#[derive(Clone, Default, Serialize, Deserialize)]
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
    pub retry_queued: usize,
    pub retry_active: usize,
    pub force_relay: bool,
}
#[derive(Clone)]
pub enum Update {
    Ping {
        peer: u64,
        id: Option<u64>,
        rtt_ms: Option<f64>,
        error: Option<String>,
    },
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
    CommandResult {
        id: u64,
        message: String,
        error: bool,
        catalog: Option<(Vec<PublicNetwork>, u64)>,
    },
}
#[derive(Clone, Default)]
pub struct Options {
    /// None permits normal application traffic to all authenticated members.
    /// Some is useful for controlled interoperability testing only.
    pub traffic_peers: Option<BTreeSet<u64>>,
    /// Setup-only executable. Desktop passes the sibling CLI, never the GUI.
    pub helper: Option<std::path::PathBuf>,
    /// Permit a headless control-only session when interface setup is unavailable.
    pub disable_interface: bool,
    pub force_relay: bool,
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
    Pong(u64, u64, f64),
}

pub const PING_TIMEOUT: Duration = Duration::from_millis(3000);
pub const PING_TIMEOUT_MESSAGE: &str = "Peer not responding — they may be using a strict firewall.";
pub const NETWORK_JOIN_DELAY: Duration = Duration::from_millis(50);
const MAX_CONCURRENT_JOINS: usize = 128;

#[derive(Clone, Copy)]
struct PingProbe {
    token: u64,
    started: Instant,
}

/// Packet accounting cannot block peer keepalives behind the engine event queue.
#[derive(Default)]
struct TrafficCounters {
    sent_bytes: AtomicU64,
    sent_frames: AtomicU64,
    dropped: AtomicU64,
    receive_queue_dropped: AtomicU64,
}

/// Publish once per send batch, including progress before an I/O error.
struct TrafficBatch<'a> {
    counters: &'a TrafficCounters,
    sent_bytes: u64,
    sent_frames: u64,
    dropped: u64,
}
impl<'a> TrafficBatch<'a> {
    fn new(counters: &'a TrafficCounters) -> Self {
        Self {
            counters,
            sent_bytes: 0,
            sent_frames: 0,
            dropped: 0,
        }
    }
    fn sent(&mut self, len: usize) {
        self.sent_bytes += len as u64;
        self.sent_frames += 1;
    }
}
impl Drop for TrafficBatch<'_> {
    fn drop(&mut self) {
        if self.sent_frames != 0 {
            self.counters
                .sent_bytes
                .fetch_add(self.sent_bytes, Ordering::Relaxed);
            self.counters
                .sent_frames
                .fetch_add(self.sent_frames, Ordering::Relaxed);
        }
        if self.dropped != 0 {
            self.counters
                .dropped
                .fetch_add(self.dropped, Ordering::Relaxed);
        }
    }
}

/// Retain lifecycle events in order while continuing attachment heartbeats.
/// The control reader pauses while this outbox is full, so memory stays bounded.
struct ControlOutbox {
    sender: SyncSender<Message>,
    pending: VecDeque<Message>,
    wake: Option<Arc<Wake>>,
}
impl ControlOutbox {
    fn push(&mut self, message: Message) {
        self.pending.push_back(message);
    }
    fn flush(&mut self) -> Result<bool> {
        while let Some(message) = self.pending.pop_front() {
            match self.sender.try_send(message) {
                Ok(()) => {
                    if let Some(wake) = &self.wake {
                        wake.notify();
                    }
                }
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
    client_id: Option<u64>,
    until: Instant,
}
fn is_join_command(command: &Command) -> bool {
    match command {
        Command::Join(_) | Command::Network(NetworkRequest::Join { .. }) => true,
        Command::Tagged { command, .. } => is_join_command(command),
        _ => false,
    }
}
struct ControlConfig {
    diagnostics: Diagnostics,
    heartbeat_interval: Duration,
    operation_timeout: Duration,
    wake: Option<Arc<Wake>>,
}
impl Default for ControlConfig {
    fn default() -> Self {
        Self {
            diagnostics: Diagnostics::default(),
            heartbeat_interval: Duration::from_secs(10),
            operation_timeout: Duration::from_secs(20),
            wake: None,
        }
    }
}
fn forward_command(sender: &SyncSender<Command>, command: Command) -> Result<Option<Update>> {
    match sender.try_send(command) {
        Ok(()) => Ok(None),
        Err(mpsc::TrySendError::Full(command)) => Ok(Some(match command {
            Command::Tagged { id, .. } => Update::CommandResult {
                id,
                message: "Too many network commands are waiting. Try again shortly.".into(),
                error: true,
                catalog: None,
            },
            _ => Update::Operation {
                message: "Too many network commands are waiting. Try again shortly.".into(),
                error: true,
            },
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
        wake: config.wake.clone(),
    };
    let result = (|| -> Result<()> {
        let mut next_id = 100;
        let mut sequence = 0u32;
        let mut pending: Vec<Pending> = Vec::new();
        let mut deferred = None;
        let mut next_join = Instant::now();
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
                    "pending_events": outbox.pending.len(), "operation_pending": !pending.is_empty(),
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
            for bytes in wire.try_iter().take(32) {
                session
                    .send(&bytes)
                    .context("sending incoming peer advertisement")?;
                if Instant::now() >= heartbeat {
                    break;
                }
            }
            if deferred.is_none() && pending.len() < MAX_CONCURRENT_JOINS {
                deferred = commands.try_recv().ok();
            }
            if deferred.as_ref().is_some_and(|command| {
                let join = is_join_command(command);
                (pending.is_empty()
                    || (join
                        && pending
                            .iter()
                            .all(|p| matches!(&p.kind, PendingKind::Network(n) if n.is_join()))))
                    && (!join || Instant::now() >= next_join)
                    && pending.len() < MAX_CONCURRENT_JOINS
            }) {
                if let Some(command) = deferred.take() {
                    let (client_id, command) = match command {
                        Command::Tagged { id, command } => (Some(id), *command),
                        command => (None, command),
                    };
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
                            if matches!(&kind, PendingKind::Network(n) if n.is_join()) {
                                next_join = Instant::now() + NETWORK_JOIN_DELAY;
                            }
                            diagnostics.event("control_operation_started", json!({
                                "request_id": next_id,
                                "kind": if matches!(&kind, PendingKind::Search { .. }) { "search" } else { "network" },
                            }));
                            pending.push(Pending {
                                kind,
                                id: next_id,
                                client_id,
                                until: Instant::now() + config.operation_timeout,
                            });
                        }
                        Err(error) => {
                            outbox.push(Message::Update(match client_id {
                                Some(id) => Update::CommandResult {
                                    id,
                                    message: error.to_string(),
                                    error: true,
                                    catalog: None,
                                },
                                None => Update::Operation {
                                    message: error.to_string(),
                                    error: true,
                                },
                            }));
                        }
                    }
                }
            }
            if let Some(index) = pending.iter().position(|p| Instant::now() >= p.until) {
                let expired = pending.remove(index);
                diagnostics.event("control_operation_timeout", json!({
                    "request_id": expired.id,
                    "kind": if matches!(&expired.kind, PendingKind::Search { .. }) { "search" } else { "network" },
                }));
                if matches!(expired.kind, PendingKind::Search { .. }) {
                    let message =
                        "Public network search timed out. You can retry the search.".to_owned();
                    outbox.push(Message::Update(match expired.client_id {
                        Some(id) => Update::CommandResult {
                            id,
                            message,
                            error: true,
                            catalog: None,
                        },
                        None => Update::Operation {
                            message,
                            error: true,
                        },
                    }));
                    continue;
                }
                // A timed-out mutation has an unknown remote outcome. Reattach before
                // allowing more commands; never optimistically report a successful leave.
                bail!("network operation timed out; reconnect to reload server membership");
            }
            let poll_ms =
                if deferred.as_ref().is_some_and(is_join_command) && Instant::now() < next_join {
                    next_join
                        .saturating_duration_since(Instant::now())
                        .as_millis()
                        .clamp(1, 20) as i32
                } else {
                    20
                };
            if !session
                .stream
                .ready(poll_ms)
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
            let auth_target = if operation == 40 {
                let mut targets = Vec::new();
                for (index, p) in pending.iter().enumerate() {
                    if let PendingKind::Network(network) = &p.kind {
                        if network.accepts_auth_reply(&data)? {
                            targets.push(index);
                        }
                    }
                }
                anyhow::ensure!(targets.len() <= 1, "Ambiguous network password reply; reconnect and join private networks individually");
                let Some(index) = targets.first().copied() else {
                    continue;
                };
                Some(index)
            } else {
                None
            };
            let mut index = 0;
            while index < pending.len() {
                if auth_target.is_some_and(|target| target != index) {
                    index += 1;
                    continue;
                }
                let p = &mut pending[index];
                let mut complete = None;
                let mut catalog = None;
                match &mut p.kind {
                    PendingKind::Search { query, cursor } if operation == 45 => {
                        if listing_id(&data)? != p.id {
                            diagnostics.event(
                                "stale_search_reply",
                                json!({"request_id": listing_id(&data)?, "expected_id": p.id}),
                            );
                            index += 1;
                            continue;
                        }
                        let (networks, next) = listing(&data, p.id)?;
                        if p.client_id.is_some() {
                            catalog = Some((networks, next));
                        } else {
                            outbox.push(Message::Update(Update::Catalog {
                                query: query.clone(),
                                networks,
                                cursor: next,
                                append: *cursor != 0,
                            }));
                        }
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
                    outbox.push(Message::Update(match p.client_id {
                        Some(id) => Update::CommandResult {
                            id,
                            message,
                            error,
                            catalog,
                        },
                        None => Update::Operation { message, error },
                    }));
                    pending.remove(index);
                } else {
                    index += 1;
                }
                if auth_target.is_some() {
                    break;
                }
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
        if let Some(wake) = &config.wake {
            wake.notify();
        }
    }
}

struct Worker {
    stop: Arc<AtomicBool>,
    sender: SyncSender<Arc<Vec<u8>>>,
    ping: SyncSender<PingProbe>,
    join: JoinHandle<()>,
    mac: Option<[u8; 6]>,
    incoming: bool,
    attempt: u64,
    connected_at: Option<Instant>,
    wake: Arc<Wake>,
}
impl Worker {
    fn queue(&self, frame: Arc<Vec<u8>>) -> bool {
        if self.sender.try_send(frame).is_err() {
            return false;
        }
        self.wake.notify();
        true
    }
    fn cancel(&self) -> bool {
        let changed = !self.stop.swap(true, Ordering::Relaxed);
        self.wake.notify();
        changed
    }
}

#[derive(Default)]
struct ForwardingTable {
    all: Vec<(u64, Ipv4Addr)>,
    by_ip: BTreeMap<Ipv4Addr, Vec<(u64, Ipv4Addr)>>,
}
impl ForwardingTable {
    fn rebuild(&mut self, membership: &Membership, eligible: &BTreeSet<u64>) {
        self.all.clear();
        self.by_ip.clear();
        for (&rid, peer) in &membership.peers {
            if eligible.contains(&rid) {
                let binding = (rid, peer.vip);
                self.all.push(binding);
                self.by_ip.entry(peer.vip).or_default().push(binding);
            }
        }
    }
    fn targets(&self, route: &tunnel::Forwarding) -> &[(u64, Ipv4Addr)] {
        route.target().map_or(&self.all, |ip| {
            self.by_ip.get(&ip).map_or(&[], Vec::as_slice)
        })
    }
}

fn forward_frame(
    frame: Vec<u8>,
    vip: Ipv4Addr,
    workers: &BTreeMap<u64, Worker>,
    table: &ForwardingTable,
    options: &Options,
) -> u64 {
    let Some(route) = tunnel::forwarding(&frame, vip, tunnel::mac(vip)) else {
        return 1;
    };
    let frame = Arc::new(frame);
    let mut forwarded = false;
    let mut dropped = 0;
    for &(rid, peer_ip) in table.targets(&route) {
        if !options.allows(rid) {
            continue;
        }
        let Some(worker) = workers
            .get(&rid)
            .filter(|w| !w.stop.load(Ordering::Relaxed))
        else {
            continue;
        };
        let Some(mac) = worker.mac else {
            continue;
        };
        if route.deliver_to(peer_ip, mac) {
            if worker.queue(Arc::clone(&frame)) {
                forwarded = true;
            } else {
                dropped += 1;
            }
        }
    }
    dropped + u64::from(!forwarded)
}
struct PeerRetry {
    failures: u32,
    at: Instant,
    previously_connected: bool,
}

fn announce_address<'a>(
    vip: Ipv4Addr,
    workers: impl Iterator<Item = (&'a u64, &'a Worker)>,
    eligible: &BTreeSet<u64>,
    options: &Options,
) -> u64 {
    let frame = Arc::new(tunnel::gratuitous_arp(vip));
    let mut dropped = 0;
    for (&rid, worker) in workers {
        if worker.mac.is_some()
            && !worker.stop.load(Ordering::Relaxed)
            && eligible.contains(&rid)
            && options.allows(rid)
        {
            let queued = worker.queue(Arc::clone(&frame));
            dropped += u64::from(!queued);
            options.diagnostics.event(
                "peer_address_announcement",
                json!({
                    "rid": rid, "attempt": worker.attempt, "queued": queued,
                }),
            );
        }
    }
    dropped
}

fn schedule_peer_retry(
    retries: &mut BTreeMap<u64, PeerRetry>,
    rid: u64,
    error: Option<&str>,
    was_connected: bool,
    stable_connection: bool,
    now: Instant,
) -> Option<Duration> {
    if error.is_none() {
        retries.remove(&rid);
        return None;
    }
    // A refusal during the initial rush is not necessarily permanent. Retry it
    // through the same slow queue; eligibility and full authentication still apply.
    let retry = retries.entry(rid).or_insert(PeerRetry {
        failures: 0,
        at: now,
        previously_connected: was_connected,
    });
    retry.previously_connected |= was_connected;
    if stable_connection {
        retry.failures = 0;
    }
    retry.failures = retry.failures.saturating_add(1);
    let delay = peer_retry_delay(rid, retry.failures);
    retry.at = now + delay;
    Some(delay)
}

fn retry_detail(detail: &str, wait: Duration) -> String {
    format!(
        "{} · Retry queued (at least {}s; adaptive recovery)",
        detail.split(" · Retry").next().unwrap_or(detail),
        wait.as_secs()
            .saturating_add(u64::from(wait.subsec_nanos() != 0))
    )
}

fn request_peer_retries(
    snapshot: &mut Snapshot,
    workers: &BTreeMap<u64, Worker>,
    retries: &mut BTreeMap<u64, PeerRetry>,
    eligible: &BTreeSet<u64>,
    now: Instant,
) -> usize {
    let mut scheduled = 0;
    for (&rid, peer) in &mut snapshot.peers {
        if matches!(peer.status, PeerState::Failed | PeerState::Refused)
            && eligible.contains(&rid)
            && !workers.contains_key(&rid)
        {
            let requested = now + peer_retry_delay(rid, 1);
            let retry = retries.entry(rid).or_insert(PeerRetry {
                failures: 1,
                at: requested,
                previously_connected: false,
            });
            // Repeated clicks neither postpone queued work nor erase backoff
            // history. Manual recovery still obeys the global retry pacer.
            retry.at = retry.at.min(requested);
            peer.detail = retry_detail(&peer.detail, retry.at.saturating_duration_since(now));
            scheduled += 1;
        }
    }
    scheduled
}

struct PeerEvents {
    lifecycle: SyncSender<Message>,
    packets: SyncSender<(u64, tunnel::OwnedFrame)>,
    traffic: Arc<TrafficCounters>,
    diagnostics: Diagnostics,
    attempt: u64,
    wake: Arc<Wake>,
}
impl PeerEvents {
    fn packet(&self, rid: u64, frame: tunnel::OwnedFrame) {
        if self.packets.try_send((rid, frame)).is_err() {
            self.traffic.dropped.fetch_add(1, Ordering::Relaxed);
            self.traffic
                .receive_queue_dropped
                .fetch_add(1, Ordering::Relaxed);
        } else {
            self.wake.notify();
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
    frames: Receiver<Arc<Vec<u8>>>,
    pings: Receiver<PingProbe>,
    incoming: Option<Setup>,
    force_relay: bool,
    wake: Arc<Wake>,
) {
    let rid = peer.rid;
    let started = Instant::now();
    let mut connected_at = None;
    let mut activity = PeerActivity::default();
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
                    force_relay,
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
            events.wake.notify();
            forward_peer(
                &mut channel,
                &events,
                frames,
                pings,
                &stop,
                &wake,
                &mut activity,
            )?;
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
            "last_receive_age_ms": activity.last_receive.map(|t| t.elapsed().as_millis()),
            "keepalives_sent": activity.keepalives, "keepalive_replies": activity.keepalive_replies,
        }),
    );
    let _ = events.lifecycle.send(Message::Closed(rid, error));
    events.wake.notify();
}

fn expire_pings(pings: &mut BTreeMap<u64, (PingProbe, Option<u64>)>, now: Instant) -> Vec<Update> {
    let expired: Vec<_> = pings
        .iter()
        .filter(|(_, (ping, _))| now.saturating_duration_since(ping.started) >= PING_TIMEOUT)
        .map(|(peer, _)| *peer)
        .collect();
    expired
        .into_iter()
        .map(|peer| {
            let (_, id) = pings.remove(&peer).unwrap();
            Update::Ping {
                peer,
                id,
                rtt_ms: None,
                error: Some(PING_TIMEOUT_MESSAGE.into()),
            }
        })
        .collect()
}

#[derive(Default)]
struct PeerActivity {
    last_receive: Option<Instant>,
    keepalives: u64,
    keepalive_replies: u64,
}

fn forward_peer(
    channel: &mut PeerChannel,
    events: &PeerEvents,
    frames: Receiver<Arc<Vec<u8>>>,
    pings: Receiver<PingProbe>,
    stop: &AtomicBool,
    wake: &Wake,
    activity: &mut PeerActivity,
) -> Result<()> {
    let rid = channel.peer.rid;
    let mut heartbeat = Instant::now();
    let mut sequence = 0u32;
    let mut probe: Option<(PingProbe, Option<(u32, Instant)>)> = None;
    let mut send_buffer = Vec::with_capacity((10 + tunnel::MAX_FRAME + 9).div_ceil(16) * 16);
    while !stop.load(Ordering::Relaxed) {
        wake.clear();
        if probe.is_some_and(|(p, _)| p.started.elapsed() >= PING_TIMEOUT) {
            probe = None;
        }
        if let Ok(ping) = pings.try_recv() {
            if ping.started.elapsed() < PING_TIMEOUT {
                probe = Some((ping, None));
            }
        }
        if let Some((_, probe_sequence)) = probe.as_mut() {
            if probe_sequence.is_none() {
                sequence = sequence.wrapping_add(1);
                if channel.send(&tunnel::keepalive(sequence, false))? {
                    *probe_sequence = Some((sequence, Instant::now()));
                }
            }
        }
        if Instant::now() >= heartbeat {
            sequence = sequence.wrapping_add(1);
            if channel.send(&tunnel::keepalive(sequence, false))? {
                activity.keepalives += 1;
                heartbeat = Instant::now() + Duration::from_secs(15);
            } else {
                heartbeat = Instant::now() + Duration::from_millis(200);
            }
        }
        let send_started = Instant::now();
        let mut sent = 0;
        {
            let mut batch = TrafficBatch::new(&events.traffic);
            for frame in frames.try_iter().take(32) {
                sent += 1;
                if channel.send_frame(&frame, &mut send_buffer)? {
                    batch.sent(frame.len());
                } else {
                    batch.dropped += 1;
                }
                if send_started.elapsed() >= Duration::from_millis(20) {
                    break;
                }
            }
        }
        let busy = sent == 32 || send_started.elapsed() >= Duration::from_millis(20);
        let wait_ms = if busy {
            0
        } else {
            heartbeat
                .min(probe.map_or(heartbeat, |(p, sent)| {
                    if sent.is_some() {
                        p.started + PING_TIMEOUT
                    } else {
                        Instant::now() + Duration::from_millis(10)
                    }
                }))
                .saturating_duration_since(Instant::now())
                .as_nanos()
                .div_ceil(1_000_000)
                .min(i32::MAX as u128) as i32
        };
        if !channel.stream.ready_or_wake(wait_ms, wake)? {
            continue;
        }
        let data = channel.receive()?;
        activity.last_receive = Some(Instant::now());
        match tunnel::decode_owned(data)? {
            tunnel::OwnedPacket::Keepalive {
                sequence,
                reply: false,
            } => {
                channel.send(&tunnel::keepalive(sequence, true))?;
            }
            tunnel::OwnedPacket::Keepalive {
                reply: true,
                sequence,
            } => {
                activity.keepalive_replies += 1;
                if let Some((ping, Some((expected, sent_at)))) = probe {
                    if sequence == expected && ping.started.elapsed() < PING_TIMEOUT {
                        events.lifecycle.send(Message::Pong(
                            rid,
                            ping.token,
                            sent_at.elapsed().as_secs_f64() * 1000.0,
                        ))?;
                        events.wake.notify();
                        probe = None;
                    }
                }
            }
            tunnel::OwnedPacket::Frames(frames) => {
                for frame in frames {
                    events.packet(rid, frame);
                }
            }
            _ => {}
        }
    }
    Ok(())
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
        let view = match snapshot.peers.entry(p.rid) {
            std::collections::btree_map::Entry::Vacant(entry) => {
                entry.insert(PeerView {
                    peer: p.clone(),
                    status: base_peer_state(p),
                    detail: String::new(),
                    transport: None,
                });
                continue;
            }
            std::collections::btree_map::Entry::Occupied(entry) => entry.into_mut(),
        };
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
                if w.cancel() {
                    diagnostics.event("peer_cancelled", json!({
                        "rid": p.rid, "attempt": w.attempt, "reason": "membership_binding_changed",
                        "old_state": view.peer.state, "new_state": p.state,
                        "old_vip": view.peer.vip, "new_vip": p.vip,
                        "old_server": view.peer.server, "new_server": p.server,
                    }));
                }
            }
        }
        // Derived Clone on Peer replaces the whole struct. Refresh fields
        // directly to reuse strings and leave unchanged memberships allocated.
        view.peer.rid = p.rid;
        view.peer.name.clone_from(&p.name);
        view.peer.vip = p.vip;
        view.peer.server.clone_from(&p.server);
        view.peer.state = p.state;
        if view.peer.network_ids != p.network_ids {
            view.peer.network_ids.clone_from(&p.network_ids);
        }
    }
    let eligible: BTreeSet<_> = membership
        .eligible_refs(own_rid, &[])?
        .map(|p| p.rid)
        .collect();
    for (rid, w) in workers {
        if !eligible.contains(rid) && w.cancel() {
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
        force_relay: options.force_relay,
        ..Default::default()
    };
    let (tx, rx) = mpsc::sync_channel(512);
    let wake = Arc::new(Wake::new()?);
    let (packet_tx, packets) = mpsc::sync_channel::<(u64, tunnel::OwnedFrame)>(512);
    let traffic = Arc::new(TrafficCounters::default());
    let (control_tx, control_rx) = mpsc::sync_channel(8);
    let (wire_tx, wire_rx) = mpsc::sync_channel(ADVERTISEMENT_QUEUE);
    let mut incoming = Hub::new(
        session.stream.socket.local_addr()?.ip(),
        session.ues.clone(),
        wire_tx,
    );
    let (events, control_stop, members) = (tx.clone(), stop.clone(), membership.clone());
    let control_log = diagnostics.clone();
    let control_wake = wake.clone();
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
                wake: Some(control_wake),
                ..Default::default()
            },
        )
    });
    let modulus = Arc::new(modulus);
    let mut workers: BTreeMap<u64, Worker> = BTreeMap::new();
    let mut pings: BTreeMap<u64, (PingProbe, Option<u64>)> = BTreeMap::new();
    let mut ping_token = 0u64;
    let mut retired = Vec::new();
    let mut tap: Option<Tap> = None;
    let mut attempted_interface = false;
    let mut interface_worker: Option<JoinHandle<Result<Tap>>> = None;
    let started = Instant::now();
    let mut next_report = Instant::now();
    let mut membership_changed = true;
    let mut eligible = BTreeSet::new();
    let mut forwarding_table = ForwardingTable::default();
    let mut retries: BTreeMap<u64, PeerRetry> = BTreeMap::new();
    let mut retry_pacer = RetryPacer::new(Instant::now());
    let mut attempt = 0u64;
    let mut next_health = Instant::now();
    let mut previous_loop = Instant::now();
    let mut max_loop_gap_ms = 0u128;
    let mut previous_connected = 0usize;
    let mut previous_peer_drops = 0;
    let mut schedule_dirty = true;
    let mut next_schedule = Instant::now();
    let mut pending = BTreeSet::new();
    let mut queued = Vec::new();
    let mut due_retries = 0;
    let mut retry_policy = retry_pacer.policy(Instant::now(), 0);
    let result = (|| -> Result<()> {
        loop {
            wake.clear();
            if stop.load(Ordering::Relaxed) {
                break;
            }
            max_loop_gap_ms = max_loop_gap_ms.max(previous_loop.elapsed().as_millis());
            previous_loop = Instant::now();
            let command = commands.try_recv();
            let command_received = command.is_ok();
            if command_received {
                schedule_dirty = true;
            }
            let (client_id, command) = match command {
                Ok(Command::Tagged { id, command }) => (Some(id), Ok(*command)),
                other => (None, other),
            };
            match command {
                Ok(Command::Ping { peer }) => {
                    let error = match pings.entry(peer) {
                        std::collections::btree_map::Entry::Occupied(_) => {
                            Some("A ping is already running for this peer.")
                        }
                        std::collections::btree_map::Entry::Vacant(entry) => {
                            if let Some(worker) = workers.get(&peer).filter(|w| w.mac.is_some()) {
                                ping_token = ping_token.wrapping_add(1);
                                let probe = PingProbe {
                                    token: ping_token,
                                    started: Instant::now(),
                                };
                                if worker.ping.try_send(probe).is_ok() {
                                    entry.insert((probe, client_id));
                                    worker.wake.notify();
                                    None
                                } else {
                                    Some("Peer channel is unavailable.")
                                }
                            } else {
                                Some("Connect to this peer before testing RTT.")
                            }
                        }
                    };
                    if let Some(error) = error {
                        report(Update::Ping {
                            peer,
                            id: client_id,
                            rtt_ms: None,
                            error: Some(error.into()),
                        });
                    }
                }
                Ok(Command::RetryInterface) => {
                    if interface_worker.is_none() {
                        attempted_interface = false;
                    }
                }
                Ok(Command::RetryPeers) => {
                    let count = request_peer_retries(
                        &mut snapshot,
                        &workers,
                        &mut retries,
                        &eligible,
                        Instant::now(),
                    );
                    report(Update::Operation {
                        message: format!("{count} peer retries queued with adaptive recovery."),
                        error: false,
                    });
                }
                Ok(c) => {
                    let c = match client_id {
                        Some(id) => Command::Tagged {
                            id,
                            command: Box::new(c),
                        },
                        None => c,
                    };
                    if let Some(update) = forward_command(&control_tx, c)? {
                        diagnostics.event("control_command_queue_full", json!({}));
                        report(update);
                    }
                }
                Err(mpsc::TryRecvError::Disconnected) => break,
                Err(mpsc::TryRecvError::Empty) => {}
            }
            for update in expire_pings(&mut pings, Instant::now()) {
                report(update);
            }
            let mut new_channels = BTreeSet::new();
            for message in rx.try_iter().take(512) {
                schedule_dirty = true;
                match message {
                    Message::Pong(peer, token, rtt_ms) => {
                        if pings.get(&peer).is_some_and(|(p, _)| {
                            p.token == token && p.started.elapsed() < PING_TIMEOUT
                        }) {
                            let (_, id) = pings.remove(&peer).unwrap();
                            report(Update::Ping {
                                peer,
                                id,
                                rtt_ms: Some(rtt_ms),
                                error: None,
                            });
                        }
                    }
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
                        if retries.contains_key(&rid) {
                            retry_pacer.record_outcome(Instant::now(), true);
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
                        new_channels.insert(rid);
                    }
                    Message::Closed(rid, error) => {
                        incoming.finish(rid);
                        let worker = workers.remove(&rid);
                        let cancelled = worker
                            .as_ref()
                            .is_some_and(|w| w.stop.load(Ordering::Relaxed));
                        let error = if cancelled { None } else { error };
                        let stable_connection = worker
                            .as_ref()
                            .and_then(|w| w.connected_at)
                            .is_some_and(|t| t.elapsed() >= Duration::from_secs(60));
                        let was_connected =
                            worker.as_ref().is_some_and(|w| w.connected_at.is_some());
                        if error.is_some()
                            && worker.as_ref().is_some_and(|w| w.mac.is_none())
                            && retries.contains_key(&rid)
                        {
                            retry_pacer.record_outcome(Instant::now(), false);
                        }
                        if let Some(delay) = schedule_peer_retry(
                            &mut retries,
                            rid,
                            error.as_deref(),
                            was_connected,
                            stable_connection,
                            Instant::now(),
                        ) {
                            diagnostics.event("peer_retry_scheduled", json!({"rid": rid, "failures": retries[&rid].failures, "delay_ms": delay.as_millis(), "error": error}));
                        }
                        if let Some(p) = snapshot.peers.get_mut(&rid) {
                            p.status = error
                                .as_ref()
                                .map(|e| failure_state(e))
                                .unwrap_or_else(|| base_peer_state(&p.peer));
                            p.detail = error.unwrap_or_else(|| "Channel closed".into());
                            if let Some(retry) = retries.get(&rid) {
                                p.detail = retry_detail(
                                    &p.detail,
                                    retry.at.saturating_duration_since(Instant::now()),
                                );
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
                forwarding_table.rebuild(&membership, &eligible);
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
            if schedule_dirty || Instant::now() >= next_schedule {
                schedule_dirty = false;
                next_schedule = Instant::now() + Duration::from_millis(50);
                incoming.expire();
                pending = incoming.pending_rids();
                for rid in &pending {
                    if let Some(w) = workers.get(rid) {
                        if w.mac.is_some() {
                            incoming.reject(*rid);
                        } else if !w.incoming && identity.rid > *rid && w.cancel() {
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
                let now = Instant::now();
                let mut retrying = workers
                    .iter()
                    .filter(|(rid, w)| w.mac.is_none() && !w.incoming && retries.contains_key(rid))
                    .count();
                queued.clear();
                queued.extend(
                    snapshot
                        .peers
                        .values()
                        .filter(|p| {
                            (pending.contains(&p.peer.rid)
                                || retries
                                    .get(&p.peer.rid)
                                    .map_or(p.status == PeerState::Online, |retry| now >= retry.at))
                                && eligible.contains(&p.peer.rid)
                                && !workers.contains_key(&p.peer.rid)
                        })
                        .map(|p| p.peer.rid),
                );
                due_retries = queued
                    .iter()
                    .filter(|rid| !pending.contains(rid) && retries.contains_key(rid))
                    .count();
                retry_policy = retry_pacer.policy(now, due_retries + retrying);
                let mut remaining_retries = due_retries;
                queued.sort_by_key(|rid| {
                    let retry = retries.get(rid);
                    peer_priority(
                        pending.contains(rid),
                        retry.is_some(),
                        retry.is_some_and(|r| r.previously_connected),
                        options.allows(*rid),
                        retry.map_or(0, |retry| retry.failures),
                        *rid,
                    )
                });
                for rid in queued.iter().copied() {
                    let is_incoming = pending.contains(&rid);
                    let is_retry = !is_incoming && retries.contains_key(&rid);
                    if is_retry && !retry_pacer.ready(now, retrying, retry_policy) {
                        continue;
                    }
                    let reserve = if is_retry || is_incoming {
                        0
                    } else {
                        remaining_retries.min(retry_policy.active_limit.saturating_sub(retrying))
                    };
                    if !budget.try_start_with_reserve(is_incoming, reserve) {
                        continue;
                    }
                    if is_retry {
                        retry_pacer.started(now, retry_policy);
                        retrying += 1;
                        remaining_retries -= 1;
                    }
                    let peer = snapshot.peers[&rid].peer.clone();
                    let setup = incoming.take(
                        rid,
                        if options.force_relay {
                            Policy::Relay
                        } else {
                            Policy::All
                        },
                    );
                    let is_incoming = setup.is_some();
                    snapshot.peers.get_mut(&rid).unwrap().status = PeerState::Connecting;
                    let (sender, frames) = mpsc::sync_channel(64);
                    let (ping, probes) = mpsc::sync_channel(1);
                    let peer_stop = Arc::new(AtomicBool::new(false));
                    let peer_wake = Arc::new(Wake::new()?);
                    attempt += 1;
                    diagnostics.event("peer_connecting", json!({"rid": rid, "attempt": attempt, "incoming": is_incoming, "server": peer.server}));
                    let (id, key, cancel) = (identity.clone(), modulus.clone(), peer_stop.clone());
                    let events = PeerEvents {
                        lifecycle: tx.clone(),
                        packets: packet_tx.clone(),
                        traffic: traffic.clone(),
                        diagnostics: diagnostics.clone(),
                        attempt,
                        wake: wake.clone(),
                    };
                    let notify = peer_wake.clone();
                    let force_relay = options.force_relay;
                    let join = thread::spawn(move || {
                        peer_loop(
                            id,
                            key,
                            vip,
                            peer,
                            cancel,
                            events,
                            frames,
                            probes,
                            setup,
                            force_relay,
                            notify,
                        )
                    });
                    workers.insert(
                        rid,
                        Worker {
                            stop: peer_stop,
                            sender,
                            ping,
                            join,
                            mac: None,
                            incoming: is_incoming,
                            attempt,
                            connected_at: None,
                            wake: peer_wake,
                        },
                    );
                }
            }
            if !attempted_interface && !options.disable_interface && interface_worker.is_none() {
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
                let interface_stop = stop.clone();
                interface_worker = Some(thread::spawn(move || {
                    #[cfg(target_os = "linux")]
                    {
                        Tap::create_lan_with_helper_cancellable(vip, &helper, &interface_stop)
                    }
                    #[cfg(not(target_os = "linux"))]
                    {
                        let _ = interface_stop;
                        Tap::create_lan_with_helper(vip, &helper)
                    }
                }));
                attempted_interface = true;
            }
            if interface_worker
                .as_ref()
                .is_some_and(|worker| worker.is_finished())
            {
                match interface_worker
                    .take()
                    .unwrap()
                    .join()
                    .unwrap_or_else(|_| Err(anyhow::anyhow!("TAP setup worker failed")))
                {
                    Ok(t) => {
                        tap = Some(t);
                        snapshot.interface_ready = true;
                        diagnostics.event("interface_ready", json!({}));
                        snapshot.traffic.dropped +=
                            announce_address(vip, workers.iter(), &eligible, &options);
                        new_channels.clear();
                    }
                    Err(e) => {
                        snapshot.interface_error = Some(format!("{e:#}"));
                        diagnostics.event("interface_failed", json!({"error": format!("{e:#}")}));
                    }
                }
                next_report = Instant::now();
            }
            if options.disable_interface {
                attempted_interface = true;
            }
            if let Some(t) = tap.as_mut() {
                if !new_channels.is_empty() {
                    snapshot.traffic.dropped += announce_address(
                        vip,
                        workers.iter().filter(|(rid, _)| new_channels.contains(rid)),
                        &eligible,
                        &options,
                    );
                }
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
                        snapshot.traffic.dropped +=
                            forward_frame(frame, vip, &workers, &forwarding_table, &options);
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
                snapshot.retry_queued = retries
                    .keys()
                    .filter(|rid| eligible.contains(rid) && !workers.contains_key(rid))
                    .count();
                snapshot.retry_active = workers
                    .iter()
                    .filter(|(rid, w)| w.mac.is_none() && !w.incoming && retries.contains_key(rid))
                    .count();
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
                        "pending_incoming": pending.len(), "scheduled_retries": snapshot.retry_queued,
                        "due_retries": due_retries, "active_retries": snapshot.retry_active,
                        "retry_capacity": retry_policy.active_limit,
                        "retry_start_interval_ms": retry_policy.start_interval.as_millis(),
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
            let wait = if command_received {
                Duration::ZERO
            } else {
                next_report
                    .min(next_schedule)
                    .saturating_duration_since(Instant::now())
                    .min(Duration::from_millis(50))
            };
            wake.wait(tap.as_ref().and_then(Tap::poll_fd), wait)?;
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
    if let Some(worker) = interface_worker {
        let _ = worker.join(); // Drops any interface completed during shutdown.
    }
    for worker in workers.values() {
        worker.cancel();
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

    #[test]
    fn two_rid_member_events_keep_the_attachment_and_heartbeats_alive() {
        let mut h = Harness::new(false);
        for tag in [0x1318, 0x131d] {
            let change = [
                u32v(SERVER_OP, 41),
                tlv(
                    0x131f,
                    &tlv(
                        tag,
                        &[
                            tlv(0x0d000309, &[7; 16]),
                            u64v(0x020001e1, 11),
                            u64v(0x020001e1, 22),
                            u32v(0x0100030a, 2),
                        ]
                        .concat(),
                    ),
                ),
            ]
            .concat();
            h.remote.send(&change).unwrap();
            match h.events.recv_timeout(Duration::from_secs(2)).unwrap() {
                Message::Membership(m) => assert_eq!(
                    m.role(&hex::encode([7; 16]), 11),
                    if tag == 0x1318 { Some(2) } else { None }
                ),
                _ => panic!("valid two-RID event must update membership without closing control"),
            }
        }
        h.receive_operation(4);
        assert!(!h.join.as_ref().unwrap().is_finished());
    }

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
            Self::with_timeout(block_events, diagnostics, Duration::from_millis(300))
        }
        fn with_timeout(
            block_events: bool,
            diagnostics: Diagnostics,
            operation_timeout: Duration,
        ) -> Self {
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
                        operation_timeout,
                        wake: None,
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
    fn join_acknowledgement(id: u64, name: &str, guid: [u8; 16]) -> Vec<u8> {
        [
            u32v(SERVER_OP, 37),
            tlv(
                0x131a,
                &[
                    u32v(0x0100030c, 2),
                    u64v(0x02000340, id),
                    tlv(
                        0x1316,
                        &tlv(
                            0x1315,
                            &[
                                tlv(0x0d000309, &guid),
                                textv(0x03000306, name).unwrap(),
                                u32v(0x0100030a, 1),
                            ]
                            .concat(),
                        ),
                    ),
                ]
                .concat(),
            ),
        ]
        .concat()
    }
    fn join_approval(guid: [u8; 16]) -> Vec<u8> {
        [
            u32v(SERVER_OP, 42),
            tlv(
                0x131f,
                &tlv(
                    0x131e,
                    &[tlv(0x0d000309, &guid), u64v(0x020001e1, 2)].concat(),
                ),
            ),
        ]
        .concat()
    }
    #[test]
    fn overlapping_public_joins_are_paced_and_acknowledged_out_of_order() {
        let mut h = Harness::with_timeout(false, Diagnostics::default(), Duration::from_secs(3));
        for (id, name) in [(21, "First LAN"), (22, "Second LAN")] {
            h.commands
                .send(Command::Tagged {
                    id,
                    command: Box::new(Command::Join(name.into())),
                })
                .unwrap();
        }
        h.receive_operation(39);
        let first = Instant::now();
        h.receive_operation(39);
        assert!(
            first.elapsed() >= Duration::from_millis(45),
            "join starts must be spaced by 50 ms (allowing socket observation jitter)"
        );
        // Both requests are on the wire before either server response arrives.
        for (id, name, guid) in [(102, "Second LAN", [2; 16]), (101, "First LAN", [1; 16])] {
            h.remote
                .send(&join_acknowledgement(id, name, guid))
                .unwrap();
            h.remote.send(&join_approval(guid)).unwrap();
        }
        let mut results = Vec::new();
        let mut membership = Membership::default();
        while results.len() < 2 {
            match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
                Message::Membership(m) => membership = m,
                Message::Update(Update::CommandResult {
                    id, message, error, ..
                }) => {
                    assert!(!error);
                    results.push((id, message));
                }
                _ => panic!("concurrent joins disrupted the attachment"),
            }
        }
        assert_eq!(
            results,
            [
                (22, "Joined Second LAN".into()),
                (21, "Joined First LAN".into())
            ]
        );
        assert_eq!(membership.networks.len(), 2);
        h.receive_operation(4);
    }
    #[test]
    fn overlapping_private_joins_keep_password_handshakes_correlated() {
        use crate::{
            crypto::ShServer,
            network::{network_identity, NetworkPassword},
        };
        let mut h = Harness::with_timeout(false, Diagnostics::default(), Duration::from_secs(5));
        for (id, name) in [(31, "Private one"), (32, "Private two")] {
            h.commands
                .send(Command::Tagged {
                    id,
                    command: Box::new(Command::Network(NetworkRequest::Join {
                        name: name.into(),
                        password: Some(NetworkPassword::new("synthetic password".into()).unwrap()),
                    })),
                })
                .unwrap();
        }
        let hello_one = h.receive_operation(39);
        let hello_two = h.receive_operation(39);
        let blob = |data: &[u8]| {
            field(
                &records(field(&records(data).unwrap(), 0x131c).unwrap()).unwrap(),
                0x0a00030e,
            )
            .unwrap()
            .to_vec()
        };
        let mut servers = ["Private one", "Private two"].map(|name| {
            ShServer::with_private(
                network_identity(name),
                b"synthetic password\0",
                vec![3; 16],
                num_bigint::BigUint::from(789123u32),
            )
            .unwrap()
        });
        let parameters = [
            servers[0].hello(&blob(&hello_one)).unwrap(),
            servers[1].hello(&blob(&hello_two)).unwrap(),
        ];
        let auth = |blob: &[u8], sequence: u32| {
            [
                u32v(SERVER_OP, 40),
                tlv(
                    0x131c,
                    &[
                        u64v(0x02000303, 0),
                        u32v(0x010003be, sequence),
                        tlv(0x0a00030e, blob),
                    ]
                    .concat(),
                ),
            ]
            .concat()
        };
        // Sequence alone is sufficient for legacy servers that omit request IDs.
        for (index, name, guid) in [(1, "Private two", [2; 16]), (0, "Private one", [1; 16])] {
            let sequence = index as u32 + 1;
            h.remote.send(&auth(&parameters[index], sequence)).unwrap();
            let public = h.receive_operation(39);
            let challenge = servers[index].public(&blob(&public)).unwrap();
            h.remote.send(&auth(&challenge, sequence)).unwrap();
            let proof = h.receive_operation(39);
            let confirmation = servers[index].proof(&blob(&proof)).unwrap().0;
            h.remote.send(&auth(&confirmation, sequence)).unwrap();
            h.remote
                .send(&join_acknowledgement(101 + index as u64, name, guid))
                .unwrap();
            h.remote.send(&join_approval(guid)).unwrap();
        }
        let mut results = Vec::new();
        while results.len() < 2 {
            match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
                Message::Membership(_) => {}
                Message::Update(Update::CommandResult {
                    id, message, error, ..
                }) => {
                    assert!(!error);
                    results.push((id, message));
                }
                _ => panic!("password response was routed to the wrong network"),
            }
        }
        assert_eq!(
            results,
            [
                (32, "Joined Private two".into()),
                (31, "Joined Private one".into())
            ]
        );
    }
    #[test]
    fn a_join_refusal_does_not_cancel_another_pending_join() {
        let mut h = Harness::with_timeout(false, Diagnostics::default(), Duration::from_secs(3));
        for (id, name) in [(41, "Refused LAN"), (42, "Allowed LAN")] {
            h.commands
                .send(Command::Tagged {
                    id,
                    command: Box::new(Command::Join(name.into())),
                })
                .unwrap();
        }
        h.receive_operation(39);
        h.receive_operation(39);
        h.remote
            .send(
                &[
                    u32v(SERVER_OP, 37),
                    tlv(
                        0x131a,
                        &[
                            u32v(0x0100030c, 2),
                            u64v(0x02000340, 101),
                            u32v(0x010001d2, 20),
                        ]
                        .concat(),
                    ),
                ]
                .concat(),
            )
            .unwrap();
        h.remote
            .send(&join_acknowledgement(102, "Allowed LAN", [2; 16]))
            .unwrap();
        h.remote.send(&join_approval([2; 16])).unwrap();
        let mut results = BTreeMap::new();
        while results.len() < 2 {
            match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
                Message::Membership(_) => {}
                Message::Update(Update::CommandResult { id, error, .. }) => {
                    results.insert(id, error);
                }
                _ => panic!("a refused join disrupted the attachment"),
            }
        }
        assert!(results[&41]);
        assert!(!results[&42]);
        h.receive_operation(4);
    }
    #[test]
    fn uncorrelated_password_reply_remains_compatible_with_one_private_join() {
        use crate::network::NetworkPassword;
        let mut h = Harness::with_timeout(false, Diagnostics::default(), Duration::from_secs(3));
        h.commands
            .send(Command::Tagged {
                id: 51,
                command: Box::new(Command::Network(NetworkRequest::Join {
                    name: "Private LAN".into(),
                    password: Some(NetworkPassword::new("synthetic password".into()).unwrap()),
                })),
            })
            .unwrap();
        h.commands
            .send(Command::Tagged {
                id: 52,
                command: Box::new(Command::Join("Public LAN".into())),
            })
            .unwrap();
        h.receive_operation(39);
        h.receive_operation(39);
        h.remote
            .send(
                &[
                    u32v(SERVER_OP, 40),
                    tlv(
                        0x131c,
                        &[
                            u64v(0x02000303, 0),
                            tlv(
                                0x0a00030e,
                                &crate::crypto::sh_record(0x10000000, &0u32.to_be_bytes()),
                            ),
                        ]
                        .concat(),
                    ),
                ]
                .concat(),
            )
            .unwrap();
        h.remote
            .send(&join_acknowledgement(102, "Public LAN", [2; 16]))
            .unwrap();
        h.remote.send(&join_approval([2; 16])).unwrap();
        let mut results = BTreeMap::new();
        while results.len() < 2 {
            match h.events.recv_timeout(Duration::from_secs(3)).unwrap() {
                Message::Membership(_) => {}
                Message::Update(Update::CommandResult { id, error, .. }) => {
                    results.insert(id, error);
                }
                _ => panic!("legacy password reply interfered with the public join"),
            }
        }
        assert!(results[&51]);
        assert!(!results[&52]);
    }
    #[test]
    fn ambiguous_password_replies_cannot_advance_multiple_private_joins() {
        use crate::network::NetworkPassword;
        let mut h = Harness::with_timeout(false, Diagnostics::default(), Duration::from_secs(3));
        for name in ["Private one", "Private two"] {
            h.commands
                .send(Command::Network(NetworkRequest::Join {
                    name: name.into(),
                    password: Some(NetworkPassword::new("synthetic password".into()).unwrap()),
                }))
                .unwrap();
        }
        h.receive_operation(39);
        h.receive_operation(39);
        h.remote
            .send(
                &[
                    u32v(SERVER_OP, 40),
                    tlv(
                        0x131c,
                        &[
                            u64v(0x02000303, 0),
                            tlv(
                                0x0a00030e,
                                &crate::crypto::sh_record(0x10000000, &0u32.to_be_bytes()),
                            ),
                        ]
                        .concat(),
                    ),
                ]
                .concat(),
            )
            .unwrap();
        assert!(
            matches!(h.events.recv_timeout(Duration::from_secs(3)).unwrap(), Message::ControlFailed(error) if error.contains("Ambiguous network password reply"))
        );
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
    fn tagged_search_returns_its_catalog_to_the_requesting_cli() {
        let mut h = Harness::new(false);
        h.commands
            .send(Command::Tagged {
                id: 77,
                command: Box::new(Command::Search {
                    query: "synthetic".into(),
                    cursor: 0,
                }),
            })
            .unwrap();
        h.receive_operation(43);
        h.reply(101);
        loop {
            match h.events.recv_timeout(Duration::from_secs(2)).unwrap() {
                Message::Update(Update::CommandResult {
                    id,
                    error,
                    catalog: Some((networks, cursor)),
                    ..
                }) => {
                    assert_eq!(id, 77);
                    assert!(!error);
                    assert!(networks.is_empty());
                    assert_eq!(cursor, 0);
                    break;
                }
                Message::Membership(_) => {}
                _ => panic!("tagged search returned an unrelated update"),
            }
        }
    }

    #[test]
    fn invalid_tagged_network_command_returns_a_correlated_error() {
        let h = Harness::new(false);
        h.commands
            .send(Command::Tagged {
                id: 91,
                command: Box::new(Command::Join(String::new())),
            })
            .unwrap();
        match h.events.recv_timeout(Duration::from_secs(2)).unwrap() {
            Message::Update(Update::CommandResult { id, error, .. }) => {
                assert_eq!(id, 91);
                assert!(error);
            }
            _ => panic!("invalid CLI command must return a correlated error"),
        }
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
            wake: Arc::new(Wake::new().unwrap()),
        };
        for rid in 1..=150 {
            let tunnel::OwnedPacket::Frames(mut packet) =
                tunnel::decode_owned(tunnel::encode(&[1; 14]).unwrap()).unwrap()
            else {
                unreachable!();
            };
            events.packet(rid, packet.next().unwrap());
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
    fn send_batch_publishes_completed_traffic_even_on_an_io_error() {
        fn send_then_fail(counters: &TrafficCounters) -> Result<()> {
            let mut batch = TrafficBatch::new(counters);
            batch.sent(42);
            batch.sent(1514);
            batch.dropped += 1;
            bail!("synthetic send failure");
        }
        let counters = TrafficCounters::default();
        assert!(send_then_fail(&counters).is_err());
        {
            let mut batch = TrafficBatch::new(&counters);
            batch.sent(60);
            batch.dropped += 2;
        }
        assert_eq!(counters.sent_bytes.load(Ordering::Relaxed), 1616);
        assert_eq!(counters.sent_frames.load(Ordering::Relaxed), 3);
        assert_eq!(counters.dropped.load(Ordering::Relaxed), 3);
        assert_eq!(counters.receive_queue_dropped.load(Ordering::Relaxed), 0);
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
            ping: mpsc::sync_channel(1).0,
            stop: Arc::new(AtomicBool::new(false)),
            sender: mpsc::sync_channel(1).0,
            join: thread::spawn(|| {}),
            mac: Some([2, 0, 0, 0, 0, 2]),
            incoming: false,
            attempt: 1,
            connected_at: Some(Instant::now()),
            wake: Arc::new(Wake::new().unwrap()),
        }
    }

    #[test]
    fn refused_peers_retry_automatically_without_retrying_cancellation() {
        let now = Instant::now();
        let mut retries = BTreeMap::new();
        for (index, error) in [
            "coordinator refused connection",
            "peer service refused",
            "frame receive timeout",
        ]
        .into_iter()
        .enumerate()
        {
            let delay =
                schedule_peer_retry(&mut retries, 2, Some(error), false, false, now).unwrap();
            assert_eq!(retries[&2].failures, index as u32 + 1);
            assert_eq!(retries[&2].at, now + delay);
            assert!(delay >= Duration::from_millis(1_500));
        }
        schedule_peer_retry(&mut retries, 2, Some("disconnected"), true, true, now);
        assert_eq!(
            retries[&2].failures, 1,
            "a stable connection resets backoff"
        );
        assert!(retries[&2].previously_connected);
        assert!(schedule_peer_retry(&mut retries, 2, None, false, false, now).is_none());
        assert!(retries.is_empty());
    }

    #[test]
    fn indexed_forwarding_preserves_duplicate_addresses_filters_bytes_and_queue_drops() {
        let source = Ipv4Addr::new(26, 0, 0, 1);
        let mut members = membership();
        let template = members.peers[&2].clone();
        for rid in 2..=32 {
            let mut peer = template.clone();
            peer.rid = rid;
            peer.vip = Ipv4Addr::new(26, 0, 0, if rid == 3 { 2 } else { rid as u8 });
            members.peers.insert(rid, peer);
        }
        let eligible: BTreeSet<_> = (2..=32).filter(|rid| *rid != 6).collect();
        let options = Options {
            traffic_peers: Some((2..=32).filter(|rid| *rid != 7).collect()),
            ..Default::default()
        };
        let make_workers = || {
            let mut workers = BTreeMap::new();
            let mut receivers = BTreeMap::new();
            for (&rid, peer) in &members.peers {
                let (sender, receiver) = mpsc::sync_channel(1);
                let mut w = worker();
                w.sender = sender;
                w.mac = (rid != 4).then(|| tunnel::mac(peer.vip));
                if rid == 5 {
                    w.cancel();
                }
                if rid == 8 {
                    w.sender.send(Arc::new(vec![99])).unwrap();
                }
                workers.insert(rid, w);
                receivers.insert(rid, receiver);
            }
            (workers, receivers)
        };
        let (before, old_receivers) = make_workers();
        let (after, new_receivers) = make_workers();
        let mut table = ForwardingTable::default();
        table.rebuild(&members, &eligible);
        let mut directed = tunnel::gratuitous_arp(source);
        directed[21] = 1;
        directed[38..42].copy_from_slice(&members.peers[&2].vip.octets());
        let mut wrong_source = directed.clone();
        wrong_source[6] ^= 1;
        let frames = [
            tunnel::gratuitous_arp(source),
            directed,
            wrong_source,
            vec![0; tunnel::MAX_FRAME + 1],
        ];
        for frame in frames {
            let mut dropped = 0;
            let mut forwarded = false;
            let shared = Arc::new(frame.clone());
            for (&rid, w) in &before {
                if !eligible.contains(&rid)
                    || !options.allows(rid)
                    || w.stop.load(Ordering::Relaxed)
                {
                    continue;
                }
                if w.mac.is_some_and(|mac| {
                    tunnel::deliver_to(
                        &frame,
                        source,
                        tunnel::mac(source),
                        members.peers[&rid].vip,
                        mac,
                    )
                }) {
                    if w.sender.try_send(shared.clone()).is_ok() {
                        forwarded = true;
                    } else {
                        dropped += 1;
                    }
                }
            }
            dropped += u64::from(!forwarded);
            assert_eq!(
                forward_frame(frame, source, &after, &table, &options),
                dropped
            );
            for rid in members.peers.keys() {
                let old: Vec<_> = old_receivers[rid].try_iter().collect();
                let new: Vec<_> = new_receivers[rid].try_iter().collect();
                assert_eq!(old, new, "different queue contents for {rid}");
            }
        }
        members.peers.remove(&2);
        table.rebuild(&members, &eligible);
        let route = tunnel::forwarding(
            &{
                let mut frame = tunnel::gratuitous_arp(source);
                frame[38..42].copy_from_slice(&Ipv4Addr::new(26, 0, 0, 2).octets());
                frame
            },
            source,
            tunnel::mac(source),
        )
        .unwrap();
        assert_eq!(table.targets(&route), [(3, Ipv4Addr::new(26, 0, 0, 2))]);
        for w in before.into_values().chain(after.into_values()) {
            w.join.join().unwrap();
        }
    }

    #[test]
    fn manual_retry_never_postpones_deadlines_or_erases_failure_history() {
        let now = Instant::now();
        let mut snapshot = Snapshot::default();
        let mut workers = BTreeMap::new();
        let mut members = membership();
        let template = members.peers[&2].clone();
        for rid in 3..=7 {
            let mut peer = template.clone();
            peer.rid = rid;
            peer.vip = Ipv4Addr::new(26, 0, 0, rid as u8);
            members.peers.insert(rid, peer);
        }
        let mut eligible = refresh_membership(
            &mut snapshot,
            &members,
            1,
            &workers,
            &Diagnostics::default(),
        )
        .unwrap();
        for peer in snapshot.peers.values_mut() {
            peer.status = PeerState::Refused;
            peer.detail = "refused".into();
        }
        snapshot.peers.get_mut(&3).unwrap().status = PeerState::Failed;
        snapshot.peers.get_mut(&4).unwrap().status = PeerState::Connected;
        snapshot.peers.get_mut(&5).unwrap().status = PeerState::Connecting;
        eligible.remove(&6);
        workers.insert(7, worker());
        let mut retries = BTreeMap::from([(
            2,
            PeerRetry {
                failures: 8,
                at: now + Duration::from_secs(3),
                previously_connected: true,
            },
        )]);
        assert_eq!(
            request_peer_retries(&mut snapshot, &workers, &mut retries, &eligible, now),
            2
        );
        assert!(retries[&2].at <= now + Duration::from_secs(3));
        let existing_deadline = retries[&2].at;
        assert_eq!(retries[&2].failures, 8);
        let deadline = retries[&3].at;
        assert!(deadline >= now + Duration::from_millis(1_500));
        request_peer_retries(
            &mut snapshot,
            &workers,
            &mut retries,
            &eligible,
            now + Duration::from_secs(1),
        );
        assert_eq!(retries[&2].at, existing_deadline);
        assert_eq!(retries[&3].at, deadline);
        assert_eq!(retries.keys().copied().collect::<Vec<_>>(), [2, 3]);
        assert_eq!(snapshot.peers[&2].status, PeerState::Refused);
        assert_eq!(snapshot.peers[&3].status, PeerState::Failed);
        assert_eq!(snapshot.peers[&3].detail.matches("Retry queued").count(), 1);
        workers.into_values().next().unwrap().join.join().unwrap();
    }

    #[test]
    fn address_announcements_only_reach_authenticated_eligible_channels() {
        let vip = Ipv4Addr::new(26, 0, 0, 1);
        let mut workers = BTreeMap::new();
        let mut receivers = BTreeMap::new();
        for rid in 2..=7 {
            let (sender, receiver) = mpsc::sync_channel(2);
            let mut w = worker();
            w.sender = sender;
            workers.insert(rid, w);
            receivers.insert(rid, receiver);
        }
        // Both incoming and outgoing authenticated channels get an announcement.
        workers.get_mut(&3).unwrap().incoming = true;
        workers.get_mut(&4).unwrap().mac = None;
        workers[&5].stop.store(true, Ordering::Relaxed);
        let eligible = BTreeSet::from([2, 3, 4, 5, 7]);
        let options = Options {
            traffic_peers: Some(BTreeSet::from([2, 3, 4, 5, 6])),
            ..Options::default()
        };
        assert_eq!(
            announce_address(vip, workers.iter(), &eligible, &options),
            0
        );
        let mut shared = None;
        for (rid, receiver) in &receivers {
            if [2, 3].contains(rid) {
                let frame = receiver.try_recv().unwrap();
                assert_eq!(frame.as_ref(), &tunnel::gratuitous_arp(vip));
                if let Some(previous) = &shared {
                    assert!(Arc::ptr_eq(previous, &frame));
                }
                shared = Some(frame.clone());
                assert!(tunnel::deliver_to(
                    &frame,
                    vip,
                    tunnel::mac(vip),
                    Ipv4Addr::new(26, 0, 0, *rid as u8),
                    [2; 6]
                ));
            }
            assert!(receiver.try_recv().is_err());
        }
        // Interface recreation or peer reconnection can announce again.
        assert_eq!(
            announce_address(vip, workers.iter(), &eligible, &options),
            0
        );
        assert!(receivers[&2].try_recv().is_ok());
        for w in workers.into_values() {
            w.join.join().unwrap();
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

#[cfg(test)]
mod ping_tests {
    use super::*;

    #[test]
    fn encrypted_ping_measures_only_the_correlated_reply_and_keeps_forwarding() {
        let (mut local, mut remote) = crate::peer::synthetic_pair();
        local.stream.sustain();
        let (lifecycle, results) = mpsc::sync_channel(16);
        let (packets, _) = mpsc::sync_channel(16);
        let wake = Arc::new(Wake::new().unwrap());
        let events = PeerEvents {
            lifecycle,
            packets,
            traffic: Arc::default(),
            diagnostics: Diagnostics::default(),
            attempt: 1,
            wake: wake.clone(),
        };
        let (frames_tx, frames) = mpsc::sync_channel(16);
        let (probe_tx, probes) = mpsc::sync_channel(1);
        probe_tx
            .send(PingProbe {
                token: 123,
                started: Instant::now(),
            })
            .unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let (cancel, notify) = (stop.clone(), wake.clone());
        let worker = thread::spawn(move || {
            forward_peer(
                &mut local,
                &events,
                frames,
                probes,
                &cancel,
                &notify,
                &mut PeerActivity::default(),
            )
        });
        let packet = remote.receive().unwrap();
        let tunnel::Packet::Keepalive {
            sequence,
            reply: false,
        } = tunnel::decode(&packet).unwrap()
        else {
            panic!("expected an encrypted probe");
        };
        remote
            .send(&tunnel::keepalive(sequence.wrapping_add(20), true))
            .unwrap();
        assert!(
            results.recv_timeout(Duration::from_millis(20)).is_err(),
            "unrelated replies cannot complete RTT"
        );
        remote.send(&tunnel::keepalive(sequence, true)).unwrap();
        let Message::Pong(peer, token, rtt_ms) =
            results.recv_timeout(Duration::from_secs(1)).unwrap()
        else {
            panic!("expected the RTT result");
        };
        assert_eq!((peer, token), (2, 123));
        assert!((10. ..1000.).contains(&rtt_ms));
        let frame = tunnel::gratuitous_arp(Ipv4Addr::new(26, 0, 0, 1));
        frames_tx.send(Arc::new(frame.clone())).unwrap();
        wake.notify();
        loop {
            let packet = remote.receive().unwrap();
            if let tunnel::Packet::Frames(frames) = tunnel::decode(&packet).unwrap() {
                assert_eq!(frames, vec![frame.as_slice()]);
                break;
            }
        }
        stop.store(true, Ordering::Relaxed);
        wake.notify();
        worker.join().unwrap().unwrap();
    }

    #[test]
    fn ping_timeout_is_exactly_three_seconds_and_results_keep_the_request_id() {
        let started = Instant::now();
        let probe = PingProbe { token: 1, started };
        let mut pings = BTreeMap::from([(2, (probe, Some(47)))]);
        assert!(expire_pings(&mut pings, started + Duration::from_millis(2999)).is_empty());
        let expired = expire_pings(&mut pings, started + Duration::from_millis(3000));
        assert!(
            matches!(&expired[..], [Update::Ping { peer: 2, id: Some(47), rtt_ms: None, error: Some(message) }]
            if message == PING_TIMEOUT_MESSAGE)
        );
        assert!(pings.is_empty());
        assert!(expire_pings(&mut pings, started + Duration::from_secs(10)).is_empty());
    }
}

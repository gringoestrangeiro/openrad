//! Bounded multi-network session, concurrent peer scheduling and TAP routing.
use crate::{
    incoming::{Hub, Policy, Setup},
    output::ReportDirectory,
    peer::{PeerChannel, TransportReport},
    protocol::*,
    session::Session,
    tap::Tap,
    tunnel::{self, Packet},
};
use anyhow::{ensure, Result};
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    net::Ipv4Addr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, SyncSender},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};

pub struct RunOptions {
    pub networks: Vec<String>,
    pub only_peers: BTreeSet<u64>,
    pub traffic_peers: BTreeSet<u64>,
    pub tap: bool,
    pub passive: bool,
    pub incoming_transport: Policy,
    pub duration: Duration,
}
pub struct AttachedClient {
    pub session: Session,
    pub membership: Membership,
    pub vip: Ipv4Addr,
    pub identity: Identity,
    pub modulus: Vec<u8>,
}
struct WorkerContext {
    identity: Identity,
    modulus: Vec<u8>,
    vip: Ipv4Addr,
    until: Instant,
    stop: Arc<AtomicBool>,
}
#[derive(Default, Serialize)]
pub struct PeerStats {
    pub reports: Option<String>,
    pub connected: bool,
    pub error: Option<String>,
    pub sent_frames: u64,
    pub received_frames: u64,
    pub tap_written: u64,
    pub dropped: u64,
    pub transport: Option<TransportReport>,
    pub verified_keepalive_replies: u64,
    pub received_authenticated_frames: u64,
}
#[derive(Default, Serialize)]
pub struct RunStats {
    pub peers: BTreeMap<u64, PeerStats>,
    pub max_concurrent: usize,
    pub tap_read: u64,
    pub tap_written: u64,
    pub tap_dropped: u64,
    pub tap_created: bool,
    pub tap_removed: bool,
}
enum Event {
    Connected(u64, [u8; 6], TransportReport, Option<String>),
    KeepaliveVerified(u64),
    ReceivedAuthenticatedFrames(u64, usize),
    Frame(u64, Vec<u8>),
    Sent(u64),
    Dropped(u64),
    Closed(u64, Option<String>),
}
struct Worker {
    stop: Arc<AtomicBool>,
    join: thread::JoinHandle<()>,
    incoming: bool,
    binding: Peer,
}

#[allow(clippy::too_many_arguments)]
fn worker(
    context: Arc<WorkerContext>,
    peer: Peer,
    reports: ReportDirectory,
    data_enabled: bool,
    events: SyncSender<Event>,
    commands: mpsc::Receiver<Vec<u8>>,
    incoming: Option<Setup>,
    peer_stop: Arc<AtomicBool>,
) {
    let rid = peer.rid;
    let outcome = (|| -> Result<()> {
        if context.stop.load(Ordering::Relaxed) {
            return Ok(());
        }
        let duration =
            context.until.saturating_duration_since(Instant::now()) + Duration::from_secs(15);
        let mut channel = if let Some(setup) = incoming {
            setup.accept(
                context.identity.rid,
                context.vip,
                peer,
                &reports,
                peer_stop.clone(),
            )?
        } else {
            PeerChannel::connect_with_stop(
                &context.identity,
                &context.modulus,
                context.vip,
                peer,
                &reports,
                duration,
                Some(peer_stop.clone()),
            )?
        };
        channel.stream.sustain();
        events.send(Event::Connected(
            rid,
            channel.mac,
            channel.transport.clone(),
            reports
                .directory
                .file_name()
                .map(|n| n.to_string_lossy().into_owned()),
        ))?;
        let mut next_keepalive = Instant::now() + Duration::from_secs(15);
        let mut seq = 0;
        let mut last_verified = 0;
        while !context.stop.load(Ordering::Relaxed) && !peer_stop.load(Ordering::Relaxed) {
            for frame in commands.try_iter().take(32) {
                if channel.send(&tunnel::encode(&frame)?)? {
                    events.send(Event::Sent(rid))?;
                } else {
                    events.send(Event::Dropped(rid))?;
                }
            }
            if Instant::now() >= next_keepalive {
                seq += 1;
                channel.send(&tunnel::keepalive(seq, false))?;
                next_keepalive = Instant::now() + Duration::from_secs(15);
            }
            if !channel.stream.ready(100)? {
                continue;
            }
            let plain = channel.receive()?;
            match tunnel::decode(&plain)? {
                Packet::Keepalive {
                    sequence,
                    reply: false,
                } => {
                    channel.send(&tunnel::keepalive(sequence, true))?;
                }
                Packet::Keepalive {
                    sequence,
                    reply: true,
                } if sequence == seq && seq > last_verified => {
                    last_verified = seq;
                    events.send(Event::KeepaliveVerified(rid))?;
                }
                Packet::Frames(frames) => {
                    events.send(Event::ReceivedAuthenticatedFrames(rid, frames.len()))?;
                    if !data_enabled {
                        continue;
                    }
                    for frame in frames {
                        if events.try_send(Event::Frame(rid, frame.to_vec())).is_err() {
                            let _ = events.try_send(Event::Dropped(rid));
                        }
                    }
                }
                _ => {}
            }
        }
        Ok(())
    })();
    let _ = events.send(Event::Closed(rid, outcome.err().map(|e| format!("{e:#}"))));
}

pub fn run(
    client: AttachedClient,
    options: RunOptions,
    reports: &ReportDirectory,
    stop: Arc<AtomicBool>,
    report: &dyn Fn(Value),
) -> Result<RunStats> {
    let AttachedClient {
        mut session,
        mut membership,
        vip,
        identity,
        modulus,
    } = client;
    let mut stats = RunStats::default();
    let mut tap = None;
    let mut workers: BTreeMap<u64, Worker> = BTreeMap::new();
    let mut retired = vec![];
    let mut attempted = BTreeSet::new();
    let mut senders = BTreeMap::new();
    let mut active = BTreeMap::new();
    let (wire_tx, wire_rx) = mpsc::sync_channel(64);
    let mut incoming = Hub::new(
        session.stream.socket.local_addr()?.ip(),
        session.ues.clone(),
        wire_tx,
    );
    let (tx, rx) = mpsc::sync_channel(256);
    let until = Instant::now() + options.duration;
    let worker_context = Arc::new(WorkerContext {
        identity: identity.clone(),
        modulus,
        vip,
        until,
        stop: stop.clone(),
    });
    let mut heartbeat = Instant::now() + Duration::from_secs(10);
    let result = (|| -> Result<()> {
        let eligible = membership.eligible(identity.rid, &options.networks)?;
        ensure!(eligible.len() <= 128, "eligible-peer budget exceeded");
        ensure!(
            options
                .traffic_peers
                .iter()
                .all(|r| eligible.iter().any(|p| p.rid == *r)),
            "traffic allowlist contains an ineligible or offline peer"
        );
        reports.json("membership.json", &membership)?;
        report(
            json!({"event":"ready","vip":vip,"networks":membership.networks,"eligible":eligible.len()}),
        );
        while Instant::now() < until && !stop.load(Ordering::Relaxed) {
            let mut eligible = membership.eligible(identity.rid, &options.networks)?;
            eligible
                .retain(|p| options.only_peers.is_empty() || options.only_peers.contains(&p.rid));
            eligible.sort_by_key(|p| (!options.traffic_peers.contains(&p.rid), p.rid));
            incoming.expire();
            let eligible_rids: BTreeSet<_> = eligible.iter().map(|p| p.rid).collect();
            for (rid, w) in &workers {
                if !eligible_rids.contains(rid)
                    || membership
                        .peers
                        .get(rid)
                        .is_none_or(|p| p.vip != w.binding.vip || p.server != w.binding.server)
                {
                    w.stop.store(true, Ordering::Relaxed);
                }
            }
            let pending = incoming.pending_rids();
            for rid in &pending {
                if active.contains_key(rid) {
                    incoming.reject(*rid);
                } else if let Some(w) = workers.get(rid) {
                    // Both Rust nodes initiating: retain the lower RID's outgoing
                    // attempt. Never replace an established channel.
                    if !w.incoming && identity.rid > *rid {
                        w.stop.store(true, Ordering::Relaxed);
                    }
                }
            }
            eligible.sort_by_key(|p| {
                (
                    !pending.contains(&p.rid),
                    !options.traffic_peers.contains(&p.rid),
                    p.rid,
                )
            });
            for peer in eligible {
                if workers.len().saturating_sub(active.len()) >= 4 {
                    break;
                }
                if workers.contains_key(&peer.rid) {
                    continue;
                }
                let setup = incoming.take(peer.rid, options.incoming_transport);
                if setup.is_none() && (options.passive || attempted.contains(&peer.rid)) {
                    continue;
                }
                let is_incoming = setup.is_some();
                attempted.insert(peer.rid);
                ensure!(attempted.len() <= 128, "session peer budget exceeded");
                let name = setup
                    .as_ref()
                    .map(|s| format!("peer-{}-incoming-{}", peer.rid, s.cid))
                    .unwrap_or_else(|| format!("peer-{}", peer.rid));
                let peer_stats = stats.peers.entry(peer.rid).or_default();
                if peer_stats.transport.is_none() {
                    peer_stats.reports = Some(name.clone());
                }
                let pc = reports.child(&name)?;
                pc.json("peer.json", &peer)?;
                let (sender, receiver) = mpsc::sync_channel(64);
                senders.insert(peer.rid, sender);
                let (context, tx) = (worker_context.clone(), tx.clone());
                let data = options.traffic_peers.contains(&peer.rid);
                let peer_stop = Arc::new(AtomicBool::new(false));
                let cancel = peer_stop.clone();
                let rid = peer.rid;
                let binding = peer.clone();
                let join = thread::spawn(move || {
                    worker(context, peer, pc, data, tx, receiver, setup, cancel)
                });
                workers.insert(
                    rid,
                    Worker {
                        stop: peer_stop,
                        join,
                        incoming: is_incoming,
                        binding,
                    },
                );
            }
            for bytes in wire_rx.try_iter().take(64) {
                session.send(&bytes)?;
            }
            for event in rx.try_iter().take(256) {
                match event {
                    Event::Connected(rid, mac, transport, peer_capture) => {
                        if workers
                            .get(&rid)
                            .is_none_or(|w| w.stop.load(Ordering::Relaxed))
                        {
                            continue;
                        }
                        incoming.reject(rid);
                        incoming.finish(rid);
                        active.insert(rid, mac);
                        stats.peers.entry(rid).or_default().connected = true;
                        // Only traffic from this channel can attest its path.
                        let peer_stats = stats.peers.entry(rid).or_default();
                        peer_stats.reports = peer_capture;
                        peer_stats.verified_keepalive_replies = 0;
                        peer_stats.received_authenticated_frames = 0;
                        stats.peers.entry(rid).or_default().error = None;
                        stats.peers.entry(rid).or_default().transport = Some(transport.clone());
                        stats.max_concurrent = stats.max_concurrent.max(active.len());
                        report(
                            json!({"event":"peer_connected","rid":rid,"path":transport.path,"incoming":transport.incoming,"controlled":options.traffic_peers.contains(&rid)}),
                        );
                    }
                    Event::KeepaliveVerified(rid) => {
                        stats
                            .peers
                            .entry(rid)
                            .or_default()
                            .verified_keepalive_replies += 1
                    }
                    Event::ReceivedAuthenticatedFrames(rid, n) => {
                        stats
                            .peers
                            .entry(rid)
                            .or_default()
                            .received_authenticated_frames += n as u64
                    }
                    Event::Closed(rid, error) => {
                        if let Some(w) = workers.remove(&rid) {
                            retired.push(w.join);
                        }
                        senders.remove(&rid);
                        incoming.finish(rid);
                        active.remove(&rid);
                        stats.peers.entry(rid).or_default().error = error.clone();
                        report(json!({"event":"peer_closed","rid":rid,"error":error}));
                    }
                    Event::Sent(rid) => stats.peers.entry(rid).or_default().sent_frames += 1,
                    Event::Dropped(rid) => stats.peers.entry(rid).or_default().dropped += 1,
                    Event::Frame(rid, frame) => {
                        let peer = membership
                            .peers
                            .get(&rid)
                            .ok_or_else(|| anyhow::anyhow!("unknown authenticated peer"))?;
                        stats.peers.entry(rid).or_default().received_frames += 1;
                        let source_mac = active.get(&rid);
                        let valid = eligible_rids.contains(&rid)
                            && workers
                                .get(&rid)
                                .is_some_and(|w| !w.stop.load(Ordering::Relaxed))
                            && frame.len() <= 1414
                            && frame.len() >= 14
                            && source_mac.map(|m| frame[6..12] == *m).unwrap_or(false)
                            && tunnel::endpoints(&frame) == Some((peer.vip, vip))
                            && (frame[..6] == tunnel::mac(vip)
                                || frame[..6] == [255; 6]
                                    && tunnel::arp_endpoints(&frame).is_some());
                        if valid {
                            if let Some(t) = tap.as_mut() {
                                Tap::send(t, &frame)?;
                                stats.tap_written += 1;
                                stats.peers.entry(rid).or_default().tap_written += 1;
                                continue;
                            }
                        }
                        stats.peers.entry(rid).or_default().dropped += 1;
                    }
                }
            }
            if options.tap
                && tap.is_none()
                && options.traffic_peers.iter().all(|r| active.contains_key(r))
            {
                let peers: Vec<_> = options
                    .traffic_peers
                    .iter()
                    .map(|rid| membership.peers[rid].vip)
                    .collect();
                let unique: BTreeSet<_> = peers.iter().collect();
                ensure!(unique.len() == peers.len(), "ambiguous peer VIP");
                tap = Some(Tap::create(vip, &peers)?);
                stats.tap_created = true;
                report(
                    json!({"event":"tap_ready","interface":"radminvpn0","vip":vip,"routes":peers,"uid":crate::tap::user_id()}),
                );
            }
            if let Some(t) = tap.as_mut() {
                for _ in 0..64 {
                    if !t.ready(0)? {
                        break;
                    }
                    let frame = t.receive()?;
                    stats.tap_read += 1;
                    let target = tunnel::endpoints(&frame)
                        .filter(|(src, _)| *src == vip && frame[6..12] == tunnel::mac(vip));
                    let peer = target.and_then(|(_, dst)| {
                        options
                            .traffic_peers
                            .iter()
                            .filter_map(|r| membership.peers.get(r))
                            .find(|p| p.vip == dst)
                    });
                    if let Some(p) = peer {
                        if let Some(mac) = active.get(&p.rid) {
                            if eligible_rids.contains(&p.rid)
                                && workers
                                    .get(&p.rid)
                                    .is_some_and(|w| !w.stop.load(Ordering::Relaxed))
                                && (frame[..6] == *mac
                                    || frame[..6] == [255; 6]
                                        && tunnel::arp_endpoints(&frame).is_some())
                                && senders[&p.rid].try_send(frame.clone()).is_ok()
                            {
                                continue;
                            }
                        }
                    }
                    stats.tap_dropped += 1;
                }
            }
            if Instant::now() >= heartbeat {
                session.send(&u32v(CLIENT_OP, 4))?;
                heartbeat = Instant::now() + Duration::from_secs(10);
            }
            if session.stream.ready(0)? {
                let data = session.receive()?;
                match op(&data)? {
                    41 => membership.changes(&data)?,
                    38 => membership.snapshot(&data)?,
                    11 | 6 | 7 | 23 | 29 => {
                        if let Err(e) = incoming.ingest(&data) {
                            report(json!({"event":"incoming_rejected","error":e.to_string()}));
                        }
                    }
                    16 => anyhow::bail!("server disconnected the session"),
                    _ => {}
                }
            }
            thread::sleep(Duration::from_millis(10));
        }
        ensure!(
            !options.tap || stats.tap_created,
            "controlled peer connections did not become ready for TAP"
        );
        Ok(())
    })();
    stop.store(true, Ordering::Relaxed);
    drop(tap); // Last nonpersistent TAP FD; kernel also removes its /32 routes.
    stats.tap_removed =
        stats.tap_created && !std::path::Path::new("/sys/class/net/radminvpn0").exists();
    drop(rx); // Wake workers blocked on event publication during failure cleanup.
    for worker in workers.values() {
        worker.stop.store(true, Ordering::Relaxed);
    }
    for worker in workers.into_values() {
        let _ = worker.join.join();
    }
    for worker in retired {
        let _ = worker.join();
    }
    reports.json("result.json", &stats)?;
    reports.json("membership-final.json", &membership)?;
    report(json!({"event":"finished","stats":stats}));
    result?;
    ensure!(
        !stats.tap_created || stats.tap_removed,
        "TAP did not disappear after close"
    );
    Ok(stats)
}

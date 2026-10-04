//! Incoming offers are accepted only from the authenticated attachment stream.
//! Membership is checked by the scheduler before an offer can open a listener.
use crate::{
    output::ReportDirectory,
    peer::{PeerChannel, PeerStream, RelayPreparation, TransportPath},
    protocol::*,
    scheduling::{DIRECT_TCP_LANES, MAX_PENDING_OFFERS},
    session::Framed,
    udp,
};
use anyhow::{bail, ensure, Result};
use serde_json::json;
use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    net::{IpAddr, Ipv4Addr},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Policy {
    #[default]
    All,
    Tcp,
    Udp,
    Relay,
}
impl std::str::FromStr for Policy {
    type Err = String;
    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        match s {
            "all" => Ok(Self::All),
            "tcp" => Ok(Self::Tcp),
            "udp" => Ok(Self::Udp),
            "relay" => Ok(Self::Relay),
            _ => Err("expected all, tcp, udp or relay".into()),
        }
    }
}
struct Offer {
    rid: u64,
    password: Vec<u8>,
    created: Instant,
    records: Vec<Vec<u8>>,
}
struct Route {
    rid: u64,
    until: Instant,
    tx: SyncSender<Vec<u8>>,
}
struct IncomingHeader {
    cid: u64,
    operation: u32,
    bytes: usize,
    // Defer offer errors until CID deduplication: a duplicate offer must never
    // replace a secret, even when its nested credentials are malformed.
    offer: Option<Result<(u64, Vec<u8>)>>,
}
impl IncomingHeader {
    fn parse(data: &[u8]) -> Result<Option<Self>> {
        let fields = records(data)?;
        let operation = int32(field(&fields, SERVER_OP)?)?;
        if ![11, 6, 7, 23, 29].contains(&operation) {
            return Ok(None);
        }
        ensure!(data.len() <= 65536, "incoming record size limit");
        let cid = int64(field(&fields, 0x020001c1)?)?;
        ensure!(cid != 0, "zero incoming connection ID");
        let offer = (operation == 11).then(|| {
            let fields = records(field(&fields, 0x1235)?)?;
            let rid = int64(field(&fields, 0x020001e1)?)?;
            let password = field(&fields, 0x0a0001cd)?;
            ensure!(
                rid != 0 && (6..=1024).contains(&password.len()),
                "invalid incoming credentials"
            );
            Ok((rid, password.to_vec()))
        });
        Ok(Some(Self {
            cid,
            operation,
            bytes: data.len(),
            offer,
        }))
    }
}

/// Parsed on the attachment worker; followup records move their transport
/// allocation through the engine and setup mailboxes without copying bytes.
pub(crate) struct IncomingRecord {
    header: IncomingHeader,
    data: Vec<u8>,
}
impl IncomingRecord {
    pub(crate) fn parse(data: Vec<u8>) -> Result<Option<Self>> {
        let Some(header) = IncomingHeader::parse(&data)? else {
            return Ok(None);
        };
        let data = if header.offer.is_some() {
            Vec::new()
        } else {
            data
        };
        Ok(Some(Self { header, data }))
    }
    pub(crate) fn operation(&self) -> u32 {
        self.header.operation
    }
    pub(crate) fn len(&self) -> usize {
        self.header.bytes
    }
}

/// Bounded mailbox; unknown/offline members may arrive before the membership
/// update, but they cannot start work. Duplicate CIDs never replace a secret.
pub struct Hub {
    pending: HashMap<u64, Offer>,
    routes: HashMap<u64, Route>,
    pending_by_rid: HashMap<u64, u64>,
    routes_by_rid: HashMap<u64, u64>,
    route_ip: IpAddr,
    ues: Vec<Ipv4Addr>,
    wire: SyncSender<Vec<u8>>,
}
impl Hub {
    pub fn new(route_ip: IpAddr, ues: Vec<Ipv4Addr>, wire: SyncSender<Vec<u8>>) -> Self {
        Self {
            pending: HashMap::new(),
            routes: HashMap::new(),
            pending_by_rid: HashMap::new(),
            routes_by_rid: HashMap::new(),
            route_ip,
            ues,
            wire,
        }
    }
    pub fn ingest(&mut self, data: &[u8]) -> Result<()> {
        let Some(header) = IncomingHeader::parse(data)? else {
            return Ok(());
        };
        self.admit(header, || data.to_vec())
    }
    pub(crate) fn ingest_record(&mut self, record: IncomingRecord) -> Result<()> {
        self.admit(record.header, || record.data)
    }
    fn admit(&mut self, header: IncomingHeader, data: impl FnOnce() -> Vec<u8>) -> Result<()> {
        let cid = header.cid;
        if let Some(offer) = header.offer {
            if self.pending.contains_key(&cid) || self.routes.contains_key(&cid) {
                return Ok(());
            }
            let (rid, password) = offer?;
            if self.pending.len() >= MAX_PENDING_OFFERS
                || self.pending_by_rid.contains_key(&rid)
                || self.routes_by_rid.contains_key(&rid)
            {
                return Ok(());
            }
            self.pending.insert(
                cid,
                Offer {
                    rid,
                    password,
                    created: Instant::now(),
                    records: vec![],
                },
            );
            self.pending_by_rid.insert(rid, cid);
        } else if let Some(route) = self.routes.get(&cid) {
            // A full or closed mailbox cannot block the attachment heartbeat.
            let _ = route.tx.try_send(data());
        } else if let Some(p) = self.pending.get_mut(&cid) {
            if p.records.len() < 8 {
                p.records.push(data());
            }
        }
        Ok(())
    }
    pub fn expire(&mut self) {
        let now = Instant::now();
        self.pending.retain(|_, p| {
            let keep = now.duration_since(p.created) < Duration::from_secs(30);
            if !keep {
                self.pending_by_rid.remove(&p.rid);
            }
            keep
        });
        self.routes.retain(|_, p| {
            let keep = now < p.until;
            if !keep {
                self.routes_by_rid.remove(&p.rid);
            }
            keep
        });
    }
    pub fn pending_rids(&self) -> BTreeSet<u64> {
        self.pending_by_rid.keys().copied().collect()
    }
    pub fn reject(&mut self, rid: u64) {
        if let Some(cid) = self.pending_by_rid.remove(&rid) {
            self.pending.remove(&cid);
        }
    }
    pub fn finish(&mut self, rid: u64) {
        if let Some(cid) = self.routes_by_rid.remove(&rid) {
            self.routes.remove(&cid);
        }
    }
    pub fn take(&mut self, rid: u64, policy: Policy) -> Option<Setup> {
        let cid = self.pending_by_rid.remove(&rid)?;
        let p = self.pending.remove(&cid)?;
        let (tx, rx) = mpsc::sync_channel(16);
        for record in p.records {
            let _ = tx.try_send(record);
        }
        self.routes.insert(
            cid,
            Route {
                rid,
                until: Instant::now() + Duration::from_secs(45),
                tx,
            },
        );
        self.routes_by_rid.insert(rid, cid);
        Some(Setup {
            cid,
            password: p.password,
            records: rx,
            wire: self.wire.clone(),
            route_ip: self.route_ip,
            ues: self.ues.clone(),
            policy,
        })
    }
}
pub struct Setup {
    pub cid: u64,
    password: Vec<u8>,
    records: Receiver<Vec<u8>>,
    wire: SyncSender<Vec<u8>>,
    route_ip: IpAddr,
    ues: Vec<Ipv4Addr>,
    policy: Policy,
}
impl Setup {
    pub fn accept(
        self,
        own_rid: u64,
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        stop: Arc<AtomicBool>,
    ) -> Result<PeerChannel> {
        self.accept_observed(own_rid, own_ip, peer, reports, stop, |_| {})
    }
    pub(crate) fn accept_observed(
        self,
        own_rid: u64,
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        stop: Arc<AtomicBool>,
        observe: impl FnOnce(&crate::peer::TransportReport),
    ) -> Result<PeerChannel> {
        let cancel = Arc::new(AtomicBool::new(false));
        let until = Instant::now() + Duration::from_secs(40);
        let mut attempts = vec![];
        let mut tcp_candidates = vec![];
        let mut udp_candidates = vec![];
        let mut mapped_candidates = vec![];
        let nonce = u16::from_be_bytes(crate::crypto::random(2).try_into().unwrap()).max(1);
        let password = &self.password;
        let cid = self.cid;
        let peer_rid = peer.rid;
        let start =
            |name: &'static str, stream: PeerStream, path: TransportPath| -> Result<PeerChannel> {
                let c = reports.child(name)?;
                PeerChannel::accept_authenticated(
                    stream,
                    path,
                    password,
                    own_rid,
                    own_ip,
                    peer.clone(),
                    &c,
                )
            };
        let result = thread::scope(|scope| -> Result<PeerChannel> {
            let _cancel_guard = Cancel(cancel.clone());
            let (tx, rx) = mpsc::channel::<(&'static str, Result<PeerChannel>)>();
            let listener = if matches!(self.policy, Policy::All | Policy::Tcp) {
                let listener = udp::listen_dual_stack()?;
                listener.set_nonblocking(true)?;
                let addresses =
                    udp::local_candidates(self.route_ip, listener.local_addr()?.port())?;
                self.wire.try_send(advertise_tcp(self.cid, &addresses)?)?;
                Some(listener)
            } else {
                None
            };
            let (rendezvous_tx, rendezvous_rx) = mpsc::sync_channel::<Result<Framed>>(8);
            let mut rendezvous_active = 0;
            let mut accepted = 0;
            let mut validated_tcp = VecDeque::new();
            let mut tcp_auth_active = false;
            let mut listener = listener;
            let mut direct_socket = None;
            let mut mapped_socket = None;
            if matches!(self.policy, Policy::All | Policy::Udp) {
                match udp::bind_candidates(self.route_ip) {
                    Ok((s, addresses)) => {
                        self.wire
                            .try_send(advertise_udp(self.cid, &addresses, nonce)?)?;
                        direct_socket = Some(s);
                    }
                    Err(e) => attempts.push(json!({"phase":"udp_bind","error":e.to_string()})),
                }
            }
            let mut mapping = if self.policy == Policy::All {
                Some(udp::MappingDiscovery::start(self.ues.clone())?)
            } else {
                None
            };
            let spawn_udp = |socket, candidates: Vec<TcpCandidate>, name: &'static str| {
                let endpoints: Vec<_> = candidates.iter().map(|c| c.endpoint).collect();
                let tx = tx.clone();
                let cancel = cancel.clone();
                let start = &start;
                thread::Builder::new()
                    .name(name.into())
                    .spawn_scoped(scope, move || {
                        let r = udp::Enet::accept(
                            socket,
                            &endpoints,
                            nonce,
                            Duration::from_secs(12),
                            Some(cancel),
                        )
                        .and_then(|s| start(name, PeerStream::Udp(s), TransportPath::DirectUdp));
                        let _ = tx.send((name, r));
                    })
            };
            let mut relay_started = false;
            let mut relay: Option<crate::peer::RelayOffer> = None;
            let mut relay_preparation = None;
            let mut relay_stream = None;
            let mut last_direct_start = None;
            loop {
                ensure!(!stop.load(Ordering::Relaxed), "cancelled");
                ensure!(Instant::now() < until, "incoming peer setup timeout");
                for (name, r) in rx.try_iter() {
                    match r {
                        Ok(mut p) => {
                            p.transport.tcp_candidates = tcp_candidates.clone();
                            p.transport.udp_candidates = udp_candidates.clone();
                            p.transport.mapped_udp_candidates = mapped_candidates.clone();
                            p.transport.attempts = attempts.clone();
                            p.transport
                                .attempts
                                .push(json!({"winner":name,"connection_id":self.cid}));
                            return Ok(p);
                        }
                        Err(e) => {
                            if name == "tcp" {
                                tcp_auth_active = false;
                            }
                            attempts.push(json!({"path":name,"error":format!("{e:#}")}));
                        }
                    }
                }
                for result in rendezvous_rx.try_iter() {
                    rendezvous_active -= 1;
                    match result {
                        Ok(stream) => validated_tcp.push_back(stream),
                        Err(error) => attempts
                            .push(json!({"phase":"tcp_rendezvous","error":format!("{error:#}")})),
                    }
                }
                // A partial or unrelated rendezvous must not hold the listener
                // while a usable candidate waits in its backlog. Admission is
                // still bounded to eight sockets and four validation workers.
                while rendezvous_active < DIRECT_TCP_LANES && accepted < 8 {
                    let Some(tcp_listener) = &listener else { break };
                    match tcp_listener.accept() {
                        Ok((socket, _)) => {
                            let sender = rendezvous_tx.clone();
                            let cancel = cancel.clone();
                            thread::Builder::new()
                                .name("tcp-rendezvous".into())
                                .spawn_scoped(scope, move || {
                                    let result = (|| {
                                        let mut stream = Framed::from_socket(
                                            socket,
                                            Duration::from_secs(3),
                                            Some(cancel),
                                        )?;
                                        stream.accept_rendezvous(peer_rid, cid)?;
                                        Ok(stream)
                                    })();
                                    let _ = sender.try_send(result);
                                })?;
                            rendezvous_active += 1;
                            accepted += 1;
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                        Err(error) => {
                            attempts.push(json!({"phase":"tcp_accept","error":error.to_string()}));
                            listener = None;
                            break;
                        }
                    }
                }
                if !tcp_auth_active {
                    if let Some(stream) = validated_tcp.pop_front() {
                        // Serialize the TCP service handshake so competing
                        // addresses cannot install two different TCP winners.
                        // Its old ten-second budget starts after queueing.
                        let stream = Framed::from_socket(
                            stream.socket,
                            Duration::from_secs(10),
                            Some(cancel.clone()),
                        )?;
                        let tx = tx.clone();
                        let start = &start;
                        thread::Builder::new()
                            .name("incoming-tcp".into())
                            .spawn_scoped(scope, move || {
                                let _ = tx.send((
                                    "tcp",
                                    start("tcp", PeerStream::Tcp(stream), TransportPath::DirectTcp),
                                ));
                            })?;
                        tcp_auth_active = true;
                    }
                }
                if let Some(result) = mapping.as_ref().and_then(udp::MappingDiscovery::poll) {
                    mapping = None;
                    match result {
                        Ok((socket, endpoint)) => {
                            self.wire.try_send(advertise_mapped_udp_with_nonce(
                                self.cid, endpoint, nonce,
                            )?)?;
                            mapped_socket = Some(socket);
                        }
                        Err(error) => attempts
                            .push(json!({"phase":"mapped_discovery","error":error.to_string()})),
                    }
                }
                // Candidates can arrive before our mapping result. Retain them
                // and start as soon as both halves are available.
                if !mapped_candidates.is_empty() {
                    if let Some(socket) = mapped_socket.take() {
                        last_direct_start = Some(Instant::now());
                        spawn_udp(socket, mapped_candidates.clone(), "mapped-udp")?;
                    }
                }
                if let Some(prepared) = relay_preparation.as_ref().and_then(RelayPreparation::poll)
                {
                    relay_preparation = None;
                    match prepared.result {
                        Ok(stream) => {
                            attempts.push(json!({"phase":prepared.name,"result":"paired","setup_ms":prepared.elapsed.as_millis()}));
                            relay_stream = Some(stream);
                        }
                        Err(error) => {
                            relay_started = true;
                            attempts.push(json!({"phase":prepared.name,"error":format!("{error:#}"),"setup_ms":prepared.elapsed.as_millis()}));
                        }
                    }
                }
                if !relay_started
                    && relay_stream.is_some()
                    && relay.as_ref().is_some_and(|offer| {
                        offer.ready(
                            Instant::now(),
                            last_direct_start,
                            self.policy == Policy::Relay,
                        )
                    })
                {
                    let mut stream = relay_stream.take().unwrap();
                    relay_started = true;
                    let tx = tx.clone();
                    let cancel = cancel.clone();
                    let start = &start;
                    thread::Builder::new()
                        .name("incoming-relay".into())
                        .spawn_scoped(scope, move || {
                            stream.set_stop(Some(cancel));
                            let r = start("relay", PeerStream::Tcp(stream), TransportPath::Relay);
                            let _ = tx.send(("relay", r));
                        })?;
                }
                let data = match self.records.recv_timeout(Duration::from_millis(10)) {
                    Ok(d) => d,
                    Err(mpsc::RecvTimeoutError::Timeout) => continue,
                    Err(_) => bail!("incoming coordinator closed"),
                };
                // The router has correlated CID; all endpoint parsers check it again.
                let operation = op(&data)?;
                match operation {
                    6 => {
                        if let Ok((c, _)) =
                            direct_candidates_on_route(&data, self.cid, 6, 0x1236, self.route_ip)
                        {
                            tcp_candidates = c;
                        }
                    }
                    29 | 7 => {
                        let candidates = if operation == 29 {
                            direct_candidates_on_route(&data, self.cid, 29, 0x127c, self.route_ip)
                                .map(|(c, _)| c)
                        } else {
                            incoming_mapping(&data, self.cid, self.route_ip).map(|c| vec![c])
                        };
                        let c = match candidates {
                            Ok(c) if !c.is_empty() => c,
                            _ => continue,
                        };
                        let socket = if operation == 29 {
                            udp_candidates = c.clone();
                            direct_socket.take()
                        } else {
                            mapped_candidates = c.clone();
                            mapped_socket.take()
                        };
                        if let Some(socket) = socket {
                            last_direct_start = Some(Instant::now());
                            spawn_udp(
                                socket,
                                c,
                                if operation == 29 { "udp" } else { "mapped-udp" },
                            )?;
                        }
                    }
                    23 if !relay_started && relay.is_none() => {
                        let f = records(&data)?;
                        ensure!(
                            int64(field(&f, 0x020001c1)?)? == self.cid,
                            "incoming relay correlation"
                        );
                        let f = records(field(&f, 0x123f)?)?;
                        let host = text(field(&f, 0x030001cb)?)?;
                        let port = int32(field(&f, 0x010001cc)?)?;
                        let ticket = field(&f, 0x090001ca)?.to_vec();
                        ensure!(
                            (1..=65535).contains(&port) && ticket.len() == 256,
                            "invalid incoming relay ticket"
                        );
                        if !matches!(self.policy, Policy::All | Policy::Relay) {
                            continue;
                        }
                        let offer = crate::peer::RelayOffer {
                            host,
                            port: port as u16,
                            ticket,
                            received_at: Instant::now(),
                        };
                        match RelayPreparation::start(&offer, until) {
                            Ok(preparation) => relay_preparation = Some(preparation),
                            Err(error) => {
                                relay_started = true;
                                attempts.push(
                                    json!({"phase":"relay_preparation","error":error.to_string()}),
                                );
                            }
                        }
                        relay = Some(offer);
                    }
                    _ => {}
                }
            }
            // Ensure every return/error cancels transports BEFORE scope joins them.
        });
        let mut channel = match result {
            Ok(channel) => channel,
            Err(error) => {
                attempts.push(json!({"phase":"incoming_setup","error":format!("{error:#}")}));
                let report = crate::peer::TransportReport {
                    incoming: true,
                    tcp_candidates,
                    udp_candidates,
                    mapped_udp_candidates: mapped_candidates,
                    attempts,
                    ..Default::default()
                };
                observe(&report);
                reports.json("transport.json", &report)?;
                return Err(error);
            }
        };
        channel.stream.set_stop(Some(stop));
        observe(&channel.transport);
        reports.json("transport.json", &channel.transport)?;
        Ok(channel)
    }
}
struct Cancel(Arc<AtomicBool>);
impl Drop for Cancel {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}
fn incoming_mapping(data: &[u8], cid: u64, route_ip: IpAddr) -> Result<TcpCandidate> {
    let f = records(data)?;
    ensure!(
        op(data)? == 7 && int64(field(&f, 0x020001c1)?)? == cid,
        "incoming mapped candidate correlation"
    );
    let f = records(field(&f, 0x1237)?)?;
    let ip: Ipv4Addr = text(field(&f, 0x030001c4)?)?.parse()?;
    candidate_address(ip.into(), route_ip)?;
    let port = int32(field(&f, 0x010001c5)?)?;
    ensure!(
        !ip.is_unspecified()
            && !ip.is_broadcast()
            && !ip.is_multicast()
            && (1..=65535).contains(&port),
        "invalid incoming mapped candidate"
    );
    Ok(TcpCandidate {
        endpoint: std::net::SocketAddr::new(ip.into(), port as u16),
        server_flag: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn offer(cid: u64, rid: u64) -> Vec<u8> {
        [
            u32v(SERVER_OP, 11),
            u64v(0x020001c1, cid),
            tlv(
                0x1235,
                &[
                    u64v(0x020001e1, rid),
                    tlv(0x0a0001cd, b"synthetic-password"),
                ]
                .concat(),
            ),
        ]
        .concat()
    }
    #[test]
    fn mailboxes_correlate_ids_preserve_secrets_and_expire_unknown_members() {
        let (wire, _) = mpsc::sync_channel(4);
        let mut h = Hub::new("127.0.0.1".parse().unwrap(), vec![], wire);
        h.ingest(&offer(7, 11)).unwrap();
        h.ingest(&offer(7, 12)).unwrap(); // duplicate CID cannot replace identity
        h.ingest(&offer(8, 11)).unwrap(); // duplicate peer cannot create another job
        assert_eq!(h.pending_rids(), BTreeSet::from([11]));
        let record = |cid| [u32v(SERVER_OP, 29), u64v(0x020001c1, cid), tlv(0x127c, &[])].concat();
        h.ingest(&record(99)).unwrap();
        h.ingest(&record(7)).unwrap();
        let setup = h.take(11, Policy::All).unwrap();
        assert_eq!(setup.cid, 7);
        assert_eq!(setup.records.try_recv().unwrap(), record(7));
        assert!(setup.records.try_recv().is_err());
        h.ingest(&record(7)).unwrap();
        assert_eq!(setup.records.try_recv().unwrap(), record(7));
        h.ingest(&offer(8, 11)).unwrap();
        assert!(h.pending_rids().is_empty());
        h.finish(11);
        h.ingest(&offer(8, 11)).unwrap();
        h.pending.get_mut(&8).unwrap().created = Instant::now() - Duration::from_secs(31);
        h.expire();
        assert!(h.take(11, Policy::All).is_none());
        for rid in 100..500 {
            h.ingest(&offer(rid, rid)).unwrap();
        }
        assert_eq!(h.pending.len(), MAX_PENDING_OFFERS);
        for _ in 0..100 {
            h.ingest(&record(100)).unwrap();
        }
        assert_eq!(h.pending[&100].records.len(), 8);
    }
    #[test]
    fn malformed_or_uncorrelated_records_never_make_an_offer() {
        let (wire, _) = mpsc::sync_channel(4);
        let mut h = Hub::new("127.0.0.1".parse().unwrap(), vec![], wire);
        assert!(h.ingest(&offer(0, 11)).is_err());
        assert!(h.ingest(&offer(1, 0)).is_err());
        assert!(h
            .ingest(&[offer(1, 11), u64v(0x020001c1, 2)].concat())
            .is_err());
        assert!(h.pending_rids().is_empty());
    }

    #[test]
    fn peer_indexes_release_rejected_finished_and_expired_connection_ids() {
        let (wire, _) = mpsc::sync_channel(4);
        let mut hub = Hub::new("127.0.0.1".parse().unwrap(), vec![], wire);
        for cid in 1..=4 {
            hub.ingest(&offer(cid, 11)).unwrap();
            let malformed_duplicate = [u32v(SERVER_OP, 11), u64v(0x020001c1, cid)].concat();
            hub.ingest(&malformed_duplicate).unwrap();
            if cid == 1 {
                hub.reject(11);
            } else {
                let setup = hub.take(11, Policy::All).unwrap();
                assert_eq!(setup.cid, cid);
                assert_eq!(setup.password, b"synthetic-password");
                if cid == 3 {
                    hub.routes.get_mut(&cid).unwrap().until = Instant::now();
                    hub.expire();
                } else {
                    hub.finish(11);
                }
            }
            assert!(hub.pending.is_empty() && hub.pending_by_rid.is_empty());
            assert!(hub.routes.is_empty() && hub.routes_by_rid.is_empty());
        }
    }

    #[test]
    fn parsed_followup_moves_its_original_allocation_into_the_setup_mailbox() {
        let (wire, _) = mpsc::sync_channel(4);
        let mut hub = Hub::new("127.0.0.1".parse().unwrap(), vec![], wire);
        hub.ingest_record(IncomingRecord::parse(offer(7, 11)).unwrap().unwrap())
            .unwrap();
        let setup = hub.take(11, Policy::All).unwrap();
        let data = [u32v(SERVER_OP, 29), u64v(0x020001c1, 7), tlv(0x127c, &[])].concat();
        let allocation = data.as_ptr();
        hub.ingest_record(IncomingRecord::parse(data).unwrap().unwrap())
            .unwrap();
        let received = setup.records.try_recv().unwrap();
        assert_eq!(received.as_ptr(), allocation);
    }
}

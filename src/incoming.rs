//! Incoming offers are accepted only from the authenticated attachment stream.
//! Membership is checked by the scheduler before an offer can open a listener.
use crate::{
    output::ReportDirectory,
    peer::{PeerChannel, PeerStream, TransportPath},
    protocol::*,
    scheduling::MAX_PENDING_OFFERS,
    session::Framed,
    udp,
};
use anyhow::{bail, ensure, Result};
use serde_json::json;
use std::{
    collections::{BTreeMap, BTreeSet},
    net::{IpAddr, Ipv4Addr, TcpListener},
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
/// Bounded mailbox; unknown/offline members may arrive before the membership
/// update, but they cannot start work. Duplicate CIDs never replace a secret.
pub struct Hub {
    pending: BTreeMap<u64, Offer>,
    routes: BTreeMap<u64, Route>,
    route_ip: IpAddr,
    ues: Vec<Ipv4Addr>,
    wire: SyncSender<Vec<u8>>,
}
impl Hub {
    pub fn new(route_ip: IpAddr, ues: Vec<Ipv4Addr>, wire: SyncSender<Vec<u8>>) -> Self {
        Self {
            pending: BTreeMap::new(),
            routes: BTreeMap::new(),
            route_ip,
            ues,
            wire,
        }
    }
    pub fn ingest(&mut self, data: &[u8]) -> Result<()> {
        let operation = op(data)?;
        if ![11, 6, 7, 23, 29].contains(&operation) {
            return Ok(());
        }
        ensure!(data.len() <= 65536, "incoming record size limit");
        let f = records(data)?;
        let cid = int64(field(&f, 0x020001c1)?)?;
        ensure!(cid != 0, "zero incoming connection ID");
        if operation == 11 {
            if self.pending.contains_key(&cid) || self.routes.contains_key(&cid) {
                return Ok(());
            }
            let f = records(field(&f, 0x1235)?)?;
            let rid = int64(field(&f, 0x020001e1)?)?;
            let password = field(&f, 0x0a0001cd)?;
            ensure!(
                rid != 0 && (6..=1024).contains(&password.len()),
                "invalid incoming credentials"
            );
            if self.pending.len() >= MAX_PENDING_OFFERS
                || self.pending.values().any(|p| p.rid == rid)
                || self.routes.values().any(|p| p.rid == rid)
            {
                return Ok(());
            }
            self.pending.insert(
                cid,
                Offer {
                    rid,
                    password: password.to_vec(),
                    created: Instant::now(),
                    records: vec![],
                },
            );
        } else if let Some(route) = self.routes.get(&cid) {
            // A full or closed mailbox cannot block the attachment heartbeat.
            let _ = route.tx.try_send(data.to_vec());
        } else if let Some(p) = self.pending.get_mut(&cid) {
            if p.records.len() < 8 {
                p.records.push(data.to_vec());
            }
        }
        Ok(())
    }
    pub fn expire(&mut self) {
        self.pending
            .retain(|_, p| p.created.elapsed() < Duration::from_secs(30));
        self.routes.retain(|_, p| Instant::now() < p.until);
    }
    pub fn pending_rids(&self) -> BTreeSet<u64> {
        self.pending.values().map(|p| p.rid).collect()
    }
    pub fn reject(&mut self, rid: u64) {
        self.pending.retain(|_, p| p.rid != rid);
    }
    pub fn finish(&mut self, rid: u64) {
        self.routes.retain(|_, p| p.rid != rid);
    }
    pub fn take(&mut self, rid: u64, policy: Policy) -> Option<Setup> {
        let cid = *self.pending.iter().find(|(_, p)| p.rid == rid)?.0;
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
            if matches!(self.policy, Policy::All | Policy::Tcp) {
                let listener = TcpListener::bind("[::]:0")?;
                listener.set_nonblocking(true)?;
                let addresses =
                    udp::local_candidates(self.route_ip, listener.local_addr()?.port())?;
                self.wire.try_send(advertise_tcp(self.cid, &addresses)?)?;
                let tx = tx.clone();
                let cancel = cancel.clone();
                let start = &start;
                scope.spawn(move || {
                    let r = (|| -> Result<PeerChannel> {
                        let mut accepted = 0;
                        loop {
                            ensure!(
                                !cancel.load(Ordering::Relaxed) && Instant::now() < until,
                                "incoming TCP cancelled/timeout"
                            );
                            match listener.accept() {
                                Ok((socket, _)) => {
                                    accepted += 1;
                                    ensure!(accepted <= 8, "incoming TCP accept budget");
                                    let mut stream = Framed::from_socket(
                                        socket,
                                        Duration::from_secs(3),
                                        Some(cancel.clone()),
                                    )?;
                                    if stream.accept_rendezvous(peer_rid, cid).is_err() {
                                        continue;
                                    }
                                    // Full SH/service gets a separate bounded deadline.
                                    let stream = Framed::from_socket(
                                        stream.socket,
                                        Duration::from_secs(10),
                                        Some(cancel.clone()),
                                    )?;
                                    return start(
                                        "tcp",
                                        PeerStream::Tcp(stream),
                                        TransportPath::DirectTcp,
                                    );
                                }
                                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                                    thread::sleep(Duration::from_millis(5))
                                }
                                Err(e) => return Err(e.into()),
                            }
                        }
                    })();
                    let _ = tx.send(("tcp", r));
                });
            }
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
                scope.spawn(move || {
                    let r = udp::Enet::accept(
                        socket,
                        &endpoints,
                        nonce,
                        Duration::from_secs(12),
                        Some(cancel),
                    )
                    .and_then(|s| start(name, PeerStream::Udp(s), TransportPath::DirectUdp));
                    let _ = tx.send((name, r));
                });
            };
            let mut relay_started = false;
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
                        Err(e) => attempts.push(json!({"path":name,"error":format!("{e:#}")})),
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
                        spawn_udp(socket, mapped_candidates.clone(), "mapped-udp");
                    }
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
                        if let Ok((c, _)) = direct_candidates(&data, self.cid, 6, 0x1236) {
                            tcp_candidates = c;
                        }
                    }
                    29 | 7 => {
                        let candidates = if operation == 29 {
                            direct_candidates(&data, self.cid, 29, 0x127c).map(|(c, _)| c)
                        } else {
                            incoming_mapping(&data, self.cid).map(|c| vec![c])
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
                            spawn_udp(
                                socket,
                                c,
                                if operation == 29 { "udp" } else { "mapped-udp" },
                            );
                        }
                    }
                    23 if !relay_started => {
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
                        relay_started = true;
                        let tx = tx.clone();
                        let cancel = cancel.clone();
                        let start = &start;
                        scope.spawn(move || {
                            let r = (|| -> Result<PeerChannel> {
                                let mut s = Framed::connect_with_stop(
                                    &host,
                                    port as u16,
                                    Duration::from_secs(30),
                                    Some(cancel),
                                )?;
                                s.send(
                                    &[
                                        u32v(0x010001df, 1),
                                        u32v(0x0100032b, 2),
                                        tlv(0x090001ca, &ticket),
                                    ]
                                    .concat(),
                                )?;
                                // TRS acknowledges the ticket only once both
                                // sides have arrived. The initiator may still
                                // be waiting for late direct candidates.
                                let ack_until = Instant::now() + Duration::from_secs(25);
                                while !s.ready(100)? {
                                    ensure!(
                                        Instant::now() < ack_until,
                                        "incoming relay pairing timeout"
                                    );
                                }
                                ensure!(
                                    int32(field(&records(&s.receive(65536)?)?, 0x010001df)?)? == 2,
                                    "incoming relay ticket rejected"
                                );
                                start("relay", PeerStream::Tcp(s), TransportPath::Relay)
                            })();
                            let _ = tx.send(("relay", r));
                        });
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
fn incoming_mapping(data: &[u8], cid: u64) -> Result<TcpCandidate> {
    let f = records(data)?;
    ensure!(
        op(data)? == 7 && int64(field(&f, 0x020001c1)?)? == cid,
        "incoming mapped candidate correlation"
    );
    let f = records(field(&f, 0x1237)?)?;
    let ip: Ipv4Addr = text(field(&f, 0x030001c4)?)?.parse()?;
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
}

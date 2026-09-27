//! Authenticated candidate selection, direct TCP/UDP, and TCP relay fallback.
use crate::{
    crypto::{random, Channel, ShClient, ShServer},
    output::ReportDirectory,
    protocol::*,
    session::{Framed, Session},
    tunnel,
};
use anyhow::{bail, ensure, Result};
use serde::Serialize;
use std::{
    net::Ipv4Addr,
    sync::{atomic::AtomicBool, Arc},
    time::{Duration, Instant},
};
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum TransportPath {
    DirectTcp,
    DirectUdp,
    Relay,
}
impl TransportPath {
    pub fn label(self) -> &'static str {
        match self {
            Self::DirectTcp => "Direct TCP",
            Self::DirectUdp => "Direct UDP",
            Self::Relay => "Relay",
        }
    }
}
#[derive(Clone, Default, Serialize)]
pub struct TransportReport {
    pub incoming: bool,
    pub path: Option<TransportPath>,
    pub tcp_candidates: Vec<TcpCandidate>,
    pub udp_candidates: Vec<TcpCandidate>,
    pub mapped_udp_candidates: Vec<TcpCandidate>,
    pub attempts: Vec<serde_json::Value>,
    pub authenticated: bool,
    pub service_connected: bool,
    pub endpoint: Option<std::net::SocketAddr>,
}
pub enum PeerStream {
    Tcp(Framed),
    Udp(crate::udp::Enet),
}
impl PeerStream {
    pub fn send(&mut self, data: &[u8]) -> Result<()> {
        match self {
            Self::Tcp(s) => s.send(data),
            Self::Udp(s) => s.send(data),
        }
    }
    pub fn receive(&mut self, max: usize) -> Result<Vec<u8>> {
        match self {
            Self::Tcp(s) => s.receive(max),
            Self::Udp(s) => s.receive(max),
        }
    }
    pub fn ready(&mut self, timeout_ms: i32) -> Result<bool> {
        match self {
            Self::Tcp(s) => s.ready(timeout_ms),
            Self::Udp(s) => s.ready(timeout_ms),
        }
    }
    pub fn set_stop(&mut self, stop: Option<Arc<AtomicBool>>) {
        match self {
            Self::Tcp(s) => s.set_stop(stop),
            Self::Udp(s) => s.set_stop(stop),
        }
    }
    pub fn has_room(&self, len: usize) -> bool {
        match self {
            Self::Tcp(_) => true,
            Self::Udp(s) => s.has_room(len),
        }
    }
    pub fn sustain(&mut self) {
        match self {
            Self::Tcp(s) => s.sustain(),
            Self::Udp(s) => s.sustain(),
        }
    }
    pub fn endpoint(&self) -> Result<std::net::SocketAddr> {
        match self {
            Self::Tcp(s) => Ok(s.socket.peer_addr()?),
            Self::Udp(s) => Ok(s.endpoint()),
        }
    }
}
pub struct PeerChannel {
    pub peer: Peer,
    pub mac: [u8; 6],
    pub version: u32,
    pub stream: PeerStream,
    pub transport: TransportReport,
    channel: Channel,
    _coordinator: Option<Session>,
}
pub(crate) struct RelayOffer {
    pub host: String,
    pub port: u16,
    pub ticket: Vec<u8>,
    pub received_at: Instant,
}
const DIRECT_HEAD_START: Duration = Duration::from_secs(4);
const MAX_RELAY_WAIT: Duration = Duration::from_secs(8);

impl RelayOffer {
    pub(crate) fn ready(
        &self,
        now: Instant,
        last_direct_start: Option<Instant>,
        direct_exhausted: bool,
    ) -> bool {
        // Late mapped UDP candidates deserve their own head start. Bound the
        // extension so a silent direct path cannot suppress the relay forever.
        let start_at = (last_direct_start
            .unwrap_or(self.received_at)
            .max(self.received_at)
            + DIRECT_HEAD_START)
            .min(self.received_at + MAX_RELAY_WAIT);
        direct_exhausted || now >= start_at
    }
}

#[derive(Clone)]
struct PeerDial<'a> {
    identity: &'a Identity,
    own_ip: Ipv4Addr,
    peer: Peer,
    password: Vec<u8>,
    reports: &'a ReportDirectory,
}
impl PeerDial<'_> {
    fn authenticate(
        &self,
        stream: PeerStream,
        path: TransportPath,
        name: &str,
    ) -> Result<PeerChannel> {
        PeerChannel::authenticate_peer(
            stream,
            path,
            &self.password,
            self.identity,
            self.own_ip,
            self.peer.clone(),
            &self.reports.child(name)?,
        )
    }
}

impl PeerChannel {
    pub fn connect(
        identity: &Identity,
        modulus: &[u8],
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        duration: Duration,
    ) -> Result<Self> {
        Self::connect_with_stop(identity, modulus, own_ip, peer, reports, duration, None)
    }
    #[allow(clippy::too_many_arguments)]
    pub fn connect_with_stop(
        identity: &Identity,
        modulus: &[u8],
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        Self::connect_observed(
            identity,
            modulus,
            own_ip,
            peer,
            reports,
            duration,
            stop,
            |_| {},
        )
    }
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn connect_observed(
        identity: &Identity,
        modulus: &[u8],
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
        observe: impl FnOnce(&TransportReport),
    ) -> Result<Self> {
        let mut report = TransportReport::default();
        let result = Self::connect_inner(
            identity,
            modulus,
            own_ip,
            peer,
            reports,
            duration,
            stop,
            &mut report,
        );
        observe(&report);
        reports.json("transport.json", &report)?;
        result
    }
    #[allow(clippy::too_many_arguments)]
    fn connect_inner(
        identity: &Identity,
        modulus: &[u8],
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
        report: &mut TransportReport,
    ) -> Result<Self> {
        let started = Instant::now();
        let host = peer
            .server
            .as_deref()
            .ok_or_else(|| anyhow::anyhow!("peer has no authenticated server"))?;
        let mut coord = Session::authenticate_with_stop(
            host,
            identity,
            modulus,
            5,
            reports,
            duration,
            stop.clone(),
        )?;
        coord.send(&login(
            &identity.node_name,
            coord.latency,
            5,
            Some(peer.rid),
        )?)?;
        Self::negotiate(
            identity,
            own_ip,
            peer,
            reports,
            duration.saturating_sub(started.elapsed()),
            stop,
            coord,
            report,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn negotiate(
        identity: &Identity,
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
        mut coord: Session,
        report: &mut TransportReport,
    ) -> Result<Self> {
        let until = Instant::now() + duration;
        let mut discovery_until = until.min(Instant::now() + Duration::from_secs(30));
        let mut pending: Option<(u64, Vec<u8>)> = None;
        let mut server_id = None;
        let mut ues = vec![];
        let mut mapping = None;
        let mut udp_socket = None;
        let mut mapped_socket = None;
        let mut udp_nonce = None;
        let mut mapped_nonce = None;
        let mut tcp_started = false;
        let mut tcp_received = false;
        let mut udp_received = false;
        let mut mapped_received = true;
        let mut relay = None;
        let mut relay_started = false;
        let mut last_direct_start = None;
        let mut records_received = 0;
        let mut channel = std::thread::scope(|scope| -> Result<Self> {
            // This guard cancels every losing transport before scope joins it,
            // including on malformed coordinator messages or user cancellation.
            let mut tasks = crate::scheduling::SetupTasks::<Self>::new();
            loop {
                ensure!(
                    !stop
                        .as_ref()
                        .is_some_and(|s| s.load(std::sync::atomic::Ordering::Relaxed)),
                    "cancelled"
                );
                ensure!(Instant::now() < until, "peer setup timeout");
                while let Some(completed) = tasks.poll() {
                    match completed.result {
                        Ok(channel) => {
                            report.attempts.push(serde_json::json!({
                                "phase":completed.name, "result":"authenticated_service",
                                "setup_ms":completed.elapsed.as_millis(), "endpoint":channel.transport.endpoint,
                            }));
                            return Ok(channel);
                        }
                        Err(error) => report.attempts.push(serde_json::json!({
                            "phase":completed.name, "error":format!("{error:#}"),
                            "setup_ms":completed.elapsed.as_millis(),
                        })),
                    }
                }
                if let Some(result) = mapping
                    .as_ref()
                    .and_then(crate::udp::MappingDiscovery::poll)
                {
                    mapping = None;
                    match result {
                        Ok((socket, endpoint)) => {
                            let cid = pending.as_ref().unwrap().0;
                            coord.send(&advertise_mapped_udp(cid, endpoint)?)?;
                            mapped_socket = Some(socket);
                            mapped_received = mapped_nonce.is_some();
                            report.attempts.push(serde_json::json!({"phase":"ues_mapping","endpoint":endpoint,"result":"two_servers_agree"}));
                        }
                        Err(error) => report.attempts.push(
                            serde_json::json!({"phase":"ues_mapping","error":error.to_string()}),
                        ),
                    }
                }
                if let Some((cid, password)) = &pending {
                    let dial = || PeerDial {
                        identity,
                        own_ip,
                        peer: peer.clone(),
                        password: password.clone(),
                        reports,
                    };
                    if !tcp_started && !report.tcp_candidates.is_empty() {
                        if let Some(server_id) = server_id {
                            tcp_started = true;
                            last_direct_start = Some(Instant::now());
                            let mut candidates = report.tcp_candidates.clone();
                            candidates.sort_by_key(|c| match c.endpoint.ip() {
                                std::net::IpAddr::V4(v) => {
                                    v.is_private() || v.is_loopback() || v.is_link_local()
                                }
                                std::net::IpAddr::V6(v) => {
                                    v.is_unique_local()
                                        || v.is_loopback()
                                        || v.segments()[0..2] == [0x2001, 0]
                                }
                            });
                            // Two lanes prevent an unreachable first address
                            // from hiding a usable address of another family.
                            let lanes = candidates.len().min(2);
                            for (lane, name) in ["direct-tcp-0", "direct-tcp-1"]
                                .into_iter()
                                .take(lanes)
                                .enumerate()
                            {
                                let candidates: Vec<_> = candidates
                                    .iter()
                                    .skip(lane)
                                    .step_by(lanes)
                                    .cloned()
                                    .collect();
                                let dial = dial();
                                let cid = *cid;
                                tasks.spawn(scope, name, move |cancel| {
                                    let deadline =
                                        until.min(Instant::now() + Duration::from_secs(8));
                                    let mut errors = vec![];
                                    for candidate in candidates {
                                        if Instant::now() >= deadline
                                            || cancel.load(std::sync::atomic::Ordering::Relaxed)
                                        {
                                            break;
                                        }
                                        let result = (|| {
                                            let mut stream = Framed::connect_timeout(
                                                &candidate.endpoint.ip().to_string(),
                                                candidate.endpoint.port(),
                                                deadline
                                                    .saturating_duration_since(Instant::now())
                                                    .min(Duration::from_secs(3)),
                                                Some(cancel.clone()),
                                                Duration::from_secs(2),
                                            )?;
                                            stream.peer_rendezvous(identity.rid, cid, server_id)?;
                                            dial.authenticate(
                                                PeerStream::Tcp(stream),
                                                TransportPath::DirectTcp,
                                                name,
                                            )
                                        })();
                                        match result {
                                            Ok(channel) => return Ok(channel),
                                            Err(error) => errors
                                                .push(format!("{}: {error:#}", candidate.endpoint)),
                                        }
                                    }
                                    bail!("direct TCP candidates failed: {}", errors.join("; "))
                                })?;
                            }
                        }
                    }
                    for (name, socket, candidates, nonce) in [
                        (
                            "direct-udp",
                            &mut udp_socket,
                            &report.udp_candidates,
                            udp_nonce,
                        ),
                        (
                            "mapped-udp",
                            &mut mapped_socket,
                            &report.mapped_udp_candidates,
                            mapped_nonce,
                        ),
                    ] {
                        if let Some(nonce) = nonce.filter(|_| !candidates.is_empty()) {
                            if let Some(socket) = socket.take() {
                                last_direct_start = Some(Instant::now());
                                let endpoints: Vec<_> =
                                    candidates.iter().map(|c| c.endpoint).collect();
                                let dial = dial();
                                tasks.spawn(scope, name, move |cancel| {
                                    let stream = crate::udp::Enet::connect(
                                        socket,
                                        &endpoints,
                                        nonce,
                                        until
                                            .saturating_duration_since(Instant::now())
                                            .min(Duration::from_secs(8)),
                                        Some(cancel),
                                    )?;
                                    dial.authenticate(
                                        PeerStream::Udp(stream),
                                        TransportPath::DirectUdp,
                                        name,
                                    )
                                })?;
                            }
                        }
                    }
                    let direct_exhausted = tasks.is_idle()
                        && mapping.is_none()
                        && tcp_received
                        && udp_received
                        && mapped_received;
                    if !relay_started
                        && relay.as_ref().is_some_and(|r: &RelayOffer| {
                            r.ready(Instant::now(), last_direct_start, direct_exhausted)
                        })
                    {
                        relay_started = true;
                        let offer = relay.take().unwrap();
                        let dial = dial();
                        tasks.spawn(scope, "relay", move |cancel| {
                            let mut stream = Framed::connect_with_stop(
                                &offer.host,
                                offer.port,
                                until.saturating_duration_since(Instant::now()),
                                Some(cancel),
                            )?;
                            stream.send(
                                &[
                                    u32v(0x010001df, 1),
                                    u32v(0x0100032b, 2),
                                    tlv(0x090001ca, &offer.ticket),
                                ]
                                .concat(),
                            )?;
                            // The other member may still be waiting for its
                            // authenticated membership notification.
                            let pairing_until = until.min(Instant::now() + Duration::from_secs(25));
                            while !stream.ready(50)? {
                                ensure!(Instant::now() < pairing_until, "relay pairing timeout");
                            }
                            ensure!(
                                int32(field(&records(&stream.receive(65536)?)?, 0x010001df)?)? == 2,
                                "relay ticket rejected"
                            );
                            dial.authenticate(
                                PeerStream::Tcp(stream),
                                TransportPath::Relay,
                                "relay",
                            )
                        })?;
                    }
                }
                let discovery_complete =
                    tcp_received && udp_received && mapped_received && relay_started;
                if discovery_complete || records_received >= 32 || Instant::now() >= discovery_until
                {
                    if tasks.is_idle() && mapping.is_none() && (relay_started || relay.is_none()) {
                        bail!("all peer transports failed or no authenticated candidates arrived");
                    }
                    std::thread::sleep(Duration::from_millis(5));
                    continue;
                }
                if !coord.stream.ready(10)? {
                    continue;
                }
                let data = coord.receive()?;
                records_received += 1;
                let fields = records(&data)?;
                match op(&data)? {
                    21 => {
                        server_id = optional(&fields, 0x0100025b)?.map(int32).transpose()?;
                        ues = ues_hosts(&data)?;
                    }
                    11 => {
                        ensure!(pending.is_none(), "duplicate NewConnection");
                        let cid = int64(field(&fields, 0x020001c1)?)?;
                        let f = records(field(&fields, 0x1235)?)?;
                        ensure!(
                            int64(field(&f, 0x020001e1)?)? == peer.rid,
                            "NewConnection target mismatch"
                        );
                        let password = field(&f, 0x0a0001cd)?.to_vec();
                        ensure!(
                            cid != 0 && (6..=1024).contains(&password.len()),
                            "invalid connection credentials"
                        );
                        pending = Some((cid, password));
                        coord.send(&request_tcp_candidates(cid))?;
                        match crate::udp::bind_candidates(coord.stream.socket.local_addr()?.ip()) {
                            Ok((socket, endpoints)) => {
                                coord.send(&advertise_udp(cid, &endpoints, 0)?)?;
                                udp_socket = Some(socket);
                            }
                            Err(error) => report.attempts.push(
                                serde_json::json!({"phase":"local_bind","error":error.to_string()}),
                            ),
                        }
                        coord.send(&request_relay(cid))?;
                        mapping = Some(crate::udp::MappingDiscovery::start(ues.clone())?);
                    }
                    operation @ (6 | 29) => {
                        let cid = pending
                            .as_ref()
                            .ok_or_else(|| anyhow::anyhow!("candidates before NewConnection"))?
                            .0;
                        match direct_candidates(
                            &data,
                            cid,
                            operation,
                            if operation == 6 { 0x1236 } else { 0x127c },
                        ) {
                            Ok((candidates, exclusions)) => {
                                if operation == 6 {
                                    report.tcp_candidates = candidates;
                                    tcp_received = true;
                                } else {
                                    report.udp_candidates = candidates;
                                    udp_received = true;
                                }
                                for error in exclusions {
                                    report.attempts.push(serde_json::json!({"phase":"candidate_exclusion","error":error}));
                                }
                            }
                            Err(error) => {
                                report.attempts.push(serde_json::json!({"phase":"candidate_validation","error":error.to_string()}));
                                continue;
                            }
                        }
                        if operation == 29 {
                            udp_nonce = optional(&fields, 0x127c)?
                                .map(records)
                                .transpose()?
                                .map(|f| optional(&f, 0x0100020a)?.map(int32).transpose())
                                .transpose()?
                                .flatten()
                                .filter(|n| (1..=65535).contains(n))
                                .map(|n| n as u16);
                        }
                    }
                    7 => {
                        let cid = pending
                            .as_ref()
                            .ok_or_else(|| {
                                anyhow::anyhow!("mapped candidates before NewConnection")
                            })?
                            .0;
                        match mapped_udp_candidate(&data, cid) {
                            Ok((candidate, nonce)) => {
                                report.mapped_udp_candidates = vec![candidate];
                                mapped_nonce = Some(nonce);
                                mapped_received = true;
                            }
                            Err(error) => report.attempts.push(serde_json::json!({"phase":"mapped_candidate_validation","error":error.to_string()})),
                        }
                    }
                    23 => {
                        let cid = pending
                            .as_ref()
                            .ok_or_else(|| anyhow::anyhow!("relay before NewConnection"))?
                            .0;
                        ensure!(
                            int64(field(&fields, 0x020001c1)?)? == cid,
                            "relay correlation mismatch"
                        );
                        let f = records(field(&fields, 0x123f)?)?;
                        let host = text(field(&f, 0x030001cb)?)?;
                        let port = int32(field(&f, 0x010001cc)?)?;
                        let ticket = field(&f, 0x090001ca)?.to_vec();
                        ensure!(
                            (1..=65535).contains(&port) && ticket.len() == 256,
                            "invalid relay ticket"
                        );
                        if !relay_started && relay.is_none() {
                            // Keep accepting delayed direct candidates while the
                            // relay waits or pairs; early TCP must not cut off UDP.
                            discovery_until =
                                discovery_until.min(Instant::now() + Duration::from_secs(12));
                            relay = Some(RelayOffer {
                                host,
                                port: port as u16,
                                ticket,
                                received_at: Instant::now(),
                            });
                        }
                    }
                    16 | 44 => bail!("coordinator refused connection: operation {}", op(&data)?),
                    _ => {}
                }
            }
        })?;
        channel.stream.set_stop(stop);
        channel.transport.tcp_candidates = report.tcp_candidates.clone();
        channel.transport.udp_candidates = report.udp_candidates.clone();
        channel.transport.mapped_udp_candidates = report.mapped_udp_candidates.clone();
        channel.transport.attempts = report.attempts.clone();
        *report = channel.transport.clone();
        channel._coordinator = Some(coord);
        Ok(channel)
    }
    fn authenticate_peer(
        mut stream: PeerStream,
        path: TransportPath,
        password: &[u8],
        identity: &Identity,
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
    ) -> Result<Self> {
        if path != TransportPath::Relay {
            reports.json("peer.json", &peer)?;
        }
        let mut sh = ShClient::new(peer.rid, password)?;
        stream.send(&sh.start()?)?;
        let sh2 = stream.receive(65536)?;
        stream.send(&sh.parameters(&sh2)?)?;
        let sh4 = stream.receive(65536)?;
        stream.send(&sh.challenge(&sh4)?)?;
        let sh6 = stream.receive(65536)?;
        let key = sh.confirm(&sh6)?;
        let mut p = Self {
            peer,
            mac: [0; 6],
            version: 0,
            transport: TransportReport {
                path: Some(path),
                authenticated: true,
                endpoint: Some(stream.endpoint()?),
                ..Default::default()
            },
            stream,
            channel: Channel::new(&key[..32])?,
            _coordinator: None,
        };
        p.send(&[0xef, 0xbe, 0xad, 0xde])?;
        let mut guid = random(16);
        guid[7] = (guid[7] & 15) | 0x40;
        guid[8] = (guid[8] & 63) | 0x80;
        p.send(&guid)?;
        p.send(&tunnel::syn(identity.rid, &tunnel::mac(own_ip)))?;
        let (ack, mac, version) = tunnel::synack(&p.receive()?)?;
        p.mac = mac;
        p.version = version;
        p.send(&ack)?;
        p.transport.service_connected = true;
        Ok(p)
    }
    pub fn accept_authenticated(
        mut stream: PeerStream,
        path: TransportPath,
        password: &[u8],
        own_rid: u64,
        own_ip: Ipv4Addr,
        peer: Peer,
        reports: &ReportDirectory,
    ) -> Result<Self> {
        reports.json("peer.json", &peer)?;
        reports.json(
            "incoming.json",
            &serde_json::json!({"own_rid":own_rid,"incoming":true}),
        )?;
        let mut sh = ShServer::new(own_rid, password)?;
        // Relay tickets can arrive before the initiator exhausts its direct
        // candidates. Keep the bounded incoming channel ready for that fallback.
        let hello_until = Instant::now() + Duration::from_secs(25);
        while !stream.ready(100)? {
            ensure!(Instant::now() < hello_until, "incoming SH hello timeout");
        }
        let hello = stream.receive(65536)?;
        stream.send(&sh.hello(&hello)?)?;
        let public = stream.receive(65536)?;
        stream.send(&sh.public(&public)?)?;
        let proof = stream.receive(65536)?;
        let (confirmation, key) = sh.proof(&proof)?;
        stream.send(&confirmation)?;
        let mut p = Self {
            peer,
            mac: [0; 6],
            version: 0,
            transport: TransportReport {
                incoming: true,
                path: Some(path),
                authenticated: true,
                endpoint: Some(stream.endpoint()?),
                ..Default::default()
            },
            stream,
            channel: Channel::new(&key[..32])?,
            _coordinator: None,
        };
        ensure!(
            p.receive()? == [0xef, 0xbe, 0xad, 0xde],
            "incoming peer race marker mismatch"
        );
        ensure!(p.receive()?.len() == 16, "incoming peer race GUID length");
        let (synack, mac, version) =
            tunnel::accept_syn(&p.receive()?, p.peer.rid, &tunnel::mac(own_ip))?;
        p.send(&synack)?;
        tunnel::accept_ack(&p.receive()?, version)?;
        p.mac = mac;
        p.version = version;
        p.transport.service_connected = true;
        Ok(p)
    }
    /// Returns false when the transport window is full and the message was
    /// dropped. The check precedes encryption: the CBC chain continues across
    /// messages, so a ciphertext that is never sent would desynchronise the peer.
    pub fn send(&mut self, plain: &[u8]) -> Result<bool> {
        if !self.stream.has_room((plain.len() + 9).div_ceil(16) * 16) {
            return Ok(false);
        }
        let ct = self.channel.encrypt(plain)?;
        self.stream.send(&ct)?;
        Ok(true)
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        let ct = self.stream.receive(65536)?;
        let pt = self.channel.decrypt(&ct)?;
        Ok(pt)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::udp::Enet;
    use std::net::UdpSocket;

    #[test]
    fn a_full_udp_window_drops_before_advancing_the_encryption_chain() {
        let a = UdpSocket::bind("[::1]:0").unwrap();
        let b = UdpSocket::bind("[::1]:0").unwrap();
        let aa = a.local_addr().unwrap();
        let ba = b.local_addr().unwrap();
        let incoming = std::thread::spawn(move || {
            Enet::accept(b, &[aa], 123, Duration::from_secs(5), None).unwrap()
        });
        let mut outgoing = Enet::connect(a, &[ba], 123, Duration::from_secs(5), None).unwrap();
        let mut receiver = incoming.join().unwrap();
        outgoing.sustain();
        receiver.sustain();
        let key = [7; 32];
        let mut decrypt = Channel::new(&key).unwrap();
        let mut sender = PeerChannel {
            peer: Peer {
                rid: 1,
                name: "Synthetic peer".into(),
                vip: Ipv4Addr::new(26, 0, 0, 1),
                server: None,
                state: 1,
                network_ids: Default::default(),
            },
            mac: [0; 6],
            version: 0,
            stream: PeerStream::Udp(outgoing),
            transport: TransportReport::default(),
            channel: Channel::new(&key).unwrap(),
            _coordinator: None,
        };

        // Deliver every message but leave ACKs unread on the sender, filling
        // its window without losing packets to the local socket buffers.
        for value in 0..=255u8 {
            assert!(sender.send(&[value]).unwrap());
            let ciphertext = receiver.receive(65536).unwrap();
            assert_eq!(decrypt.decrypt(&ciphertext).unwrap(), [value]);
        }
        assert!(!sender.send(b"dropped").unwrap());
        sender.stream.ready(0).unwrap();
        assert!(sender.send(b"resumed").unwrap());
        let deadline = Instant::now() + Duration::from_secs(5);
        while !receiver.ready(0).unwrap() {
            assert!(
                Instant::now() < deadline,
                "resumed message was not delivered"
            );
            sender.stream.ready(0).unwrap();
            std::thread::sleep(Duration::from_millis(1));
        }
        let ciphertext = receiver.receive(65536).unwrap();
        assert_eq!(decrypt.decrypt(&ciphertext).unwrap(), b"resumed");
    }
}

#[cfg(test)]
mod connection_tests {
    use super::*;
    use std::{
        io::Read,
        net::{SocketAddr, TcpListener, TcpStream},
        sync::{atomic::Ordering, mpsc},
        thread,
    };

    const CID: u64 = 99;
    const PASSWORD: &[u8] = b"synthetic-peer-password";

    fn peer(rid: u64) -> Peer {
        Peer {
            rid,
            name: "synthetic".into(),
            vip: Ipv4Addr::new(26, 0, 0, rid as u8),
            server: Some("127.0.0.1".into()),
            state: 1,
            network_ids: Default::default(),
        }
    }
    fn identity() -> Identity {
        let mut identity = Identity::bootstrap("synthetic", "127.0.0.1").unwrap();
        identity.rid = 11;
        identity.vip = peer(11).vip;
        identity
    }
    fn coordinator() -> (Session, Session) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let local = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let remote = listener.accept().unwrap().0;
        let session = |socket| Session {
            stream: Framed::from_socket(socket, Duration::from_secs(5), None).unwrap(),
            channel: Channel::new(&[17; 32]).unwrap(),
            latency: 0,
            ues: vec![],
        };
        let mut remote = session(remote);
        let login = [u32v(SERVER_OP, 21), u32v(0x0100025b, 17)];
        remote.send(&login.concat()).unwrap();
        remote
            .send(
                &[
                    u32v(SERVER_OP, 11),
                    u64v(0x020001c1, CID),
                    tlv(
                        0x1235,
                        &[u64v(0x020001e1, 42), tlv(0x0a0001cd, PASSWORD)].concat(),
                    ),
                ]
                .concat(),
            )
            .unwrap();
        (session(local), remote)
    }
    fn candidates(remote: &mut Session, endpoints: &[SocketAddr]) {
        let body: Vec<_> = endpoints
            .iter()
            .flat_map(|endpoint| {
                tlv(
                    0x127d,
                    &[
                        textv(0x030001c2, &endpoint.ip().to_string()).unwrap(),
                        u32v(0x010001c3, endpoint.port() as u32),
                    ]
                    .concat(),
                )
            })
            .collect();
        remote
            .send(
                &[
                    u32v(SERVER_OP, 6),
                    u64v(0x020001c1, CID),
                    tlv(0x1236, &body),
                ]
                .concat(),
            )
            .unwrap();
    }
    fn respond(socket: TcpStream, path: TransportPath) {
        let mut stream = Framed::from_socket(socket, Duration::from_secs(5), None).unwrap();
        if path == TransportPath::Relay {
            let ticket = stream.receive(65536).unwrap();
            assert_eq!(
                field(&records(&ticket).unwrap(), 0x090001ca).unwrap(),
                &[23; 256]
            );
            stream.send(&u32v(0x010001df, 2)).unwrap();
        } else {
            stream.accept_rendezvous(11, CID).unwrap();
        }
        let mut channel = PeerChannel::accept_authenticated(
            PeerStream::Tcp(stream),
            path,
            PASSWORD,
            42,
            peer(42).vip,
            peer(11),
            &ReportDirectory::disabled(),
        )
        .unwrap();
        let packet = channel.receive().unwrap();
        let tunnel::Packet::Frames(frames) = tunnel::decode(&packet).unwrap() else {
            panic!("expected Ethernet announcement");
        };
        assert_eq!(frames, [tunnel::gratuitous_arp(peer(11).vip).as_slice()]);
        assert!(crate::runtime::valid_inbound(
            frames[0],
            peer(42).vip,
            peer(11).vip,
            channel.mac,
        ));
        channel.send(b"confirmed").unwrap();
    }
    fn responder(listener: TcpListener, path: TransportPath) -> thread::JoinHandle<()> {
        thread::spawn(move || respond(listener.accept().unwrap().0, path))
    }
    fn relay_offer(remote: &mut Session, port: u16) {
        remote
            .send(
                &[
                    u32v(SERVER_OP, 23),
                    u64v(0x020001c1, CID),
                    tlv(
                        0x123f,
                        &[
                            textv(0x030001cb, "127.0.0.1").unwrap(),
                            u32v(0x010001cc, port as u32),
                            tlv(0x090001ca, &[23; 256]),
                        ]
                        .concat(),
                    ),
                ]
                .concat(),
            )
            .unwrap();
    }
    fn stalled_listener() -> (SocketAddr, mpsc::Receiver<()>, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let (sender, receiver) = mpsc::channel();
        let worker = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut preamble = [0; 24];
            socket.read_exact(&mut preamble).unwrap();
            sender.send(()).unwrap();
            let mut byte = [0];
            assert_eq!(
                socket.read(&mut byte).unwrap(),
                0,
                "losing socket must be closed"
            );
        });
        (address, receiver, worker)
    }
    fn exchange(mut channel: PeerChannel) {
        channel
            .send(&tunnel::encode(&tunnel::gratuitous_arp(peer(11).vip)).unwrap())
            .unwrap();
        assert_eq!(channel.receive().unwrap(), b"confirmed");
    }

    #[test]
    fn direct_tcp_starts_before_other_candidates_and_bypasses_stalled_first_address() {
        let (coord, mut remote) = coordinator();
        let (stalled, entered, loser) = stalled_listener();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        candidates(&mut remote, &[stalled, listener.local_addr().unwrap()]);
        let server = responder(listener, TransportPath::DirectTcp);
        let mut report = TransportReport::default();
        let started = Instant::now();
        let channel = PeerChannel::negotiate(
            &identity(),
            peer(11).vip,
            peer(42),
            &ReportDirectory::disabled(),
            Duration::from_secs(5),
            None,
            coord,
            &mut report,
        )
        .unwrap();
        assert!(
            started.elapsed() < Duration::from_millis(1500),
            "ready TCP waited for unrelated setup"
        );
        assert_eq!(channel.transport.path, Some(TransportPath::DirectTcp));
        entered.recv_timeout(Duration::from_secs(1)).unwrap();
        exchange(channel);
        loser.join().unwrap();
        server.join().unwrap();
    }

    #[test]
    fn relay_authenticates_while_direct_handshake_and_candidate_discovery_are_stalled() {
        let (coord, mut remote) = coordinator();
        let (stalled, entered, loser) = stalled_listener();
        candidates(&mut remote, &[stalled]);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        relay_offer(&mut remote, listener.local_addr().unwrap().port());
        let server = responder(listener, TransportPath::Relay);
        let mut report = TransportReport::default();
        let started = Instant::now();
        let channel = PeerChannel::negotiate(
            &identity(),
            peer(11).vip,
            peer(42),
            &ReportDirectory::disabled(),
            Duration::from_secs(10),
            None,
            coord,
            &mut report,
        )
        .unwrap();
        assert!(
            started.elapsed() >= DIRECT_HEAD_START && started.elapsed() < Duration::from_secs(6),
            "relay must respect the direct head start but remain a bounded fallback"
        );
        assert_eq!(channel.transport.path, Some(TransportPath::Relay));
        entered.recv_timeout(Duration::from_secs(1)).unwrap();
        exchange(channel);
        loser.join().unwrap();
        server.join().unwrap();
    }

    #[test]
    fn relay_waits_for_late_direct_candidates_but_has_a_finite_deadline() {
        let now = Instant::now();
        let offer = RelayOffer {
            host: String::new(),
            port: 1,
            ticket: vec![],
            received_at: now,
        };
        assert!(!offer.ready(now + Duration::from_secs(3), None, false));
        assert!(offer.ready(now + Duration::from_secs(4), None, false));
        let late_udp = now + Duration::from_secs(3);
        assert!(!offer.ready(now + Duration::from_secs(6), Some(late_udp), false));
        assert!(offer.ready(now + Duration::from_secs(7), Some(late_udp), false));
        assert!(offer.ready(
            now + Duration::from_secs(8),
            Some(now + Duration::from_secs(7)),
            false
        ));
        assert!(
            offer.ready(now, None, true),
            "exhausted direct paths need no extra wait"
        );
    }

    #[test]
    fn slow_direct_authentication_wins_over_a_ready_relay() {
        let (coord, mut remote) = coordinator();
        let direct = TcpListener::bind("127.0.0.1:0").unwrap();
        candidates(&mut remote, &[direct.local_addr().unwrap()]);
        let direct_server = thread::spawn(move || {
            let socket = direct.accept().unwrap().0;
            thread::sleep(Duration::from_millis(1200));
            respond(socket, TransportPath::DirectTcp);
        });
        let relay = TcpListener::bind("127.0.0.1:0").unwrap();
        relay_offer(&mut remote, relay.local_addr().unwrap().port());
        relay.set_nonblocking(true).unwrap();
        let done = Arc::new(AtomicBool::new(false));
        let signal = done.clone();
        let relay_server = thread::spawn(move || {
            let until = Instant::now() + Duration::from_secs(10);
            while !signal.load(Ordering::Relaxed) && Instant::now() < until {
                match relay.accept() {
                    Ok((socket, _)) => {
                        respond(socket, TransportPath::Relay);
                        return true;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(e) => panic!("{e}"),
                }
            }
            false
        });
        let channel = PeerChannel::negotiate(
            &identity(),
            peer(11).vip,
            peer(42),
            &ReportDirectory::disabled(),
            Duration::from_secs(10),
            None,
            coord,
            &mut TransportReport::default(),
        )
        .unwrap();
        let path = channel.transport.path;
        exchange(channel);
        done.store(true, Ordering::Relaxed);
        let relay_contacted = relay_server.join().unwrap();
        direct_server.join().unwrap();
        assert_eq!(path, Some(TransportPath::DirectTcp));
        assert!(
            !relay_contacted,
            "direct setup should have an exclusive head start"
        );
    }

    #[test]
    fn cancelling_negotiation_closes_in_progress_transports() {
        let (coord, mut remote) = coordinator();
        let (stalled, entered, loser) = stalled_listener();
        candidates(&mut remote, &[stalled]);
        let stop = Arc::new(AtomicBool::new(false));
        let signal = stop.clone();
        let worker = thread::spawn(move || {
            PeerChannel::negotiate(
                &identity(),
                peer(11).vip,
                peer(42),
                &ReportDirectory::disabled(),
                Duration::from_secs(5),
                Some(signal),
                coord,
                &mut TransportReport::default(),
            )
        });
        entered.recv_timeout(Duration::from_secs(2)).unwrap();
        let started = Instant::now();
        stop.store(true, Ordering::Relaxed);
        assert!(worker.join().unwrap().is_err());
        assert!(started.elapsed() < Duration::from_secs(1));
        loser.join().unwrap();
    }
}

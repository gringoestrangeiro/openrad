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
        let mut pending = None;
        let mut relay = None;
        let mut server_id = None;
        let mut udp_socket = None;
        let mut mapped_socket = None;
        let mut mapped_nonce = None;
        let mut mapped_received = true;
        let mut ues = vec![];
        // The incoming role owns the rendezvous nonce.
        let mut udp_nonce = None;
        let mut tcp_received = false;
        let mut udp_received = false;
        let until = Instant::now() + Duration::from_secs(30).min(duration);
        let mut discovery_until = until;
        for _ in 0..32 {
            while !coord.stream.ready(50)? {
                if Instant::now() >= discovery_until {
                    break;
                }
            }
            if Instant::now() >= discovery_until {
                break;
            }
            let data = coord.receive()?;
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
                    pending = Some((cid, field(&f, 0x0a0001cd)?.to_vec()));
                    // An empty native op-2 requests authenticated candidates. A
                    // listener must race the native universal connector for its
                    // whole lifetime before it is safe to advertise one.
                    coord.send(&request_tcp_candidates(cid))?;
                    let mapped = (|| -> Result<_> {
                        let (socket, _) =
                            crate::udp::bind_candidates(coord.stream.socket.local_addr()?.ip())?;
                        let endpoint = crate::udp::discover_mapping(&socket, &ues, &stop)?;
                        Ok((socket, endpoint))
                    })();
                    match mapped {
                        Ok((socket, endpoint)) => {
                            coord.send(&advertise_mapped_udp(cid, endpoint)?)?;
                            mapped_socket = Some(socket); mapped_received = false;
                            report.attempts.push(serde_json::json!({"path":"DirectUdp","phase":"ues_mapping","endpoint":endpoint,"result":"two_servers_agree"}));
                        }
                        Err(e) => report.attempts.push(serde_json::json!({"path":"DirectUdp","phase":"ues_mapping","error":e.to_string()})),
                    }
                    match crate::udp::bind_candidates(coord.stream.socket.local_addr()?.ip()) {
                        Ok((socket, endpoints)) => {
                            coord.send(&advertise_udp(cid, &endpoints, 0)?)?;
                            udp_socket = Some(socket);
                        }
                        Err(e) => {
                            udp_received = true;
                            report.attempts.push(serde_json::json!({"path":"DirectUdp","phase":"local_bind","error":e.to_string()}));
                        }
                    }
                    coord.send(&request_relay(cid))?;
                }
                operation @ (6 | 29) => {
                    if relay.is_some() {
                        discovery_until =
                            discovery_until.min(Instant::now() + Duration::from_secs(3));
                    }
                    let (cid, _) = pending
                        .as_ref()
                        .ok_or_else(|| anyhow::anyhow!("candidates before NewConnection"))?;
                    let path = if operation == 6 {
                        TransportPath::DirectTcp
                    } else {
                        TransportPath::DirectUdp
                    };
                    match direct_candidates(&data, *cid, operation, if operation == 6 { 0x1236 } else { 0x127c }) {
                        Ok((candidates, exclusions)) => {
                            if operation == 6 { report.tcp_candidates = candidates; } else { report.udp_candidates = candidates; }
                            for error in exclusions {
                                report.attempts.push(serde_json::json!({"path":path,"phase":"candidate_exclusion","error":error}));
                            }
                        }
                        Err(e) => report.attempts.push(serde_json::json!({"path":path,"phase":"candidate_validation","error":e.to_string()})),
                    }
                    if operation == 6 {
                        tcp_received = true;
                    } else {
                        udp_received = true;
                        udp_nonce = optional(&fields, 0x127c)?
                            .map(records)
                            .transpose()?
                            .map(|f| optional(&f, 0x0100020a)?.map(int32).transpose())
                            .transpose()?
                            .flatten()
                            .filter(|n| (1..=65535).contains(n))
                            .map(|n| n as u16);
                    }
                    if tcp_received && udp_received && mapped_received && relay.is_some() {
                        break;
                    }
                }
                7 => {
                    if relay.is_some() {
                        discovery_until =
                            discovery_until.min(Instant::now() + Duration::from_secs(3));
                    }
                    let cid = pending
                        .as_ref()
                        .ok_or_else(|| anyhow::anyhow!("mapped candidates before NewConnection"))?
                        .0;
                    match mapped_udp_candidate(&data, cid) {
                        Ok((candidate, nonce)) => { report.mapped_udp_candidates.push(candidate); mapped_nonce = Some(nonce); }
                        Err(e) => report.attempts.push(serde_json::json!({"path":"DirectUdp","phase":"mapped_candidate_validation","error":e.to_string()})),
                    }
                    mapped_received = true;
                    if tcp_received && udp_received && relay.is_some() {
                        break;
                    }
                }
                23 => {
                    let (cid, password) = pending
                        .as_ref()
                        .ok_or_else(|| anyhow::anyhow!("relay before NewConnection"))?;
                    ensure!(
                        int64(field(&fields, 0x020001c1)?)? == *cid,
                        "relay correlation mismatch"
                    );
                    let f = records(field(&fields, 0x123f)?)?;
                    let host = text(field(&f, 0x030001cb)?)?;
                    let port = int32(field(&f, 0x010001cc)?)?;
                    ensure!((1..=65535).contains(&port), "invalid relay port");
                    relay = Some((
                        host,
                        port as u16,
                        field(&f, 0x090001ca)?.to_vec(),
                        password.clone(),
                    ));
                    // A new member can receive NewConnection before its batched
                    // membership notification. Its incoming worker must wait for
                    // that identity/VIP binding before advertising listeners.
                    let grace = if tcp_received || udp_received { 3 } else { 12 };
                    discovery_until = until.min(Instant::now() + Duration::from_secs(grace));
                    if tcp_received && udp_received && mapped_received {
                        break;
                    }
                }
                16 | 44 => bail!("coordinator refused connection: operation {}", op(&data)?),
                _ => {}
            }
        }
        let (cid, password) = pending.ok_or_else(|| anyhow::anyhow!("missing NewConnection"))?;
        // Native connectors race; serial transport attempts can outlive a
        // peer's rendezvous window. Bound this to three transport workers per
        // peer (the caller separately bounds concurrent peer setups). Only a complete peer
        // proof AND Ethernet service handshake may win.
        let mut tcp_candidates = report.tcp_candidates.clone();
        tcp_candidates.sort_by_key(|c| match c.endpoint.ip() {
            std::net::IpAddr::V4(v) => {
                (v.is_private() || v.is_loopback() || v.is_link_local()) as u8
            }
            std::net::IpAddr::V6(v) => {
                (v.is_unique_local() || v.is_loopback() || v.segments()[0..2] == [0x2001, 0]) as u8
            }
        });
        let mut winner = None;
        std::thread::scope(|scope| -> Result<()> {
            let (tx, rx) = std::sync::mpsc::channel();
            let mut cancellations = vec![];
            if !tcp_candidates.is_empty() {
                let cancel = Arc::new(AtomicBool::new(false));
                cancellations.push(cancel.clone());
                let tx = tx.clone();
                let peer = peer.clone();
                let password = &password;
                scope.spawn(move || {
                    let mut attempts = vec![];
                    let result = (|| -> Result<Option<Self>> {
                        let until = Instant::now() + Duration::from_secs(8);
                        for (index, candidate) in tcp_candidates.iter().enumerate() {
                            if Instant::now() >= until || cancel.load(std::sync::atomic::Ordering::Relaxed) { break; }
                            let attempt = reports.child(&format!("direct-tcp-{index}"))?;
                            let result = (|| -> Result<Self> {
                                let mut stream = Framed::connect_timeout(
                                    &candidate.endpoint.ip().to_string(), candidate.endpoint.port(),
                                    until.saturating_duration_since(Instant::now()).min(Duration::from_secs(3)),
                                    Some(cancel.clone()), Duration::from_secs(2))?;
                                stream.peer_rendezvous(identity.rid, cid,
                                    server_id.ok_or_else(|| anyhow::anyhow!("missing authenticated coordinator ID"))?)?;
                                Self::authenticate_peer(PeerStream::Tcp(stream), TransportPath::DirectTcp,
                                    password, identity, own_ip, peer.clone(), &attempt)
                            })();
                            match result {
                                Ok(channel) => {
                                    attempts.push(serde_json::json!({"path":"DirectTcp","endpoint":candidate.endpoint,"result":"authenticated_service"}));
                                    return Ok(Some(channel));
                                }
                                Err(e) => attempts.push(serde_json::json!({"path":"DirectTcp","endpoint":candidate.endpoint,"error":e.to_string()})),
                            }
                        }
                        Ok(None)
                    })();
                    let _ = tx.send((0, result, attempts));
                });
            }
            for (kind, socket, candidates, nonce) in [
                (
                    "direct-udp",
                    udp_socket,
                    report.udp_candidates.clone(),
                    udp_nonce,
                ),
                (
                    "mapped-udp",
                    mapped_socket,
                    report.mapped_udp_candidates.clone(),
                    mapped_nonce,
                ),
            ] {
                let Some(socket) = socket.filter(|_| !candidates.is_empty()) else {
                    continue;
                };
                let index = cancellations.len();
                let cancel = Arc::new(AtomicBool::new(false));
                cancellations.push(cancel.clone());
                let tx = tx.clone();
                let peer = peer.clone();
                let password = &password;
                scope.spawn(move || {
                    let mut attempts = vec![];
                    let result = (|| -> Result<Option<Self>> {
                        let attempt = reports.child(kind)?;
                        let endpoints: Vec<_> = candidates.iter().map(|c| c.endpoint).collect();
                        let stream = crate::udp::Enet::connect(socket, &endpoints,
                            nonce.ok_or_else(|| anyhow::anyhow!("missing authenticated UDP rendezvous nonce"))?,
                            Duration::from_secs(8), Some(cancel))?;
                        let channel = Self::authenticate_peer(PeerStream::Udp(stream), TransportPath::DirectUdp,
                            password, identity, own_ip, peer, &attempt)?;
                        attempts.push(serde_json::json!({"path":"DirectUdp","phase":kind,"endpoint":channel.transport.endpoint,"result":"authenticated_service"}));
                        Ok(Some(channel))
                    })();
                    if let Err(ref e) = result {
                        attempts.push(serde_json::json!({"path":"DirectUdp","phase":kind,"error":e.to_string()}));
                    }
                    let _ = tx.send((index, result, attempts));
                });
            }
            drop(tx);
            let mut remaining = cancellations.len();
            while remaining > 0 {
                if stop
                    .as_ref()
                    .is_some_and(|s| s.load(std::sync::atomic::Ordering::Relaxed))
                {
                    for cancel in &cancellations {
                        cancel.store(true, std::sync::atomic::Ordering::Relaxed);
                    }
                }
                match rx.recv_timeout(Duration::from_millis(50)) {
                    Ok((index, result, attempts)) => {
                        remaining -= 1;
                        report.attempts.extend(attempts);
                        match result {
                            Ok(Some(channel)) if winner.is_none() => {
                                winner = Some(channel);
                                for (i, cancel) in cancellations.iter().enumerate() {
                                    if i != index { cancel.store(true, std::sync::atomic::Ordering::Relaxed); }
                                }
                            }
                            Err(e) if !report.attempts.iter().any(|a| a.get("error").and_then(|v| v.as_str()) == Some(&e.to_string())) =>
                                report.attempts.push(serde_json::json!({"phase":"direct_worker","error":e.to_string()})),
                            _ => {}
                        }
                    }
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
            Ok(())
        })?;
        if let Some(mut channel) = winner {
            channel.stream.set_stop(stop.clone());
            channel.transport.tcp_candidates = report.tcp_candidates.clone();
            channel.transport.udp_candidates = report.udp_candidates.clone();
            channel.transport.mapped_udp_candidates = report.mapped_udp_candidates.clone();
            channel.transport.attempts = report.attempts.clone();
            *report = channel.transport.clone();
            channel._coordinator = Some(coord);
            return Ok(channel);
        }
        let (host, port, ticket, _) =
            relay.ok_or_else(|| anyhow::anyhow!("relay setup record budget"))?;
        ensure!(ticket.len() == 256, "unsupported relay ticket width");
        let mut stream = Framed::connect_with_stop(&host, port, duration, stop)?;
        stream.send(
            &[
                u32v(0x010001df, 1),
                u32v(0x0100032b, 2),
                tlv(0x090001ca, &ticket),
            ]
            .concat(),
        )?;
        ensure!(
            int32(field(&records(&stream.receive(65536)?)?, 0x010001df)?)? == 2,
            "relay ticket rejected"
        );
        let mut channel = Self::authenticate_peer(
            PeerStream::Tcp(stream),
            TransportPath::Relay,
            &password,
            identity,
            own_ip,
            peer,
            reports,
        )?;
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

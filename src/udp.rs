//! Datagram channel: seven-byte P/A/C rendezvous, then reliable ENET.
//! Socket reachability is never authentication; the caller still runs peer SH.
use anyhow::{bail, ensure, Result};
use std::{
    collections::{BTreeMap, VecDeque},
    net::{IpAddr, SocketAddr, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

fn u16be(b: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([b[at], b[at + 1]])
}

fn u32be(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(b[at..at + 4].try_into().unwrap())
}
pub fn mapped(a: SocketAddr) -> SocketAddr {
    match a {
        SocketAddr::V4(v) => SocketAddr::new(IpAddr::V6(v.ip().to_ipv6_mapped()), v.port()),
        _ => a,
    }
}
fn canonical(a: SocketAddr) -> SocketAddr {
    match a {
        SocketAddr::V6(v) => v
            .ip()
            .to_ipv4_mapped()
            .map(|ip| SocketAddr::new(ip.into(), v.port()))
            .unwrap_or(a),
        _ => a,
    }
}
pub fn punch(nonce: u16, remote_port: u16, local_port: u16, kind: u8) -> [u8; 7] {
    let mut b = [0; 7];
    b[..2].copy_from_slice(&nonce.to_be_bytes());
    b[2..4].copy_from_slice(&remote_port.to_be_bytes());
    b[4..6].copy_from_slice(&local_port.to_be_bytes());
    b[6] = kind;
    b
}
pub fn valid_punch(b: &[u8], nonce: u16) -> bool {
    b.len() == 7
        && u16be(b, 0) == nonce
        && u16be(b, 2) != 0
        && u16be(b, 4) != 0
        && matches!(b[6], b'P' | b'A' | b'C')
}

/// Parse a correlated UDP endpoint discovery response.
pub fn mapped_response(data: &[u8], transaction: &[u8; 16]) -> Result<SocketAddr> {
    ensure!(
        data.len() >= 20 && u16be(data, 0) == 0x0101 && data[4..20] == *transaction,
        "UES response correlation/type"
    );
    ensure!(
        u16be(data, 2) as usize == data.len() - 20,
        "UES response length"
    );
    let mut at = 20;
    let mut mapped = None;
    while at < data.len() {
        ensure!(data.len() - at >= 4, "truncated UES attribute");
        let kind = u16be(data, at);
        let len = u16be(data, at + 2) as usize;
        at += 4;
        ensure!(len <= data.len() - at, "truncated UES value");
        if kind == 1 {
            ensure!(
                mapped.is_none() && len == 8 && data[at] == 0 && data[at + 1] == 1,
                "invalid UES mapped address"
            );
            let ip =
                std::net::Ipv4Addr::new(data[at + 4], data[at + 5], data[at + 6], data[at + 7]);
            let port = u16be(data, at + 2);
            ensure!(
                port != 0 && !ip.is_unspecified() && !ip.is_multicast() && !ip.is_broadcast(),
                "invalid UES mapping"
            );
            mapped = Some(SocketAddr::new(ip.into(), port));
        }
        at += len;
    }
    mapped.ok_or_else(|| anyhow::anyhow!("missing UES mapping"))
}
pub fn discover_mapping(
    socket: &UdpSocket,
    hosts: &[std::net::Ipv4Addr],
    stop: &Option<Arc<AtomicBool>>,
) -> Result<SocketAddr> {
    ensure!(hosts.len() >= 2, "insufficient authenticated UES hosts");
    socket.set_nonblocking(true)?;
    let mut previous = None;
    // Pick one native service port at each of two authenticated hosts. Never
    // walk a host's ports or contact addresses outside LoginComplete.
    let choice = crate::crypto::random(3);
    let start = choice[0] as usize % hosts.len();
    for index in 0..2 {
        let host = hosts[(start + index) % hosts.len()];
        let server = SocketAddr::new(host.into(), 17301 + (choice[index + 1] as u16 % 99));
        let transaction: [u8; 16] = crate::crypto::random(16).try_into().unwrap();
        let request = [vec![0, 1, 0, 0], transaction.to_vec()].concat();
        let until = Instant::now() + Duration::from_secs(2);
        let mut next = Instant::now();
        let mut sends = 0;
        let mut data = [0; 2048];
        let mut responses = 0;
        let address = loop {
            ensure!(
                !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
                "cancelled"
            );
            ensure!(
                Instant::now() < until,
                "UES mapping timeout at {server} (responses={responses})"
            );
            if sends < 2 && Instant::now() >= next {
                socket.send_to(&request, mapped(server))?;
                sends += 1;
                next = Instant::now() + Duration::from_millis(750);
            }
            match socket.recv_from(&mut data) {
                Ok((n, source)) if canonical(source) == server => {
                    responses += 1;
                    if let Ok(a) = mapped_response(&data[..n], &transaction) {
                        break a;
                    }
                }
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5))
                }
                Err(e) => return Err(e.into()),
            }
        };
        ensure!(
            previous.is_none_or(|p| p == address),
            "UES mapping changes by destination; no port prediction attempted"
        );
        previous = Some(address);
    }
    Ok(previous.unwrap())
}

/// Advertise only addresses on the interface used by the authenticated coordinator.
/// getifaddrs is local inventory, not network discovery or probing.
pub fn bind_candidates(route_ip: IpAddr) -> Result<(UdpSocket, Vec<SocketAddr>)> {
    let socket = UdpSocket::bind("[::]:0")?;
    let port = socket.local_addr()?.port();
    Ok((socket, local_candidates(route_ip, port)?))
}
pub fn local_candidates(route_ip: IpAddr, port: u16) -> Result<Vec<SocketAddr>> {
    let mut head = std::ptr::null_mut();
    // SAFETY: libc initializes a linked list; it is read only until freeifaddrs below.
    ensure!(
        unsafe { libc::getifaddrs(&mut head) } == 0,
        "local interface inventory failed"
    );
    let mut entries = vec![];
    let mut at = head;
    while !at.is_null() {
        // SAFETY: at and its address/name pointers belong to the live getifaddrs list.
        unsafe {
            let entry = &*at;
            if !entry.ifa_addr.is_null() && !entry.ifa_name.is_null() {
                let ip = match (*entry.ifa_addr).sa_family as i32 {
                    libc::AF_INET => Some(IpAddr::V4(std::net::Ipv4Addr::from(
                        (*(entry.ifa_addr as *const libc::sockaddr_in))
                            .sin_addr
                            .s_addr
                            .to_ne_bytes(),
                    ))),
                    libc::AF_INET6 => Some(IpAddr::V6(std::net::Ipv6Addr::from(
                        (*(entry.ifa_addr as *const libc::sockaddr_in6))
                            .sin6_addr
                            .s6_addr,
                    ))),
                    _ => None,
                };
                if let Some(ip) = ip {
                    entries.push((
                        std::ffi::CStr::from_ptr(entry.ifa_name).to_bytes().to_vec(),
                        ip,
                    ));
                }
            }
            at = entry.ifa_next;
        }
    }
    // SAFETY: exactly once, after all borrowed pointers have been copied.
    unsafe { libc::freeifaddrs(head) };
    let interface = entries
        .iter()
        .find(|(_, ip)| *ip == route_ip)
        .map(|(name, _)| name.clone());
    let mut candidates: Vec<_> = entries
        .into_iter()
        .filter(|(name, ip)| {
            interface.as_ref() == Some(name)
                && !ip.is_unspecified()
                && !ip.is_multicast()
                && !ip.is_loopback()
                && !matches!(ip, IpAddr::V6(v) if v.is_unicast_link_local())
        })
        .map(|(_, ip)| SocketAddr::new(ip, port))
        .collect();
    candidates.sort();
    candidates.dedup();
    candidates.truncate(32);
    ensure!(!candidates.is_empty(), "no routable local UDP candidates");
    Ok(candidates)
}

/// Sequence span a sender may keep unacknowledged. Counting the span from the
/// oldest unacknowledged command (not the number of pending commands) keeps an
/// openrad sender within the receiver's window even while one command is lost.
const SEND_WINDOW: usize = 256;
/// Receiver window: SEND_WINDOW plus one maximal fragmented message, since
/// `incoming` only advances past a message once all of its fragments arrived.
const RECEIVE_WINDOW: u16 = 512;
/// Stop draining the socket while this many messages await the caller; the
/// kernel buffer holds the rest instead of the channel failing.
const READY_HIGH_WATER: usize = 128;

struct Pending {
    command: Vec<u8>,
    last: Instant,
    tries: u8,
}
struct Fragment {
    total: usize,
    count: u16,
    parts: BTreeMap<u32, (usize, Vec<u8>)>,
}
pub struct Enet {
    pub socket: UdpSocket,
    endpoint: SocketAddr,
    cookie: [u8; 4],
    remote_id: u16,
    connected: bool,
    incoming_nonce: Option<u16>,
    mtu: usize,
    outgoing: u16,
    incoming: u16,
    pending: BTreeMap<(u8, u16), Pending>,
    ordered: BTreeMap<u16, (u16, Vec<u8>)>,
    fragments: BTreeMap<u16, Fragment>,
    ready: VecDeque<Vec<u8>>,
    started: Instant,
    deadline: Option<Instant>,
    last_receive: Instant,
    stop: Option<Arc<AtomicBool>>,
}
impl Enet {
    /// Incoming role: exact candidate + server-correlated nonce, then ENET's
    /// CONNECT/VERIFY/ACK. This is transport setup, not peer authentication.
    pub fn accept(
        socket: UdpSocket,
        endpoints: &[SocketAddr],
        nonce: u16,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        ensure!(
            !endpoints.is_empty() && endpoints.len() <= 32 && nonce != 0,
            "UDP candidate/nonce limit"
        );
        socket.set_nonblocking(true)?;
        let until = Instant::now() + duration;
        let local_port = socket.local_addr()?.port();
        let mut next = Instant::now();
        let mut buffer = [0; 4096];
        let endpoint = loop {
            ensure!(
                !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
                "cancelled"
            );
            ensure!(Instant::now() < until, "incoming UDP rendezvous timeout");
            if Instant::now() >= next {
                for &a in endpoints {
                    let _ = socket.send_to(&punch(nonce, a.port(), local_port, b'P'), mapped(a));
                }
                next = Instant::now() + Duration::from_millis(250);
            }
            match socket.recv_from(&mut buffer) {
                Ok((n, source))
                    if endpoints.contains(&canonical(source))
                        && valid_punch(&buffer[..n], nonce)
                        && matches!(buffer[6], b'P' | b'A') =>
                {
                    socket.send_to(&punch(nonce, source.port(), local_port, b'C'), source)?;
                    break canonical(source);
                }
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5))
                }
                Err(e) => return Err(e.into()),
            }
        };
        socket.connect(mapped(endpoint))?;
        let mut e = Self::new(socket, endpoint, Some(until), stop);
        e.incoming_nonce = Some(nonce);
        while !e.connected {
            e.pump()?;
            std::thread::sleep(Duration::from_millis(2));
        }
        Ok(e)
    }
    pub fn connect(
        socket: UdpSocket,
        endpoints: &[SocketAddr],
        nonce: u16,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        ensure!(
            endpoints.len() <= 32 && nonce != 0,
            "UDP candidate/nonce limit"
        );
        socket.set_nonblocking(true)?;
        let until = Instant::now() + duration;
        let local_port = socket.local_addr()?.port();
        let mut next = Instant::now();
        let mut buffer = [0; 4096];
        let mut received = 0;
        let mut matched = 0;
        let mut kinds = [0usize; 3];
        let endpoint = loop {
            ensure!(
                !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
                "cancelled"
            );
            ensure!(
                Instant::now() < until,
                "UDP rendezvous timeout (received={received}, matched={matched}, P/A/C={kinds:?})"
            );
            if Instant::now() >= next {
                for &a in endpoints {
                    let _ = socket.send_to(&punch(nonce, a.port(), local_port, b'P'), mapped(a));
                }
                next = Instant::now() + Duration::from_millis(250);
            }
            match socket.recv_from(&mut buffer) {
                Ok((n, source)) => {
                    received += 1;
                    let a = canonical(source);
                    // Exact authenticated candidates only: no port guessing or unsolicited peers.
                    if !endpoints.contains(&a) || !valid_punch(&buffer[..n], nonce) {
                        continue;
                    }
                    matched += 1;
                    kinds[match buffer[6] {
                        b'P' => 0,
                        b'A' => 1,
                        _ => 2,
                    }] += 1;
                    if buffer[6] == b'C' {
                        break a;
                    }
                    if buffer[6] == b'P' {
                        socket.send_to(&punch(nonce, a.port(), local_port, b'A'), source)?;
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5))
                }
                Err(e) => return Err(e.into()),
            }
        };
        socket.connect(mapped(endpoint))?;
        let mut e = Self::new(socket, endpoint, Some(until), stop);
        let mut command = vec![0x82, 255, 0, 1];
        command.extend(0u16.to_be_bytes());
        command.extend((e.mtu as u16).to_be_bytes());
        for value in [32768u32, 1, 0, 0, 5000, 2, 2] {
            command.extend(value.to_be_bytes());
        }
        command.extend(e.cookie);
        e.queue(command)?;
        while !e.connected {
            e.pump()?;
            std::thread::sleep(Duration::from_millis(2));
        }
        Ok(e)
    }
    fn new(
        socket: UdpSocket,
        endpoint: SocketAddr,
        deadline: Option<Instant>,
        stop: Option<Arc<AtomicBool>>,
    ) -> Self {
        Self {
            socket,
            endpoint,
            cookie: crate::crypto::random(4).try_into().unwrap(),
            remote_id: 0x7fff,
            connected: false,
            incoming_nonce: None,
            mtu: 1400,
            outgoing: 0,
            incoming: 0,
            pending: BTreeMap::new(),
            ordered: BTreeMap::new(),
            fragments: BTreeMap::new(),
            ready: VecDeque::new(),
            started: Instant::now(),
            deadline,
            last_receive: Instant::now(),
            stop,
        }
    }
    pub fn endpoint(&self) -> SocketAddr {
        self.endpoint
    }
    pub fn set_stop(&mut self, stop: Option<Arc<AtomicBool>>) {
        self.stop = stop;
    }
    pub fn sustain(&mut self) {
        self.deadline = None;
    }
    fn transmit(&self, command: &[u8]) -> Result<()> {
        // Reliable datagrams include a CRC checksum.
        // The packet header is a checksum, while CONNECT carries its own cookie.
        let mut packet = vec![0; 4];
        packet.extend((self.remote_id | 0x8000).to_be_bytes());
        packet.extend((self.started.elapsed().as_millis() as u16).to_be_bytes());
        packet.extend(command);
        let checksum = crate::session::rendezvous_checksum(&packet);
        packet[..4].copy_from_slice(&checksum.to_be_bytes());
        self.socket.send(&packet)?;
        Ok(())
    }
    fn queue(&mut self, command: Vec<u8>) -> Result<()> {
        ensure!(self.pending.len() < 256, "UDP send window exhausted");
        self.transmit(&command)?;
        self.pending.insert(
            (command[1], u16be(&command, 2)),
            Pending {
                command,
                last: Instant::now(),
                tries: 1,
            },
        );
        Ok(())
    }
    fn in_flight(&self) -> usize {
        self.pending
            .keys()
            .filter(|(channel, _)| *channel == 0)
            .map(|(_, seq)| self.outgoing.wrapping_sub(*seq) as usize + 1)
            .max()
            .unwrap_or(0)
    }
    /// Whether a message of `len` bytes fits the send window now. Callers drop
    /// the message when it does not, like congestion loss; tunnelled TCP recovers.
    pub fn has_room(&self, len: usize) -> bool {
        self.in_flight() + len.div_ceil(self.mtu - 32) <= SEND_WINDOW
    }
    pub fn send(&mut self, data: &[u8]) -> Result<()> {
        ensure!(
            self.connected && !data.is_empty() && data.len() <= 65536,
            "UDP message size/state"
        );
        let fragment_size = self.mtu - 32;
        let count = data.len().div_ceil(fragment_size);
        ensure!(self.has_room(data.len()), "UDP send window exhausted");
        let start = self.outgoing.wrapping_add(1);
        for (index, part) in data.chunks(fragment_size).enumerate() {
            self.outgoing = self.outgoing.wrapping_add(1);
            let mut c = vec![if count == 1 { 0x86 } else { 0x88 }, 0];
            c.extend(self.outgoing.to_be_bytes());
            if count > 1 {
                c.extend(start.to_be_bytes());
            }
            c.extend((part.len() as u16).to_be_bytes());
            if count > 1 {
                for v in [
                    count as u32,
                    index as u32,
                    data.len() as u32,
                    (index * fragment_size) as u32,
                ] {
                    c.extend(v.to_be_bytes());
                }
            }
            c.extend(part);
            self.queue(c)?;
        }
        Ok(())
    }
    pub fn receive(&mut self, max: usize) -> Result<Vec<u8>> {
        let until = Instant::now() + Duration::from_secs(10);
        loop {
            if let Some(data) = self.ready.pop_front() {
                ensure!(data.len() <= max, "UDP message limit");
                return Ok(data);
            }
            ensure!(Instant::now() < until, "UDP receive timeout");
            self.pump()?;
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    pub fn ready(&mut self, timeout_ms: i32) -> Result<bool> {
        let until = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
        loop {
            self.pump()?;
            if !self.ready.is_empty() {
                return Ok(true);
            }
            if Instant::now() >= until {
                return Ok(false);
            }
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    fn pump(&mut self) -> Result<()> {
        ensure!(
            !self
                .stop
                .as_ref()
                .is_some_and(|s| s.load(Ordering::Relaxed)),
            "cancelled"
        );
        ensure!(
            self.deadline.is_none_or(|d| Instant::now() < d),
            "UDP handshake deadline"
        );
        ensure!(
            self.last_receive.elapsed() < Duration::from_secs(35),
            "UDP liveness timeout"
        );
        let retry: Vec<_> = self
            .pending
            .iter()
            .filter(|(_, p)| p.last.elapsed() >= Duration::from_millis(400))
            .map(|(k, _)| *k)
            .collect();
        for key in retry {
            let p = self.pending.get_mut(&key).unwrap();
            ensure!(p.tries < 20, "UDP acknowledgement timeout");
            p.tries += 1;
            p.last = Instant::now();
            let c = p.command.clone();
            self.transmit(&c)?;
        }
        let mut b = [0; 4096];
        for _ in 0..64 {
            if self.ready.len() >= READY_HIGH_WATER {
                break;
            }
            match self.socket.recv(&mut b) {
                Ok(n) => self.ingest(&b[..n])?,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(e.into()),
            }
        }
        Ok(())
    }
    fn ingest(&mut self, b: &[u8]) -> Result<()> {
        if self
            .incoming_nonce
            .is_some_and(|nonce| valid_punch(b, nonce))
        {
            if matches!(b[6], b'P' | b'A') {
                self.socket.send(&punch(
                    self.incoming_nonce.unwrap(),
                    self.endpoint.port(),
                    self.socket.local_addr()?.port(),
                    b'C',
                ))?;
            }
            return Ok(());
        }
        if b.len() < 8 {
            return Ok(());
        }
        let mut checked = b.to_vec();
        checked[..4].fill(0);
        if u32be(b, 0) != crate::session::rendezvous_checksum(&checked) {
            return Ok(());
        }
        let id = u16be(b, 4);
        if id & 0x7fff != 0 && !(self.incoming_nonce.is_some() && id & 0x7fff == 0x7fff) {
            return Ok(());
        }
        let time = if id & 0x8000 != 0 { u16be(b, 6) } else { 0 };
        let mut at = if id & 0x8000 != 0 { 8 } else { 6 };
        const SIZES: [usize; 12] = [0, 8, 40, 36, 8, 4, 6, 8, 24, 8, 12, 16];
        for _ in 0..32 {
            if at == b.len() {
                break;
            }
            ensure!(b.len() - at >= 4, "truncated ENET command");
            let kind = (b[at] & 15) as usize;
            ensure!(kind > 0 && kind < SIZES.len(), "unknown ENET command");
            let size = SIZES[kind];
            ensure!(b.len() - at >= size, "truncated ENET command body");
            let c = &b[at..at + size];
            let channel = c[1];
            let seq = u16be(c, 2);
            at += size;
            let len = match kind {
                6 => u16be(c, 4) as usize,
                7..=9 => u16be(c, 6) as usize,
                _ => 0,
            };
            ensure!(len <= b.len() - at, "truncated ENET data");
            let payload = &b[at..at + len];
            at += len;
            if kind == 2 {
                ensure!(
                    self.incoming_nonce.is_some()
                        && id & 0x8000 != 0
                        && channel == 255
                        && seq == 1
                        && c[0] == 0x82
                        && u16be(c, 4) < 0x7fff
                        && u32be(c, 12) == 1
                        && u32be(c, 24) == 5000
                        && u32be(c, 28) == 2
                        && u32be(c, 32) == 2,
                    "ENET incoming CONNECT mismatch"
                );
                if self.remote_id == 0x7fff {
                    self.remote_id = u16be(c, 4);
                    self.mtu = (u16be(c, 6) as usize).clamp(576, self.mtu);
                    self.cookie.copy_from_slice(&c[36..40]);
                    let mut verify = vec![0x83, 255, 0, 1];
                    verify.extend(0u16.to_be_bytes());
                    verify.extend((self.mtu as u16).to_be_bytes());
                    for value in [32768u32, 1, 0, 0, 5000, 2, 2] {
                        verify.extend(value.to_be_bytes());
                    }
                    self.queue(verify)?;
                } else {
                    ensure!(
                        self.remote_id == u16be(c, 4) && self.cookie == c[36..40],
                        "ENET changed CONNECT"
                    );
                }
            }
            if c[0] & 128 != 0 {
                ensure!(id & 0x8000 != 0, "ENET reliable command without timestamp");
                if kind == 3 {
                    ensure!(
                        self.incoming_nonce.is_none()
                            && channel == 255
                            && seq == 1
                            && c[0] == 0x83
                            && u32be(c, 12) == 1
                            && u32be(c, 24) == 5000
                            && u32be(c, 28) == 2
                            && u32be(c, 32) == 2,
                        "ENET verification mismatch"
                    );
                    self.remote_id = u16be(c, 4);
                    ensure!(self.remote_id < 0x7fff, "ENET peer id");
                    self.mtu = (u16be(c, 6) as usize).clamp(576, self.mtu);
                    self.pending.remove(&(255, 1));
                    self.connected = true;
                }
            }
            // A reliable command outside the receive window is left unacknowledged
            // so the sender retransmits it, instead of failing the channel.
            let accepted = match kind {
                1 => {
                    let acknowledged = self.pending.remove(&(channel, u16be(c, 4)));
                    if self.incoming_nonce.is_some()
                        && channel == 255
                        && u16be(c, 4) == 1
                        && acknowledged.is_some()
                    {
                        self.connected = true;
                    }
                    true
                }
                2 | 3 | 5 | 10 | 11 => true,
                4 => bail!("UDP peer disconnected"),
                6 if channel == 0 => self.accept_ordered(seq, 1, payload.to_vec()),
                8 if channel == 0 => self.fragment(c, payload)?,
                _ => bail!("unsupported ENET channel/command"),
            };
            if c[0] & 128 != 0 && accepted {
                let mut ack = vec![1, channel, 0, 0];
                ack.extend(seq.to_be_bytes());
                ack.extend(time.to_be_bytes());
                self.transmit(&ack)?;
            }
            self.last_receive = Instant::now();
        }
        ensure!(at == b.len(), "ENET command budget");
        Ok(())
    }
    /// Returns whether the command may be acknowledged: true once it is buffered
    /// or already delivered, false when it falls outside the receive window.
    fn accept_ordered(&mut self, start: u16, count: u16, data: Vec<u8>) -> bool {
        let ahead = start.wrapping_sub(self.incoming);
        if ahead == 0 || ahead > 0x8000 {
            return true;
        }
        // The next expected message is always taken: refusing it once the
        // reorder buffer is full would stall the channel for good.
        let full = self.ordered.len() >= 256 || self.ready.len() >= 256;
        if ahead > RECEIVE_WINDOW || (full && ahead != 1) {
            return false;
        }
        self.ordered.entry(start).or_insert((count, data));
        while let Some((n, data)) = self.ordered.remove(&self.incoming.wrapping_add(1)) {
            self.incoming = self.incoming.wrapping_add(n);
            self.ready.push_back(data);
        }
        // Retransmitted fragments of already buffered or delivered messages can
        // leave incomplete groups behind; forget those that can no longer complete.
        let incoming = self.incoming;
        self.fragments
            .retain(|start, _| !matches!(start.wrapping_sub(incoming), 0 | 0x8001..));
        true
    }
    fn fragment(&mut self, c: &[u8], payload: &[u8]) -> Result<bool> {
        let start = u16be(c, 4);
        let count = u32be(c, 8);
        let index = u32be(c, 12);
        let total = u32be(c, 16) as usize;
        let offset = u32be(c, 20) as usize;
        let ahead = start.wrapping_sub(self.incoming);
        if ahead == 0 || ahead > 0x8000 || self.ordered.contains_key(&start) {
            return Ok(true);
        }
        ensure!(
            (1..=128).contains(&count)
                && index < count
                && (1..=65536).contains(&total)
                && offset <= total
                && payload.len() <= total - offset,
            "UDP fragment bounds"
        );
        ensure!(
            u16be(c, 2) == start.wrapping_add(index as u16),
            "UDP fragment sequence"
        );
        let full = !self.fragments.contains_key(&start) && self.fragments.len() >= 16;
        if ahead > RECEIVE_WINDOW || (full && ahead != 1) {
            return Ok(false);
        }
        let f = self.fragments.entry(start).or_insert_with(|| Fragment {
            total,
            count: count as u16,
            parts: BTreeMap::new(),
        });
        ensure!(
            f.total == total && f.count == count as u16,
            "inconsistent UDP fragments"
        );
        f.parts.entry(index).or_insert((offset, payload.to_vec()));
        if f.parts.len() == count as usize {
            let mut out = Vec::with_capacity(total);
            for (offset, data) in f.parts.values() {
                ensure!(*offset == out.len(), "overlapping/gapped UDP fragments");
                out.extend_from_slice(data);
            }
            ensure!(out.len() == total, "UDP fragment total mismatch");
            // Earlier parts have already been acknowledged. Keep them if the
            // reorder queue is full: the sender only retries the final part.
            let accepted = self.accept_ordered(start, count as u16, out);
            if accepted {
                self.fragments.remove(&start);
            }
            return Ok(accepted);
        }
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (Enet, UdpSocket) {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let remote = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.connect(remote.local_addr().unwrap()).unwrap();
        remote.connect(socket.local_addr().unwrap()).unwrap();
        socket.set_nonblocking(true).unwrap();
        let mut enet = Enet::new(socket, remote.local_addr().unwrap(), None, None);
        enet.connected = true;
        enet.remote_id = 0;
        (enet, remote)
    }
    fn packet(command: &[u8]) -> Vec<u8> {
        let mut b = vec![0, 0, 0, 0, 0x80, 0, 0, 42];
        b.extend(command);
        let crc = crate::session::rendezvous_checksum(&b);
        b[..4].copy_from_slice(&crc.to_be_bytes());
        b
    }
    fn reliable(sequence: u16, data: &[u8]) -> Vec<u8> {
        [
            vec![0x86, 0],
            sequence.to_be_bytes().to_vec(),
            (data.len() as u16).to_be_bytes().to_vec(),
            data.to_vec(),
        ]
        .concat()
    }
    fn fragment(sequence: u16, index: u32, offset: u32, data: &[u8]) -> Vec<u8> {
        let mut b = vec![0x88, 0];
        b.extend(sequence.to_be_bytes());
        b.extend(3u16.to_be_bytes());
        b.extend((data.len() as u16).to_be_bytes());
        for n in [2u32, index, 4, offset] {
            b.extend(n.to_be_bytes());
        }
        b.extend(data);
        b
    }
    #[test]
    fn reliable_reorders_deduplicates_and_reassembles_without_replaying_data() {
        let (mut e, _remote) = pair();
        e.ingest(&packet(&reliable(2, b"second"))).unwrap();
        assert!(e.ready.is_empty());
        e.ingest(&packet(&reliable(1, b"first"))).unwrap();
        e.ingest(&packet(&reliable(1, b"first"))).unwrap();
        assert_eq!(e.ready.pop_front().unwrap(), b"first");
        assert_eq!(e.ready.pop_front().unwrap(), b"second");
        assert!(e.ready.is_empty());
        e.ingest(&packet(&fragment(4, 1, 2, b"cd"))).unwrap();
        assert!(e.ready.is_empty());
        e.ingest(&packet(&fragment(3, 0, 0, b"ab"))).unwrap();
        assert_eq!(e.ready.pop_front().unwrap(), b"abcd");
        e.ingest(&packet(&fragment(4, 1, 2, b"cd"))).unwrap();
        assert!(e.ready.is_empty());
    }
    #[test]
    fn checksum_and_ack_sequence_are_required_before_releasing_pending_send() {
        let (mut e, _remote) = pair();
        e.send(b"retained until acknowledged").unwrap();
        let mut ack = packet(&[1, 0, 0, 0, 0, 1, 0, 42]);
        ack[0] ^= 1;
        e.ingest(&ack).unwrap();
        assert_eq!(e.pending.len(), 1);
        e.ingest(&packet(&[1, 0, 0, 0, 0, 2, 0, 42])).unwrap();
        assert_eq!(e.pending.len(), 1);
        e.ingest(&packet(&[1, 0, 0, 0, 0, 1, 0, 42])).unwrap();
        assert!(e.pending.is_empty());
    }
    #[test]
    fn malformed_fragments_and_sequence_jumps_are_bounded() {
        let (mut e, _remote) = pair();
        e.ingest(&packet(&reliable(600, b"outside window")))
            .unwrap();
        assert!(e.ordered.is_empty() && e.ready.is_empty());
        e.incoming = 2;
        e.ingest(&packet(&fragment(3, 0, 0, b"abc"))).unwrap();
        assert!(e.ingest(&packet(&fragment(4, 1, 2, b"cd"))).is_err());
        assert!(e.ingest(&packet(&[0x86, 0, 0, 5, 255, 255])).is_err());
        assert!(!valid_punch(&punch(1, 2, 3, b'P'), 2));
        assert!(!valid_punch(&[0; 7], 1));
    }

    fn fragment_of(start: u16, index: u32, data: &[u8]) -> Vec<u8> {
        let mut b = vec![0x88, 0];
        b.extend(start.wrapping_add(index as u16).to_be_bytes());
        b.extend(start.to_be_bytes());
        b.extend((data.len() as u16).to_be_bytes());
        for n in [2u32, index, 4, index * 2] {
            b.extend(n.to_be_bytes());
        }
        b.extend(data);
        b
    }
    #[test]
    fn full_send_window_refuses_room_instead_of_failing() {
        let (mut e, _remote) = pair();
        let message = vec![7; 1440];
        let mut sent = 0;
        while e.has_room(message.len()) {
            e.send(&message).unwrap();
            sent += 1;
        }
        assert_eq!(sent, 128);
        // Acknowledging everything but the oldest command does not reopen the
        // window: the receiver cannot advance past the lost command either.
        for seq in 2..=256u16 {
            let mut ack = vec![1, 0, 0, 0];
            ack.extend(seq.to_be_bytes());
            ack.extend([0, 42]);
            e.ingest(&packet(&ack)).unwrap();
        }
        assert!(!e.has_room(message.len()));
        e.ingest(&packet(&[1, 0, 0, 0, 0, 1, 0, 42])).unwrap();
        assert!(e.has_room(message.len()));
    }
    #[test]
    fn burst_after_a_lost_command_is_buffered_or_left_unacknowledged() {
        let (mut e, _remote) = pair();
        for seq in 2..=400u16 {
            e.ingest(&packet(&reliable(seq, &seq.to_be_bytes())))
                .unwrap();
        }
        assert!(e.ready.is_empty());
        e.ingest(&packet(&reliable(1, b"lost"))).unwrap();
        assert_eq!(e.ready.len(), 257);
        assert_eq!(e.ready.pop_front().unwrap(), b"lost");
    }
    #[test]
    fn retransmitted_fragments_of_buffered_messages_do_not_accumulate() {
        let (mut e, _remote) = pair();
        for round in 0..20u16 {
            let base = round * 3;
            e.ingest(&packet(&fragment_of(base + 2, 0, b"ab"))).unwrap();
            e.ingest(&packet(&fragment_of(base + 2, 1, b"cd"))).unwrap();
            e.ingest(&packet(&fragment_of(base + 2, 1, b"cd"))).unwrap();
            assert!(e.fragments.is_empty());
            e.ingest(&packet(&reliable(base + 1, b"gap"))).unwrap();
            assert_eq!(e.ready.pop_front().unwrap(), b"gap");
            assert_eq!(e.ready.pop_front().unwrap(), b"abcd");
        }
    }

    #[test]
    fn completed_fragments_survive_a_full_reorder_buffer() {
        let (mut e, _remote) = pair();
        // The sender may forget this part as soon as it is acknowledged.
        e.ingest(&packet(&fragment_of(300, 0, b"ab"))).unwrap();
        for seq in 2..=257 {
            e.ingest(&packet(&reliable(seq, b"queued"))).unwrap();
        }
        assert_eq!(e.ordered.len(), 256);
        e.ingest(&packet(&fragment_of(300, 1, b"cd"))).unwrap();

        // Close the gap and drain the queue before the sender retries only the
        // final, unacknowledged fragment. The earlier part must still exist.
        e.ingest(&packet(&reliable(1, b"gap"))).unwrap();
        e.ready.clear();
        for seq in 258..300 {
            e.ingest(&packet(&reliable(seq, b"queued"))).unwrap();
        }
        e.ready.clear();
        e.ingest(&packet(&fragment_of(300, 1, b"cd"))).unwrap();
        assert_eq!(e.ready.pop_front().as_deref(), Some(b"abcd".as_slice()));
        assert_eq!(e.incoming, 301);
        assert!(e.fragments.is_empty());
    }

    #[test]
    fn refused_commands_are_not_acknowledged_and_can_be_retried() {
        let (mut e, remote) = pair();
        remote.set_nonblocking(true).unwrap();
        let mut received = [0; 64];
        e.ingest(&packet(&reliable(RECEIVE_WINDOW + 1, b"later")))
            .unwrap();
        assert_eq!(
            remote.recv(&mut received).unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
        e.incoming = RECEIVE_WINDOW;
        e.ingest(&packet(&reliable(RECEIVE_WINDOW + 1, b"later")))
            .unwrap();
        assert_eq!(remote.recv(&mut received).unwrap(), 16);
        assert_eq!(received[8], 1);
        assert_eq!(u16be(&received, 12), RECEIVE_WINDOW + 1);
        assert_eq!(e.ready.pop_front().unwrap(), b"later");
    }

    #[test]
    fn send_window_tracks_a_lost_command_across_sequence_wraparound() {
        let (mut e, _remote) = pair();
        e.outgoing = u16::MAX - 1;
        e.send(b"lost").unwrap();
        e.send(b"received").unwrap();
        e.ingest(&packet(&[1, 0, 0, 0, 0, 0, 0, 42])).unwrap();
        assert_eq!(e.in_flight(), 2);
        assert!(e.has_room((SEND_WINDOW - 2) * (e.mtu - 32)));
        assert!(!e.has_room((SEND_WINDOW - 1) * (e.mtu - 32)));
        e.ingest(&packet(&[1, 0, 0, 0, 255, 255, 0, 42])).unwrap();
        assert_eq!(e.in_flight(), 0);
    }
}

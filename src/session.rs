//! Framed TCP and authenticated control-session lifecycle.
use crate::{
    crypto::{rsa_session, Channel, ShClient},
    output::ReportDirectory,
    protocol::*,
};
use anyhow::{bail, ensure, Result};
use std::{
    io::{self, IoSlice, Read, Write},
    net::{TcpStream, ToSocketAddrs},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

pub struct Framed {
    pub socket: TcpStream,
    deadline: Option<Instant>,
    received: usize,
    records: usize,
    stop: Option<Arc<AtomicBool>>,
}
impl Framed {
    /// Validate the universal connector before acknowledging it. The CID and
    /// remote RID come exclusively from the authenticated attachment session.
    pub fn accept_rendezvous(&mut self, rid: u64, cid: u64) -> Result<()> {
        self.socket
            .set_read_timeout(Some(Duration::from_millis(200)))?;
        let mut data = [0; 24];
        self.read_exact_until(
            &mut data,
            self.deadline
                .unwrap_or_else(|| Instant::now() + Duration::from_secs(3)),
        )?;
        ensure!(
            data[..4] == [0, 20, 0, 1]
                && rid != 0
                && cid != 0
                && data[4..12] == rid.to_be_bytes()
                && data[12..20] == cid.to_be_bytes()
                && data[20..24] != [0; 4],
            "incoming TCP rendezvous identity mismatch"
        );
        self.socket
            .write_all(&rendezvous_checksum(&data[4..]).to_be_bytes())?;
        Ok(())
    }
    pub fn peer_rendezvous(&mut self, rid: u64, cid: u64, server_id: u32) -> Result<()> {
        ensure!(
            rid != 0 && cid != 0 && server_id != 0,
            "invalid TCP rendezvous identity"
        );
        let body = [
            rid.to_be_bytes().to_vec(),
            cid.to_be_bytes().to_vec(),
            server_id.to_be_bytes().to_vec(),
        ]
        .concat();
        self.socket
            .write_all(&[vec![0, 20, 0, 1], body.clone()].concat())?;
        self.socket
            .set_read_timeout(Some(Duration::from_millis(200)))?;
        let mut response = [0; 4];
        let until = self
            .deadline
            .unwrap_or_else(|| Instant::now() + Duration::from_secs(5));
        self.read_exact_until(&mut response, until)?;
        ensure!(
            u32::from_be_bytes(response) == rendezvous_checksum(&body),
            "TCP rendezvous checksum mismatch"
        );
        Ok(())
    }
    pub fn connect(host: &str, port: u16, duration: Duration) -> Result<Self> {
        Self::connect_with_stop(host, port, duration, None)
    }
    pub fn connect_with_stop(
        host: &str,
        port: u16,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        Self::connect_timeout(host, port, duration, stop, Duration::from_secs(8))
    }
    pub fn connect_timeout(
        host: &str,
        port: u16,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
        timeout: Duration,
    ) -> Result<Self> {
        ensure!(
            !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
            "cancelled"
        );
        let addr = (host, port)
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| anyhow::anyhow!("no endpoint address"))?;
        let socket = connect_socket(&addr, timeout.min(duration), &stop)?;
        Self::from_socket(socket, duration, stop)
    }
    pub fn from_socket(
        socket: TcpStream,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        socket.set_nodelay(true)?;
        socket.set_write_timeout(Some(Duration::from_secs(5)))?;
        socket.set_read_timeout(Some(Duration::from_secs(10)))?;
        Ok(Self {
            socket,
            deadline: Some(Instant::now() + duration),
            received: 0,
            records: 0,
            stop,
        })
    }
    fn check_cancelled(&self) -> Result<()> {
        ensure!(
            !self
                .stop
                .as_ref()
                .is_some_and(|s| s.load(Ordering::Relaxed)),
            "cancelled"
        );
        Ok(())
    }
    pub fn send(&mut self, b: &[u8]) -> Result<()> {
        self.check_cancelled()?;
        ensure!(
            self.deadline.is_none_or(|d| Instant::now() < d)
                && !b.is_empty()
                && b.len() <= 4 * 1024 * 1024,
            "frame/deadline limit"
        );
        write_frame(&mut self.socket, b)?;
        Ok(())
    }
    pub fn receive(&mut self, max: usize) -> Result<Vec<u8>> {
        let remaining = self
            .deadline
            .map(|d| d.saturating_duration_since(Instant::now()))
            .unwrap_or(Duration::from_secs(10));
        ensure!(
            !remaining.is_zero() && (self.deadline.is_none() || self.records < 4096),
            "session deadline/record budget"
        );
        let until = Instant::now() + remaining.min(Duration::from_secs(10));
        self.socket
            .set_read_timeout(Some(Duration::from_millis(200)))?;
        let mut header = [0; 4];
        self.read_exact_until(&mut header, until)?;
        let len = u32::from_be_bytes(header) as usize;
        ensure!(
            len > 0
                && len <= max
                && (self.deadline.is_none() || len <= 32 * 1024 * 1024 - self.received),
            "frame/byte budget"
        );
        let mut b = vec![0; len];
        self.read_exact_until(&mut b, until)?;
        self.received = self.received.saturating_add(len);
        self.records = self.records.saturating_add(1);
        Ok(b)
    }
    fn read_exact_until(&mut self, mut buffer: &mut [u8], until: Instant) -> Result<()> {
        while !buffer.is_empty() {
            self.check_cancelled()?;
            ensure!(Instant::now() < until, "frame receive timeout");
            match self.socket.read(buffer) {
                Ok(0) => bail!("remote endpoint closed the connection"),
                Ok(n) => buffer = &mut buffer[n..],
                Err(e)
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock
                            | std::io::ErrorKind::TimedOut
                            | std::io::ErrorKind::Interrupted
                    ) => {}
                Err(e) => return Err(e.into()),
            }
        }
        Ok(())
    }
    /// Lift aggregate setup budgets only after successful authentication.
    /// Individual frames and socket timeouts remain bounded.
    pub fn set_stop(&mut self, stop: Option<Arc<AtomicBool>>) {
        self.stop = stop;
    }
    pub fn sustain(&mut self) {
        self.deadline = None;
    }
    pub fn ready(&self, timeout_ms: i32) -> Result<bool> {
        self.check_cancelled()?;
        #[cfg(target_os = "linux")]
        {
            use std::os::fd::AsRawFd;
            let until = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
            loop {
                // A kernel wait avoids repeatedly toggling O_NONBLOCK and
                // peeking every 5 ms on every idle peer. Slice long waits so
                // cancellation remains responsive without touching the stream.
                let remaining = until.saturating_duration_since(Instant::now());
                let wait_ms = remaining.as_nanos().div_ceil(1_000_000).min(50) as i32;
                let ready = readable(self.socket.as_raw_fd(), wait_ms)?;
                self.check_cancelled()?;
                if ready {
                    return Ok(true);
                }
                if Instant::now() >= until {
                    return Ok(false);
                }
            }
        }
        #[cfg(not(target_os = "linux"))]
        self.ready_peek(timeout_ms)
    }
    #[cfg(not(target_os = "linux"))]
    fn ready_peek(&self, timeout_ms: i32) -> Result<bool> {
        // peek preserves framing; this also reports EOF as readable.
        let until = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
        loop {
            self.check_cancelled()?;
            self.socket.set_nonblocking(true)?;
            let result = self.socket.peek(&mut [0u8]);
            self.socket.set_nonblocking(false)?;
            match result {
                Ok(_) => return Ok(true),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(e.into()),
            }
            if Instant::now() >= until {
                return Ok(false);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

// A blocking connect kept cancelled handshake slots (and transport race losers)
// alive for up to eight seconds. Poll a single nonblocking attempt instead of
// repeatedly reconnecting and restarting the TCP handshake.
#[cfg(target_os = "linux")]
fn connect_socket(
    address: &std::net::SocketAddr,
    timeout: Duration,
    stop: &Option<Arc<AtomicBool>>,
) -> Result<TcpStream> {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    ensure!(!timeout.is_zero(), "TCP connect timeout");
    let domain = if address.is_ipv4() {
        libc::AF_INET
    } else {
        libc::AF_INET6
    };
    // SAFETY: socket has no borrowed pointers; OwnedFd closes it on every exit.
    let raw = unsafe {
        libc::socket(
            domain,
            libc::SOCK_STREAM | libc::SOCK_NONBLOCK | libc::SOCK_CLOEXEC,
            0,
        )
    };
    if raw < 0 {
        return Err(io::Error::last_os_error().into());
    }
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    let connected = match address {
        std::net::SocketAddr::V4(address) => {
            let mut raw: libc::sockaddr_in = unsafe { std::mem::zeroed() };
            raw.sin_family = libc::AF_INET as _;
            raw.sin_port = address.port().to_be();
            raw.sin_addr.s_addr = u32::from_ne_bytes(address.ip().octets());
            // SAFETY: pointer and length describe a live IPv4 sockaddr.
            unsafe {
                libc::connect(
                    fd.as_raw_fd(),
                    (&raw as *const libc::sockaddr_in).cast(),
                    std::mem::size_of_val(&raw) as _,
                )
            }
        }
        std::net::SocketAddr::V6(address) => {
            let mut raw: libc::sockaddr_in6 = unsafe { std::mem::zeroed() };
            raw.sin6_family = libc::AF_INET6 as _;
            raw.sin6_port = address.port().to_be();
            raw.sin6_addr.s6_addr = address.ip().octets();
            raw.sin6_flowinfo = address.flowinfo().to_be();
            raw.sin6_scope_id = address.scope_id();
            // SAFETY: pointer and length describe a live IPv6 sockaddr.
            unsafe {
                libc::connect(
                    fd.as_raw_fd(),
                    (&raw as *const libc::sockaddr_in6).cast(),
                    std::mem::size_of_val(&raw) as _,
                )
            }
        }
    };
    if connected < 0 {
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::EINPROGRESS) {
            return Err(error.into());
        }
    }
    let stream = TcpStream::from(fd);
    let until = Instant::now() + timeout;
    loop {
        ensure!(
            !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
            "cancelled"
        );
        if connected == 0 {
            break;
        }
        let remaining = until.saturating_duration_since(Instant::now());
        ensure!(!remaining.is_zero(), "TCP connect timeout");
        let mut descriptor = libc::pollfd {
            fd: stream.as_raw_fd(),
            events: libc::POLLOUT,
            revents: 0,
        };
        // SAFETY: poll mutates one valid descriptor for at most 50 ms.
        let result = unsafe {
            libc::poll(
                &mut descriptor,
                1,
                remaining.as_millis().clamp(1, 50) as i32,
            )
        };
        if result < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error.into());
        }
        if result > 0 {
            if let Some(error) = stream.take_error()? {
                return Err(error.into());
            }
            ensure!(
                !stop.as_ref().is_some_and(|s| s.load(Ordering::Relaxed)),
                "cancelled"
            );
            break;
        }
    }
    stream.set_nonblocking(false)?;
    Ok(stream)
}

#[cfg(not(target_os = "linux"))]
fn connect_socket(
    address: &std::net::SocketAddr,
    timeout: Duration,
    _stop: &Option<Arc<AtomicBool>>,
) -> Result<TcpStream> {
    Ok(TcpStream::connect_timeout(address, timeout)?)
}

/// Submit header and payload together without a concatenation allocation. TCP
/// may write only part of either slice; retry without duplicating any bytes.
fn write_frame(writer: &mut impl Write, payload: &[u8]) -> io::Result<()> {
    let header = (payload.len() as u32).to_be_bytes();
    let mut buffers = [IoSlice::new(&header), IoSlice::new(payload)];
    let mut remaining = &mut buffers[..];
    while !remaining.is_empty() {
        match writer.write_vectored(remaining) {
            Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
            Ok(n) => IoSlice::advance_slices(&mut remaining, n),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// The checksum feeds each byte into the low byte after shifting.
pub fn rendezvous_checksum(data: &[u8]) -> u32 {
    checksum_bytes(data.iter().copied())
}

/// ENET checksums treat their first four (checksum) bytes as zero.
pub(crate) fn enet_checksum(data: &[u8]) -> u32 {
    checksum_bytes([0; 4].into_iter().chain(data[4..].iter().copied()))
}

fn checksum_bytes(bytes: impl IntoIterator<Item = u8>) -> u32 {
    let mut crc = u32::MAX;
    for byte in bytes {
        crc = CRC_TABLE[(crc >> 24) as usize] ^ ((crc << 8) | byte as u32);
    }
    !crc
}

const CRC_TABLE: [u32; 256] = {
    let mut table = [0; 256];
    let mut index = 0;
    while index < table.len() {
        let mut value = (index as u32) << 24;
        let mut bit = 0;
        while bit < 8 {
            value = (value << 1)
                ^ if value & 0x8000_0000 != 0 {
                    0x04c1_1db7
                } else {
                    0
                };
            bit += 1;
        }
        table[index] = value;
        index += 1;
    }
    table
};

#[cfg(test)]
mod checksum_tests {
    use super::*;

    fn bitwise_checksum(data: &[u8]) -> u32 {
        let mut crc = u32::MAX;
        for &byte in data {
            let mut table = crc & 0xff00_0000;
            for _ in 0..8 {
                table = (table << 1)
                    ^ if table & 0x8000_0000 != 0 {
                        0x04c1_1db7
                    } else {
                        0
                    };
            }
            crc = table ^ ((crc << 8) | byte as u32);
        }
        !crc
    }

    #[test]
    fn lookup_checksum_matches_bitwise_for_packet_sizes_and_zero_prefix() {
        for len in [0, 1, 4, 8, 15, 16, 64, 1400, 4096] {
            let data: Vec<u8> = (0..len).map(|i| (i * 37 + 11) as u8).collect();
            assert_eq!(rendezvous_checksum(&data), bitwise_checksum(&data));
            if len >= 4 {
                let mut zeroed = data.clone();
                zeroed[..4].fill(0);
                assert_eq!(enet_checksum(&data), bitwise_checksum(&zeroed));
            }
        }
    }
}
#[cfg(target_os = "linux")]
pub fn readable(fd: i32, timeout_ms: i32) -> Result<bool> {
    let mut p = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let result = unsafe { libc::poll(&mut p, 1, timeout_ms) };
    if result < 0 {
        let err = std::io::Error::last_os_error();
        if err.kind() == std::io::ErrorKind::Interrupted {
            return Ok(false);
        }
        return Err(err.into());
    }
    Ok(result > 0)
}
pub struct Session {
    pub stream: Framed,
    pub channel: Channel,
    pub latency: u32,
    pub ues: Vec<std::net::Ipv4Addr>,
}
impl Session {
    pub fn provision(
        modulus: &[u8],
        name: &str,
        host: &str,
        reports: &ReportDirectory,
    ) -> Result<Identity> {
        Self::provision_with_commit(modulus, name, host, reports, &mut || Ok(()))
    }
    /// `commit` runs right before each registering login is sent, so a caller can
    /// persist its intent only once a registration may actually happen.
    pub fn provision_with_commit(
        modulus: &[u8],
        name: &str,
        host: &str,
        reports: &ReportDirectory,
        commit: &mut dyn FnMut() -> Result<()>,
    ) -> Result<Identity> {
        let mut bootstrap = Identity::bootstrap(name, host)?;
        let mut visited = std::collections::BTreeSet::new();
        for attempt in 0..3 {
            ensure!(
                visited.insert(bootstrap.server_address.clone()),
                "provisioning redirect loop"
            );
            let c = reports.child(&format!("attempt-{attempt}"))?;
            let mut session = Self::authenticate(
                &bootstrap.server_address,
                &bootstrap,
                modulus,
                3,
                &c,
                Duration::from_secs(40),
            )?;
            commit()?;
            session.send(&login(name, 0, 3, None)?)?;
            let data = session.receive()?;
            if op(&data)? == 12 {
                let r = records(&data)?;
                let f = records(field(&r, 0x1238)?)?;
                let f = records(field(&f, 0x1234)?)?;
                let host = text(field(&f, 0x030001c9)?)?;
                host.parse::<std::net::Ipv4Addr>()?;
                bootstrap.server_address = host;
                continue;
            }
            ensure!(op(&data)? == 21, "expected provisioning LoginComplete");
            let identity = Identity::registered(&session.receive()?)?;
            identity.save(reports)?;
            return Ok(identity);
        }
        bail!("provisioning redirect budget exhausted")
    }
    pub fn authenticate(
        host: &str,
        identity: &Identity,
        modulus: &[u8],
        purpose: u32,
        reports: &ReportDirectory,
        duration: Duration,
    ) -> Result<Self> {
        Self::authenticate_with_stop(host, identity, modulus, purpose, reports, duration, None)
    }
    pub fn authenticate_with_stop(
        host: &str,
        identity: &Identity,
        modulus: &[u8],
        purpose: u32,
        _reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<Self> {
        let (rsa, secret) = rsa_session(modulus, purpose)?;
        let mut stream = Framed::connect_with_stop(host, 17301, duration, stop)?;
        stream.send(&[3, 0, 0, 0])?;
        stream.send(&rsa)?;
        ensure!(stream.receive(4)? == [3, 0, 0, 0], "RSession echo mismatch");
        let mut session = Self {
            stream,
            channel: Channel::new(&secret[..32])?,
            latency: u32::MAX,
            ues: vec![],
        };
        let mut sh = ShClient::new(identity.rid, &identity.password()?)?;
        let mut next = sh.start()?;
        for stage in 0..3 {
            let start = Instant::now();
            session.send(&next)?;
            let plain = session.receive()?;
            let ns = start.elapsed().as_nanos();
            let whole = ns / 1_000_000;
            let rem = ns % 1_000_000;
            let ms = whole + u128::from(rem > 500_000 || rem == 500_000 && whole % 2 == 1);
            session.latency = session.latency.min(ms.min(u32::MAX as u128) as u32);
            match stage {
                0 => next = sh.parameters(&plain)?,
                1 => next = sh.challenge(&plain)?,
                _ => {
                    let key = sh.confirm(&plain)?;
                    session.channel.rekey(&key[..32])?;
                }
            }
        }
        Ok(session)
    }
    pub fn send(&mut self, plain: &[u8]) -> Result<()> {
        let ct = self.channel.encrypt(plain)?;
        self.stream.send(&ct)?;
        Ok(())
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        let ct = self.stream.receive(4 * 1024 * 1024)?;
        let pt = self.channel.decrypt(&ct)?;
        Ok(pt)
    }
    pub fn attach(
        identity: &Identity,
        modulus: &[u8],
        reports: &ReportDirectory,
        duration: Duration,
    ) -> Result<(Self, Membership, std::net::Ipv4Addr)> {
        Self::attach_with_stop(identity, modulus, reports, duration, None)
    }
    pub fn attach_with_stop(
        identity: &Identity,
        modulus: &[u8],
        reports: &ReportDirectory,
        duration: Duration,
        stop: Option<Arc<AtomicBool>>,
    ) -> Result<(Self, Membership, std::net::Ipv4Addr)> {
        let mut s = Self::authenticate_with_stop(
            &identity.server_address,
            identity,
            modulus,
            4,
            reports,
            duration,
            stop,
        )?;
        s.send(&login(&identity.node_name, s.latency, 4, None)?)?;
        let mut membership = Membership {
            own_rid: identity.rid,
            ..Membership::default()
        };
        for i in 0..8 {
            let data = s.receive()?;
            let operation = op(&data)?;
            if i == 0 {
                ensure!(
                    operation == 21,
                    "expected LoginComplete; operation {operation}"
                );
                s.ues = ues_hosts(&data)?;
            } else {
                ensure!(
                    [9, 38].contains(&operation),
                    "unexpected registration operation {operation}"
                );
            }
            membership.snapshot(&data)?;
            if let Some(vip) = own_vip(&data)? {
                return Ok((s, membership, vip));
            }
        }
        bail!("registration budget exhausted")
    }
    pub fn list_public(&mut self, query: &str) -> Result<(Vec<PublicNetwork>, u64)> {
        self.send(&public_list(query, 1, 0)?)?;
        for _ in 0..128 {
            let data = self.receive()?;
            if op(&data)? == 45 {
                return listing(&data, 1);
            }
        }
        bail!("public listing budget exhausted")
    }
    pub fn network_operation(
        &mut self,
        request: crate::network::NetworkRequest,
        id: u64,
        sequence: u32,
        membership: &mut Membership,
    ) -> Result<crate::network::OperationResult> {
        let (mut operation, packet) =
            crate::network::NetworkOperation::start(request, id, sequence)?;
        self.send(&packet)?;
        let deadline = Instant::now() + Duration::from_secs(20);
        for _ in 0..256 {
            ensure!(
                Instant::now() < deadline,
                "network operation timed out; reconnect to check server membership"
            );
            if !self.stream.ready(100)? {
                continue;
            }
            let data = self.receive()?;
            match op(&data)? {
                38 => membership.snapshot(&data)?,
                41 | 42 => membership.changes(&data)?,
                16 => bail!("server disconnected the session"),
                _ => {}
            }
            let progress = operation.handle(&data, membership)?;
            if let Some(packet) = progress.send {
                self.send(&packet)?;
            }
            if let Some(result) = progress.complete {
                return Ok(result);
            }
        }
        bail!("network operation budget exhausted; reconnect to check server membership")
    }
    pub fn join_public(
        &mut self,
        name: &str,
        id: u64,
        seq: u32,
        membership: &mut Membership,
    ) -> Result<Network> {
        let result = self.network_operation(
            crate::network::NetworkRequest::public_join(name.into()),
            id,
            seq,
            membership,
        )?;
        ensure!(!result.error, "{}", result.message);
        membership
            .networks
            .values()
            .find(|n| n.name == name)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("joined network missing from membership"))
    }
}

#[cfg(test)]
mod frame_write_tests {
    use super::*;

    struct PartialWriter {
        bytes: Vec<u8>,
        limit: usize,
        calls: usize,
        interrupt: bool,
    }
    impl Write for PartialWriter {
        fn write(&mut self, _: &[u8]) -> io::Result<usize> {
            panic!("frame should use vectored writes")
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
        fn write_vectored(&mut self, buffers: &[IoSlice<'_>]) -> io::Result<usize> {
            self.calls += 1;
            if self.interrupt {
                self.interrupt = false;
                return Err(io::ErrorKind::Interrupted.into());
            }
            let before = self.bytes.len();
            self.bytes
                .extend(buffers.iter().flat_map(|b| b.iter()).take(self.limit));
            Ok(self.bytes.len() - before)
        }
    }

    #[test]
    fn partial_and_interrupted_writes_never_repeat_or_drop_bytes() {
        let expected = [0, 0, 0, 5, 1, 2, 3, 4, 5];
        for limit in 1..=expected.len() {
            let mut writer = PartialWriter {
                bytes: vec![],
                limit,
                calls: 0,
                interrupt: true,
            };
            write_frame(&mut writer, &[1, 2, 3, 4, 5]).unwrap();
            assert_eq!(writer.bytes, expected);
            assert_eq!(writer.calls, 1 + expected.len().div_ceil(limit));
        }
    }

    #[test]
    fn zero_write_fails_instead_of_spinning() {
        let mut writer = PartialWriter {
            bytes: vec![],
            limit: 0,
            calls: 0,
            interrupt: false,
        };
        assert_eq!(
            write_frame(&mut writer, &[1]).unwrap_err().kind(),
            io::ErrorKind::WriteZero
        );
        assert_eq!(writer.calls, 1);
    }
}

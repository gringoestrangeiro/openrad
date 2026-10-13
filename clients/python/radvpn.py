"""Linux VPN engine: authenticated control, peer relays/TCP and userspace L2.

The engine never invokes sudo. Its packet interface is a pair of callbacks,
so localhost UDP and an optional Linux TAP use exactly the same peer channels.
"""
from collections import deque
from dataclasses import replace
import fcntl
from ipaddress import IPv4Address, ip_address
import json
import math
import os
from pathlib import Path
import queue
import secrets
import select
import socket
import struct
import subprocess
import threading
import time
import uuid

import radproto as p


MAX_WIRE = 4 * 1024 * 1024
MTU = 1500
MAX_ETHERNET = MTU + 14


def poll_readable(readers, timeout=0):
    """Linux readiness without select()'s FD_SETSIZE (1024) ceiling."""
    poller = select.poll()
    by_fd = {}
    for reader in readers:
        fd = reader if isinstance(reader, int) else reader.fileno()
        by_fd[fd] = reader
        poller.register(fd, select.POLLIN)
    return [by_fd[fd] for fd, _ in poller.poll(None if timeout is None else math.ceil(timeout * 1000))]


class WakeQueue(queue.Queue):
    """Bounded queue that wakes select without periodic packet-path polling."""
    def __init__(self, capacity):
        super().__init__(capacity)
        self.reader, self.writer = socket.socketpair()
        self.reader.setblocking(False)
        self.writer.setblocking(False)

    def put(self, item, block=True, timeout=None):
        super().put(item, block, timeout)
        try:
            self.writer.send(b'\0')
        except OSError:
            pass  # A full wake socket already signals pending work.

    def drain_wake(self):
        try:
            while self.reader.recv(4096):
                pass
        except OSError:
            pass

    def close(self):
        self.reader.close()
        self.writer.close()


def private_json(path, value):
    """Atomic, private persistence. An existing identity survives failed writes."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + secrets.token_hex(6) + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(value, out, ensure_ascii=False, indent=2)
            out.write('\n')
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def save_identity(path, identity):
    private_json(path, dict(format='openrad-identity-v1', rid=identity.rid,
        vip=identity.ipv4, node_name=identity.node_name, address=identity.address.hex(),
        credential=identity.credential.hex(), server_address=identity.server_address,
        legacy_credential=identity.legacy_credential.hex()))


class Profile:
    """An exclusive per-user engine, with separate identity and API discovery."""
    def __init__(self, directory):
        self.path = Path(directory).expanduser().absolute()
        self.path.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.path.stat()
        if info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise ValueError('profile directory must belong to you and have mode 0700')
        self.lock_fd = None

    def acquire(self):
        self.lock_fd = os.open(self.path / 'engine.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(self.lock_fd)
            self.lock_fd = None
            raise ValueError('an engine already owns this profile') from None

    def close(self):
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None


class Framed:
    """Incremental TCP framing. Timeouts preserve partially received records."""
    def __init__(self, sock, stop=None):
        self.sock, self.stop = sock, stop or threading.Event()
        self.buffer = bytearray()
        self.sock.setblocking(False)
        self._read_poll, self._write_poll = select.poll(), select.poll()
        self._read_poll.register(sock, select.POLLIN)
        self._write_poll.register(sock, select.POLLOUT)
        if sock.family in (socket.AF_INET, socket.AF_INET6):
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

    @classmethod
    def connect(cls, host, port=17301, stop=None, timeout=6):
        # Service replies supply IP literals; avoiding DNS also bounds setup.
        host = str(ip_address(host))
        if not 0 < port <= 65535:
            raise ValueError('invalid TCP port')
        if stop is not None and stop.is_set():
            raise ConnectionError('cancelled')
        sock = socket.create_connection((host, port), timeout=timeout)
        return cls(sock, stop)

    def send(self, body, until=None):
        if not 0 < len(body) <= MAX_WIRE:
            raise ValueError('invalid outgoing record size')
        self.write(struct.pack('>I', len(body)) + body, until)

    def write(self, data, until=None):
        until = until or time.monotonic() + 5
        view = memoryview(data)
        while view:
            if self.stop.is_set():
                raise ConnectionError('cancelled')
            remaining = until - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('TCP write timed out')
            try:
                n = self.sock.send(view)
            except BlockingIOError:
                self._write_poll.poll(math.ceil(min(.2, remaining) * 1000))
                continue
            if not n:
                raise ConnectionError('TCP stream closed')
            if n == len(view):
                return
            view = view[n:]

    def read_exact(self, size, until):
        while len(self.buffer) < size:
            self._fill(size, until)
        result = bytes(memoryview(self.buffer)[:size])
        del self.buffer[:size]
        return result

    def receive(self, maximum=MAX_WIRE, until=None):
        until = until or time.monotonic() + 10
        # Keep the prefix in the buffer until the entire payload arrives.
        while len(self.buffer) < 4:
            self._fill(4, until)
        size = int.from_bytes(self.buffer[:4], 'big')
        if not 0 < size <= maximum:
            raise ValueError('incoming record exceeds limit')
        while len(self.buffer) < 4 + size:
            self._fill(4 + size, until)
        result = bytes(memoryview(self.buffer)[4:4 + size])
        del self.buffer[:4 + size]
        return result

    def _fill(self, size, until):
        if self.stop.is_set():
            raise ConnectionError('cancelled')
        remaining = until - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('TCP read timed out')
        try:
            # One bounded read often captures prefix, payload and subsequent
            # records. Preserve them in the incremental buffer across timeouts.
            block = self.sock.recv(65536)
        except BlockingIOError:
            self._read_poll.poll(math.ceil(min(.2, remaining) * 1000))
            return
        if not block:
            raise ConnectionError('TCP stream closed')
        self.buffer.extend(block)

    def ready(self, timeout=0):
        return len(self.buffer) >= 4 or bool(self._read_poll.poll(None if timeout is None else math.ceil(timeout * 1000)))

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def authenticate(stream, crypto, until=None):
    until = until or time.monotonic() + 35
    stream.send(b'\x03\0\0\0', until)
    stream.send(crypto.f6, until)
    reply = crypto.accept_echo(stream.receive(4, until))
    latency = []
    for handler in (crypto.receive_f10, crypto.receive_f12, crypto.receive_f14):
        start = time.monotonic_ns()
        stream.send(reply, until)
        reply = handler(stream.receive(65536, until))
        latency.append(p.round_milliseconds(time.monotonic_ns() - start))
    return min(latency)


class Control:
    def __init__(self, stream, crypto):
        self.stream, self.crypto = stream, crypto

    @classmethod
    def login(cls, host, identity, modulus=p.SERVER_MODULUS, purpose=4, stop=None, port=17301):
        stream = Framed.connect(host, port, stop)
        try:
            crypto = p.SessionCrypto(modulus, identity.rid, identity.credential, purpose=purpose)
            latency = authenticate(stream, crypto)
            control = cls(stream, crypto)
            body = p.build_login(identity.node_name, latency, purpose=purpose)
            control.send(body)
            return control
        except BaseException:
            stream.close()
            raise

    def send(self, data):
        self.stream.send(self.crypto.cc.enc(data))

    def receive(self, until=None):
        return self.crypto.cc.dec(self.stream.receive(MAX_WIRE, until))

    def close(self):
        self.stream.close()


def ensure_identity(profile, name, *, import_path=None, host=p.DEFAULT_HOST, port=p.DEFAULT_PORT, modulus=p.SERVER_MODULUS, stop=None):
    path = profile.path / 'identity.json'
    if path.exists():
        if import_path:
            raise ValueError('this profile already has an identity')
        return p.Identity.load(path)
    if import_path:
        identity = p.Identity.load(Path(import_path))
        save_identity(path, identity)
        return identity
    p.build_login(name, 0, purpose=3)
    bootstrap_rid, _ = p.bootstrap_session(modulus)
    visited, deadline = set(), time.monotonic() + 60
    for _ in range(4):
        if host in visited or time.monotonic() >= deadline:
            raise ValueError('registration redirect budget exhausted')
        visited.add(host)
        stream = Framed.connect(host, port, stop)
        try:
            crypto = p.SessionCrypto(modulus, bootstrap_rid, p.BOOTSTRAP_PASSWORD, purpose=3)
            authenticate(stream, crypto, deadline)
            control = Control(stream, crypto)
            # Only an authenticated redirect may restart registration. An
            # ambiguous failure after this Login is never retried automatically.
            control.send(p.build_login(name, 0, purpose=3))
            receiver = p.Provisioning()
            for _ in range(16):
                _, identity = receiver.receive(control.receive(deadline))
                if identity:
                    save_identity(path, identity)
                    return identity
            raise ValueError('registration did not return an identity')
        except p.ServerRedirect as exc:
            host = exc.host
        finally:
            stream.close()
    raise ValueError('registration redirect budget exhausted')


def relay_details(data, cid):
    fields = p.parse_records(data)
    if p.read_u64(p.unique(fields, 0x020001c1)) != cid:
        raise ValueError('relay connection ID mismatch')
    fields = p.parse_records(p.unique(fields, 0x123f).value)
    host = str(IPv4Address(p.decode_text(p.unique(fields, 0x030001cb))))
    port = p.unique(fields, 0x010001cc).u32()
    ticket = p.unique(fields, 0x090001ca).value
    if not 0 < port <= 65535 or len(ticket) != 256:
        raise ValueError('invalid relay endpoint or ticket')
    return host, port, ticket


def connection_details(data):
    fields = p.parse_records(data)
    cid = p.read_u64(p.unique(fields, 0x020001c1))
    fields = p.parse_records(p.unique(fields, 0x1235).value)
    rid = p.read_u64(p.unique(fields, 0x020001e1))
    password = p.unique(fields, 0x0a0001cd).value
    if not cid or not rid or not 6 <= len(password) <= 1024:
        raise ValueError('invalid connection credentials')
    return cid, rid, password


def tcp_candidates(data, cid):
    fields = p.parse_records(data)
    if p.read_u64(p.unique(fields, 0x020001c1)) != cid:
        raise ValueError('direct connection ID mismatch')
    out = []
    for rec in p.parse_records(p.unique(fields, 0x1236).value):
        if rec.tag != 0x127d or len(out) >= 4:
            continue
        f = p.parse_records(rec.value)
        ip = ip_address(p.decode_text(p.unique(f, 0x030001c2)))
        port = p.unique(f, 0x010001c3).u32()
        if not ip.is_multicast and not ip.is_unspecified and str(ip) != '255.255.255.255' and 0 < port <= 65535:
            out.append((str(ip), port))
    return out


def rendezvous_checksum(data):
    crc = 0xffffffff
    for byte in data:
        top = crc & 0xff000000
        for _ in range(8):
            top = ((top << 1) ^ (0x04c11db7 if top & 0x80000000 else 0)) & 0xffffffff
        crc = top ^ (((crc << 8) | byte) & 0xffffffff)
    return crc ^ 0xffffffff


class PeerChannel:
    def __init__(self, stream, identity, peer, password, *, incoming=False, ticket=None, transport='relay', timeout=30):
        self.stream, self.peer, self.transport = stream, peer, transport
        until = time.monotonic() + timeout
        try:
            if ticket is not None:
                stream.send(p.u32(0x010001df, 1) + p.u32(0x0100032b, 2) + p.tlv(0x090001ca, ticket), until)
                if p.unique(p.parse_records(stream.receive(65536, until)), 0x010001df).u32() != 2:
                    raise ValueError('relay ticket rejected')
            if incoming:
                sh = p.SHServer(identity.rid, password)
                stream.send(sh.hello(stream.receive(65536, until)), until)
                stream.send(sh.public(stream.receive(65536, until)), until)
                proof, key = sh.proof(stream.receive(65536, until))
                stream.send(proof, until)
            else:
                sh = p.SHClient(peer['rid'], password)
                stream.send(sh.start(), until)
                stream.send(sh.receive_f10(stream.receive(65536, until)), until)
                stream.send(sh.receive_f12(stream.receive(65536, until)), until)
                key = sh.receive_f14(stream.receive(65536, until))
            self.cc = p.PureCC(key[:32])
            own_mac = p.mac_for_ip(identity.ipv4)
            if incoming:
                if self.receive(until) != b'\xef\xbe\xad\xde' or len(self.receive(until)) != 16:
                    raise ValueError('invalid channel race marker')
                f = p.parse_records(self.receive(until))
                if p.read_u64(p.unique(f, 0x020001e1)) != peer['rid']:
                    raise ValueError('service peer identity mismatch')
                self.mac = p.unique(f, 0x09000304).value
                self.version = min(8, p.unique(f, 0x01000302).u32())
                if len(self.mac) != 6 or self.mac[0] & 1 or self.version < 5:
                    raise ValueError('invalid peer service version/MAC')
                self.send(p.u64(0x02000303, 0) + p.u32(0x01000302, self.version) + p.tlv(0x09000304, own_mac))
                ack = p.parse_records(self.receive(until))
                if p.read_u64(p.unique(ack, 0x02000303)) or p.unique(ack, 0x01000302).u32() != self.version:
                    raise ValueError('invalid service ACK')
            else:
                self.send(b'\xef\xbe\xad\xde')
                self.send(uuid.uuid4().bytes_le)
                self.send(p.build_syn(identity.rid, own_mac))
                ack, self.version, self.mac = p.accept_synack(self.receive(until))
                self.send(ack)
        except BaseException:
            stream.close()
            raise

    def send(self, data):
        self.stream.send(self.cc.enc(data))

    def receive(self, until=None):
        return self.cc.dec(self.stream.receive(65536, until))

    def close(self):
        self.stream.close()


def valid_frame(frame, source_ip, source_mac, target_ip=None, target_mac=None):
    """Validate routing and sources, allowing arbitrary ARP reply senders.

    ARP replies deliberately do not bind Ethernet/ARP sender MAC or sender IP
    to the channel identity. Requests and IPv4 retain their source checks.
    Every L2 group destination is delivered to every eligible connected peer.
    """
    if not 14 <= len(frame) <= MAX_ETHERNET:
        return False
    arp_reply = (len(frame) >= 42 and frame[12:14] == b'\x08\x06'
                 and frame[14:22] == b'\0\x01\x08\0\x06\x04\0\x02')
    if not arp_reply and frame[6:12] != source_mac:
        return False
    try:
        if frame[12:14] == b'\x08\x00':
            _, src, dst, _ = p.ipv4_packet(frame[14:], source=source_ip)
            group = p.group_mac(dst)
            if group is not None:
                return frame[:6] == group
            if frame[0] & 1:
                return True
            return target_ip is None or (dst == target_ip and frame[:6] == target_mac)
        if frame[12:14] == b'\x08\x06':
            if len(frame) < 42 or frame[14:20] != b'\0\x01\x08\0\x06\x04' or frame[20:22] not in (b'\0\x01', b'\0\x02'):
                return False
            src, dst = str(IPv4Address(frame[28:32])), str(IPv4Address(frame[38:42]))
            if not arp_reply and (src != source_ip or frame[22:28] != source_mac):
                return False
            if frame[0] & 1:
                return True
            return target_ip is None or dst == target_ip and frame[:6] == target_mac
        # Other L2 protocols (including IPv6) remain available through raw API.
        return target_mac is None or frame[:6] == target_mac or bool(frame[0] & 1)
    except ValueError:
        return False


class PeerWorker:
    def __init__(self, engine, peer, incoming=None):
        self.engine, self.peer, self.incoming = engine, dict(peer), incoming
        self.stop = threading.Event()
        self.tx, self.offers = WakeQueue(128), queue.Queue(16)
        self.channel = None
        self.state, self.error, self.transport = 'connecting', None, None
        self.thread = threading.Thread(target=self.run, name=f'peer-{peer["rid"]}', daemon=True)
        self.rx_packets = self.tx_packets = 0
        self.retry_at = 0
        self.last_use = 0

    def outgoing(self):
        identity, engine = self.engine.identity, self.engine
        stream = Framed.connect(self.peer['server'], 17301, self.stop)
        coord = None
        try:
            crypto = p.SessionCrypto(engine.modulus, identity.rid, identity.credential, purpose=5)
            latency = authenticate(stream, crypto)
            coord = Control(stream, crypto)
            coord.send(p.build_connect_login(identity.node_name, latency, self.peer['rid']))
            until = time.monotonic() + 30
            cid = password = relay = None
            server_id, candidates, relay_at = 0, [], 0
            for _ in range(64):
                if password and ((candidates and server_id) or
                                 relay and (engine.force_relay or time.monotonic() - relay_at >= 4)):
                    break
                try:
                    data = coord.receive(min(until, time.monotonic() + .5))
                except TimeoutError:
                    if time.monotonic() >= until:
                        raise TimeoutError('peer coordinator timed out') from None
                    continue
                op, fields = p.operation(data), p.parse_records(data)
                if op == 21:
                    value = p.unique(fields, 0x0100025b, required=False)
                    server_id = value.u32() if value else 0
                elif op == 11:
                    if cid is not None:
                        raise ValueError('duplicate connection offer')
                    cid, rid, password = connection_details(data)
                    if rid != self.peer['rid']:
                        raise ValueError('connection offer target mismatch')
                    if not engine.force_relay:
                        coord.send(p.u32(p.CLIENT_OPERATION, 2) + p.u64(0x020001c1, cid))
                    coord.send(p.build_request_relay(cid))
                elif op == 6 and not engine.force_relay and cid is not None:
                    candidates = tcp_candidates(data, cid)
                elif op == 23 and cid is not None:
                    relay = relay_details(data, cid)
                    relay_at = time.monotonic()
                elif op in (16, 44):
                    raise ValueError(f'peer coordinator refused connection (operation {op})')
            if password is None:
                raise ValueError('coordinator did not return connection credentials')
            if not engine.force_relay and server_id:
                for host, port in candidates:
                    direct = None
                    try:
                        direct = Framed.connect(host, port, self.stop, timeout=1.5)
                        body = struct.pack('>QQI', identity.rid, cid, server_id)
                        direct.write(b'\0\x14\0\x01' + body)
                        check = direct.read_exact(4, time.monotonic() + 2)
                        if int.from_bytes(check, 'big') != rendezvous_checksum(body):
                            raise ValueError('TCP rendezvous checksum mismatch')
                        channel = PeerChannel(direct, identity, self.peer, password, transport='direct-tcp')
                        return channel, coord
                    except (OSError, ValueError) as exc:
                        if direct:
                            direct.close()
                        engine.report('direct_failed', rid=self.peer['rid'], error=str(exc))
            if relay is None:
                # Direct candidates do not depend on a relay offer. If every
                # direct attempt failed, continue discovery for the fallback.
                while time.monotonic() < until and relay is None:
                    try:
                        data = coord.receive(min(until, time.monotonic() + .5))
                    except TimeoutError:
                        continue
                    if p.operation(data) == 23:
                        relay = relay_details(data, cid)
                if relay is None:
                    raise ValueError('all direct paths failed and no relay offer arrived')
            host, port, ticket = relay
            relay_stream = Framed.connect(host, port, self.stop)
            return PeerChannel(relay_stream, identity, self.peer, password, ticket=ticket), coord
        except BaseException:
            stream.close()
            raise
        finally:
            # Drop the offer password on every path, including failure; see accept().
            password = None

    def accept(self):
        engine, identity = self.engine, self.engine.identity
        cid, password = self.incoming
        listener, cancel, results, attempts = None, threading.Event(), queue.Queue(), []
        until = time.monotonic() + 40
        winner = None
        def attempt(kind, operation):
            try:
                channel = operation()
                if cancel.is_set() or self.stop.is_set():
                    channel.close()
                else:
                    results.put((kind, channel, None))
            except (OSError, ValueError) as exc:
                results.put((kind, None, type(exc).__name__))
        def launch(kind, operation):
            thread = threading.Thread(target=attempt, args=(kind, operation),
                                      name=f'incoming-{kind}-{self.peer["rid"]}', daemon=True)
            attempts.append(thread)
            thread.start()
        def accept_tcp():
            accepted = 0
            while not cancel.is_set() and not self.stop.is_set() and time.monotonic() < until:
                if not poll_readable([listener], .1):
                    continue
                sock, _ = listener.accept()
                accepted += 1
                if accepted > 8:
                    sock.close()
                    raise ValueError('incoming TCP accept budget exhausted')
                direct = Framed(sock, cancel)
                try:
                    data = direct.read_exact(24, min(until, time.monotonic() + 3))
                    if data[:4] != b'\0\x14\0\x01' or int.from_bytes(data[4:12], 'big') != self.peer['rid'] or int.from_bytes(data[12:20], 'big') != cid or not int.from_bytes(data[20:], 'big'):
                        raise ValueError('incoming TCP rendezvous identity mismatch')
                    direct.write(struct.pack('>I', rendezvous_checksum(data[4:])))
                    return PeerChannel(direct, identity, self.peer, password, incoming=True,
                                       transport='direct-tcp', timeout=min(10, max(.1, until - time.monotonic())))
                except (OSError, ValueError):
                    direct.close()
            raise TimeoutError('incoming TCP setup timed out')
        def accept_relay(relay):
            host, port, ticket = relay
            stream = Framed.connect(host, port, cancel)
            return PeerChannel(stream, identity, self.peer, password, incoming=True, ticket=ticket,
                               timeout=max(.1, until - time.monotonic()))
        try:
            if not engine.force_relay:
                # Listen on both IP families when available; advertise the
                # actual routed address rather than an unspecified endpoint.
                try:
                    listener = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
                    listener.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                    listener.bind(('::', 0))
                except OSError:
                    if listener:
                        listener.close()
                    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    listener.bind(('0.0.0.0', 0))
                listener.listen(8)
                listener.setblocking(False)
                port = listener.getsockname()[1]
                engine.wire.put_nowait(p.u32(p.CLIENT_OPERATION, 2) + p.u64(0x020001c1, cid)
                    + p.tlv(0x1230, p.tlv(0x127d, p.text16(0x030001c2, engine.route_ip) + p.u32(0x010001c3, port))))
                launch('tcp', accept_tcp)
            relay, relay_at, relay_started = None, 0, False
            while not self.stop.is_set() and time.monotonic() < until:
                while not results.empty():
                    kind, channel, error = results.get_nowait()
                    if channel is not None:
                        channel.stream.stop = self.stop
                        winner = channel
                        return channel
                    engine.report('incoming_path_failed', rid=self.peer['rid'], path=kind, error=error)
                if relay and not relay_started and (engine.force_relay or time.monotonic() - relay_at >= 4):
                    relay_started = True
                    launch('relay', lambda: accept_relay(relay))
                try:
                    data = self.offers.get(timeout=.1)
                except queue.Empty:
                    continue
                if p.operation(data) == 23 and relay is None:
                    relay = relay_details(data, cid)
                    relay_at = time.monotonic()
            raise TimeoutError('incoming peer setup timed out')
        finally:
            # The offer password is consumed by PeerChannel setup above; drop
            # it here too so tracebacks cannot retain it. Bytes are immutable,
            # so CPython cannot wipe the underlying memory (see AGENTS.md).
            password = None
            cancel.set()
            if listener:
                listener.close()
            for thread in attempts:
                thread.join(timeout=6.5)
            while not results.empty():
                _, channel, _ = results.get_nowait()
                if channel is not None and channel is not winner:
                    channel.close()

    def run(self):
        coord = None
        try:
            if self.incoming:
                self.channel = self.accept()
            else:
                self.channel, coord = self.outgoing()
            self.transport, self.state = self.channel.transport, 'connected'
            self.engine.report('peer_connected', rid=self.peer['rid'], transport=self.transport,
                               incoming=bool(self.incoming))
            # Announce userspace MAC/VIP without relying on a kernel interface.
            self.channel.send(p.encode_frame(p.arp_frame(p.mac_for_ip(self.engine.identity.ipv4),
                self.engine.identity.ipv4, b'\xff' * 6, self.engine.identity.ipv4, reply=True)))
            heartbeat = time.monotonic() + 15
            last_rx, sequence = time.monotonic(), 0
            poller = select.poll()
            sock_fd, wake_fd = self.channel.stream.sock.fileno(), self.tx.reader.fileno()
            poller.register(sock_fd, select.POLLIN)
            poller.register(wake_fd, select.POLLIN)
            while not self.stop.is_set() and not self.engine.stop.is_set():
                now = time.monotonic()
                for _ in range(16):
                    try:
                        frame = self.tx.get_nowait()
                    except queue.Empty:
                        break
                    self.channel.send(p.encode_frame(frame))
                    self.tx_packets += 1
                    self.engine.count('peer_tx')
                if now >= heartbeat:
                    sequence = sequence % 0xffffffff + 1
                    self.channel.send(p.keepalive(sequence))
                    heartbeat = now + 15
                if now - last_rx > 65:
                    raise TimeoutError('peer stopped answering keepalives')
                if len(self.channel.stream.buffer) < 4:
                    ready = dict(poller.poll(0 if not self.tx.empty() else math.ceil(min(.2, max(0, heartbeat - now)) * 1000)))
                    if wake_fd in ready:
                        self.tx.drain_wake()
                    if sock_fd not in ready:
                        continue
                if self.stop.is_set():
                    continue
                try:
                    packet = p.decode_packet(self.channel.receive(time.monotonic() + .2))
                except TimeoutError:
                    continue
                last_rx = time.monotonic()
                if packet['kind'] == 3:
                    self.channel.send(p.keepalive(packet['sequence'], reply=True))
                elif packet['kind'] == 0:
                    # decode_packet verifies every envelope before returning.
                    for entry in packet['frames']:
                        self.rx_packets += 1
                        self.engine.incoming_frame(self, entry['ethernet'])
        except (OSError, ValueError, queue.Full) as exc:
            if not self.stop.is_set() and not self.engine.stop.is_set():
                self.error = str(exc)
                self.engine.report('peer_failed', rid=self.peer['rid'], error=self.error)
        except Exception as exc:
            # A bad peer cannot kill the control or localhost UDP service.
            self.error = type(exc).__name__
            self.engine.report('peer_failed', rid=self.peer['rid'], error=self.error)
        finally:
            # The channel consumed the offer password during setup; drop the
            # (cid, password) tuple so the worker does not retain it.
            self.incoming = None
            self.state = 'closed'
            self.retry_at = time.monotonic() + 30 + secrets.randbelow(10)
            if self.channel:
                self.channel.close()
            if coord:
                coord.close()
            self.tx.close()


class Engine:
    def __init__(self, identity, profile, *, networks=(), force_relay=False, passive=False,
                 max_peers=32, modulus=p.SERVER_MODULUS, port=p.DEFAULT_PORT):
        self.identity, self.profile, self.modulus, self.port = identity, profile, modulus, port
        self.selected, self.force_relay, self.passive = set(networks), force_relay, passive
        self.max_peers = max_peers
        # Keep the default 32-peer client's four setup slots; larger requested
        # loads get a bounded ramp-up instead of serializing hundreds of dials.
        self._max_connecting = min(64, max(4, (max_peers + 7) // 8))
        self._schedule_capacity = None
        self.lock, self.stop = threading.RLock(), threading.Event()
        self.membership, self.workers, self.pending = p.Membership(identity.rid), {}, {}
        self.desired = {}
        self.wire, self.commands = queue.Queue(128), queue.Queue(32)
        self.events, self.counters = deque(maxlen=64), {}
        self.state, self.error, self.mode, self.route_ip = 'starting', None, 'server', '127.0.0.1'
        self.on_frame = lambda rid, frame: None
        self.frame_listeners = set()
        self.tap = None
        self.thread = threading.Thread(target=self.run, name='control', daemon=True)

    def report(self, event, **details):
        with self.lock:
            self.events.append(dict(event=event, time=time.time(), **details))

    def count(self, key, amount=1):
        with self.lock:
            self.counters[key] = self.counters.get(key, 0) + amount

    def add_frame_listener(self, callback):
        with self.lock:
            self.frame_listeners.add(callback)

    def remove_frame_listener(self, callback):
        with self.lock:
            self.frame_listeners.discard(callback)

    def save_settings(self):
        private_json(self.profile.path / 'settings.json', dict(force_relay=self.force_relay, networks=sorted(self.selected)))

    def snapshot(self, peers=False, cursor=0, limit=64):
        with self.lock:
            out = dict(state=self.state, mode=self.mode, uid=os.geteuid(), tun_required=False,
                rid=self.identity.rid, vip=self.identity.ipv4, node_name=self.identity.node_name,
                mac=p.mac_for_ip(self.identity.ipv4).hex(':'), mtu=MTU, force_relay=self.force_relay,
                networks=list(self.membership.networks.values())[:32], network_count=len(self.membership.networks),
                connected=sum(w.state == 'connected' for w in self.workers.values()),
                connecting=sum(w.state == 'connecting' for w in self.workers.values()),
                incoming_connected=sum(w.state == 'connected' and bool(w.incoming) for w in self.workers.values()),
                pending_incoming=len(self.pending),
                counters=dict(self.counters), error=self.error, recent_events=list(self.events)[-12:])
            if peers:
                eligible = self.membership.eligible(self.selected)
                items = sorted(self.membership.peers.values(), key=lambda peer: peer['rid'])
                out['peers'] = []
                for peer in items[cursor:cursor + limit]:
                    worker = self.workers.get(peer['rid'])
                    out['peers'].append(dict(peer, eligible=peer['rid'] in eligible,
                        connection=worker.state if worker else 'idle', transport=worker.transport if worker else None,
                        error=worker.error if worker else None))
                out['cursor'] = cursor + limit if cursor + limit < len(items) else None
                out['peer_count'] = len(items)
            return out

    def _spawn(self, peer, incoming=None, records=()):
        if self.stop.is_set():
            return False
        rid = peer['rid']
        old = self.workers.get(rid)
        if old and old.thread.is_alive():
            return False
        capacity = self._schedule_capacity
        if capacity is None:
            capacity = [sum(w.thread.is_alive() for w in self.workers.values()),
                        sum(w.state == 'connecting' for w in self.workers.values())]
        if capacity[0] >= self.max_peers:
            return False
        # Outgoing scans must leave incoming offers two additional handshake
        # slots, otherwise a full scan can delay them past the remote timeout.
        if capacity[1] >= self._max_connecting + (2 if incoming else 0):
            return False
        worker = PeerWorker(self, peer, incoming)
        for data in records:
            worker.offers.put_nowait(data)
        self.workers[rid] = worker
        worker.thread.start()
        capacity[0] += 1
        capacity[1] += 1
        return True

    def schedule(self):
        with self.lock:
            now, eligible = time.monotonic(), self.membership.eligible(self.selected)
            # One count per scheduling pass, rather than scanning every live
            # thread for each waiting peer when the setup/connection cap is full.
            self._schedule_capacity = [sum(w.thread.is_alive() for w in self.workers.values()),
                                       sum(w.state == 'connecting' for w in self.workers.values())]
            try:
                self._schedule_peers(now, eligible)
            finally:
                self._schedule_capacity = None

    def _schedule_peers(self, now, eligible):
        self.desired = {rid: until for rid, until in self.desired.items() if now < until and rid in eligible}
        for rid, worker in list(self.workers.items()):
            if rid not in eligible:
                worker.stop.set()
                if not worker.thread.is_alive():
                    del self.workers[rid]
        # Process authenticated incoming work before explicit/automatic dials.
        for cid, offer in sorted(list(self.pending.items()), key=lambda item: item[1]['rid'] not in self.desired):
            if now - offer['created'] > 40:
                expired = self.pending.pop(cid)
                # Offers expire after 40 s. Bytes are immutable, so drop the
                # reference instead of wiping (see AGENTS.md).
                expired['password'] = None
            elif self.force_relay and not any(p.operation(data) == 23 for data in offer['records']):
                continue
            elif offer['rid'] in eligible and self._spawn(eligible[offer['rid']], (cid, offer['password']), offer['records']):
                del self.pending[cid]
        if self.passive:
            for rid in self.desired:
                if not any(o['rid'] == rid for o in self.pending.values()):
                    self._spawn(eligible[rid])
            return
        offered = {o['rid'] for o in self.pending.values()}
        for rid in self.desired:
            if rid not in offered:
                self._spawn(eligible[rid])
        for rid, peer in eligible.items():
            old = self.workers.get(rid)
            if rid not in offered and (not old or not old.thread.is_alive() and now >= old.retry_at):
                self._spawn(peer)

    def control_data(self, data):
        op = p.operation(data)
        self.count(f'control_op_{op}')
        if op == 37:
            fields = p.parse_records(p.unique(p.parse_records(data), 0x131a).value)
            self.report('network_response', request_id=p.read_u64(p.unique(fields, 0x02000340)),
                action=p.unique(fields, 0x0100030c).u32())
        p.check_redirect(p.parse_records(data))
        if op == 16:
            raise ConnectionError('server disconnected the attachment session')
        with self.lock:
            if op == 38:
                self.membership.snapshot(data)
            elif op in (41, 42):
                self.membership.changes(data)
            if op == 11:
                cid, rid, password = connection_details(data)
                duplicate = cid in self.pending or any(o['rid'] == rid for o in self.pending.values())
                if not duplicate and len(self.pending) < 64:
                    worker = self.workers.get(rid)
                    if worker is None or not worker.thread.is_alive():
                        self.pending[cid] = dict(rid=rid, password=password, created=time.monotonic(), records=[])
                    elif worker.state == 'connecting' and not worker.incoming and self.identity.rid > rid:
                        # Both nodes may dial together; converge on the lower initiator.
                        worker.stop.set()
                        self.pending[cid] = dict(rid=rid, password=password, created=time.monotonic(), records=[])
            elif op in (6, 7, 23, 29):
                cid = p.read_u64(p.unique(p.parse_records(data), 0x020001c1))
                if cid in self.pending:
                    if len(self.pending[cid]['records']) < 8:
                        self.pending[cid]['records'].append(data)
                else:
                    for worker in self.workers.values():
                        if worker.incoming and worker.incoming[0] == cid and worker.state == 'connecting':
                            try:
                                worker.offers.put_nowait(data)
                            except queue.Full:
                                self.count('offer_dropped')
                            break

    def _network_command(self, body, callback, sequence):
        action = body.get('op')
        nid = body.get('network_id')
        name = body.get('name')
        if action in ('leave', 'delete') and nid is not None:
            nid = p.network_id(bytes.fromhex(nid))
            network = self.membership.networks.get(nid)
            if network is None:
                raise ValueError('network is not an authenticated membership')
            name = network['name']
        if action in ('leave', 'delete') and nid is None:
            matches = [n for n in self.membership.networks.values() if n['name'] == body.get('name')]
            if len(matches) != 1:
                raise ValueError('network is not an authenticated membership')
            nid = matches[0]['network_id']
        # The service's correlation counter is serialized as u64 but native
        # clients allocate small monotonic IDs. Follow that interoperability rule.
        op = p.NetworkOperation(action, sequence, sequence,
            name=name, password=body.get('password'), nid=nid)
        self.report('network_request', request_id=sequence, action=action)
        return dict(kind='network', op=op, callback=callback, until=time.monotonic() + 35)

    def _start_command(self, body, callback, sequence):
        action = body.get('op')
        if action in ('join', 'create', 'leave', 'delete'):
            return self._network_command(body, callback, sequence)
        if action == 'search':
            query = body.get('query', '')
            if not isinstance(query, str):
                raise ValueError('query must be a string')
            request = sequence
            return dict(kind='search', packet=p.build_public_list(query, request_id=request, cursor=int(body.get('cursor', 0))),
                request=request, callback=callback, until=time.monotonic() + 20)
        if action == 'select_networks':
            names = body.get('names')
            if not isinstance(names, (list, tuple)) or len(names) > 32:
                raise ValueError('network selection must contain at most 32 names')
            for name in names:
                p.validate_network_name(name)
            with self.lock:
                self.selected = set(names)
                self.save_settings()
            callback(dict(ok=True, networks=sorted(self.selected)))
        elif action == 'force_relay':
            if type(body.get('enabled')) is not bool:
                raise ValueError('enabled must be a boolean')
            with self.lock:
                self.force_relay = body['enabled']
                # Retire direct channels and setups so policy applies in both directions.
                for worker in self.workers.values():
                    if worker.state == 'connecting' or self.force_relay and worker.transport != 'relay':
                        worker.stop.set()
                        worker.retry_at = 0
                self.save_settings()
            callback(dict(ok=True, force_relay=self.force_relay))
        elif action == 'connect':
            rid = int(body['rid'])
            with self.lock:
                peer = self.membership.eligible(self.selected).get(rid)
                if peer is None:
                    raise ValueError('peer is not online in an approved selected network')
                self.desired[rid] = time.monotonic() + 300
                worker = self.workers.get(rid)
                if worker and worker.thread.is_alive():
                    callback(dict(ok=True, connection=worker.state))
                else:
                    if worker:
                        worker.retry_at = 0
                    if not self._spawn(peer):
                        candidates = [w for key, w in self.workers.items() if key not in self.desired
                            and w.thread.is_alive() and not w.stop.is_set()]
                        if candidates:
                            min(candidates, key=lambda w: w.last_use).stop.set()
                        callback(dict(ok=True, connection='queued'))
                    else:
                        callback(dict(ok=True, connection='connecting'))
        elif action == 'stop':
            callback(dict(ok=True, stopping=True))
            self.stop.set()
        else:
            raise ValueError('unknown command')
        return None

    def run(self):
        retry, sequence, active = 1, 1, None
        host, redirected = self.identity.server_address, set()
        startup = deque(dict(op='join', name=name) for name in sorted(self.selected))
        while not self.stop.is_set():
            control = None
            try:
                self.state = 'connecting'
                control = Control.login(host, self.identity, self.modulus, stop=self.stop, port=self.port)
                receiver, until = p.Registration(), time.monotonic() + 20
                members = p.Membership(self.identity.rid)
                for _ in range(64):
                    data = control.receive(until)
                    _, assignment = receiver.receive(data)
                    members.snapshot(data)
                    if assignment:
                        self.identity = replace(self.identity, ipv4=assignment.ipv4, address=assignment.address, server_address=host)
                        save_identity(self.profile.path / 'identity.json', self.identity)
                        break
                else:
                    raise ValueError('login did not return a VIP')
                with self.lock:
                    self.membership = members
                    self.route_ip = control.stream.sock.getsockname()[0]
                    self.state, self.error = 'online', None
                self.report('online', rid=self.identity.rid, vip=self.identity.ipv4)
                retry, heartbeat = 1, time.monotonic()
                while not self.stop.is_set():
                    now = time.monotonic()
                    if now >= heartbeat:
                        control.send(p.u32(p.CLIENT_OPERATION, 4))
                        heartbeat = now + 10
                        self.count('control_heartbeats')
                    for _ in range(8):
                        try:
                            control.send(self.wire.get_nowait())
                        except queue.Empty:
                            break
                    if active is None:
                        try:
                            body, callback = self.commands.get_nowait()
                        except queue.Empty:
                            if startup:
                                body = startup.popleft()
                                with self.lock:
                                    exists = any(n['name'] == body['name'] for n in self.membership.networks.values())
                                if exists:
                                    body = None
                                callback = lambda result: self.report('network_result', **result)
                            else:
                                body = None
                        if body is not None:
                            sequence = sequence % 0xffffffff + 1
                            try:
                                active = self._start_command(body, callback, sequence)
                                if active:
                                    control.send(active['op'].packet if active['kind'] == 'network' else active['packet'])
                            except (ValueError, KeyError, TypeError) as exc:
                                callback(dict(ok=False, error=str(exc)))
                            finally:
                                body.pop('password', None)
                    if active and now >= active['until']:
                        active['callback'](dict(ok=False, error='network operation timed out'))
                        active = None
                    self.schedule()
                    if not control.stream.ready(.05):
                        continue
                    try:
                        data = control.receive(time.monotonic() + .2)
                    except TimeoutError:
                        continue
                    self.control_data(data)
                    if active:
                        try:
                            if active['kind'] == 'network':
                                with self.lock:
                                    send, result = active['op'].handle(data, self.membership)
                                if send:
                                    control.send(send)
                            elif p.operation(data) == 45:
                                # Query result correlation is checked by public_listing.
                                root = p.parse_records(p.unique(p.parse_records(data), 0x1334).value)
                                result = p.public_listing(data, active['request']) if p.read_u64(p.unique(root, 0x02000340)) == active['request'] else None
                            else:
                                result = None
                            if result is not None:
                                if active['kind'] == 'network' and active['op'].action in ('join', 'create'):
                                    self.selected.add(active['op'].name)
                                    self.save_settings()
                                elif active['kind'] == 'network' and active['op'].action in ('leave', 'delete'):
                                    self.selected.discard(active['op'].name)
                                    self.save_settings()
                                active['callback'](dict(ok=True, **result))
                                active = None
                        except ValueError as exc:
                            active['callback'](dict(ok=False, error=str(exc)))
                            active = None
            except p.ServerRedirect as exc:
                if exc.host in redirected or len(redirected) >= 4:
                    self.error = 'server redirect loop'
                    self.stop.set()
                else:
                    redirected.add(host)
                    host = exc.host
                    self.report('redirect', host=host)
            except (OSError, ValueError) as exc:
                if not self.stop.is_set():
                    self.error, self.state = str(exc), 'reconnecting'
                    self.report('control_failed', error=self.error)
                    self.stop.wait(retry)
                    retry = min(30, retry * 2)
            except Exception as exc:
                self.error, self.state = type(exc).__name__, 'reconnecting'
                self.report('control_failed', error=self.error)
                self.stop.wait(retry)
                retry = min(30, retry * 2)
            finally:
                if active:
                    active['callback'](dict(ok=False, error='control connection closed'))
                    active = None
                if control:
                    control.close()
                with self.lock:
                    for worker in self.workers.values():
                        worker.stop.set()
                    # Drop offer passwords with the pending entries (see above).
                    for offer in self.pending.values():
                        offer['password'] = None
                    self.pending.clear()
                    while not self.wire.empty():
                        try:
                            self.wire.get_nowait()
                        except queue.Empty:
                            break
        self.state = 'stopped'

    def command(self, body, callback):
        if self.stop.is_set():
            raise ValueError('engine is stopped')
        if self.state != 'online' and body.get('op') != 'stop':
            raise ValueError('control session is not online')
        if body.get('op') == 'stop':
            callback(dict(ok=True, stopping=True))
            self.stop.set()
            return
        try:
            self.commands.put_nowait((body, callback))
        except queue.Full:
            raise ValueError('control command queue is full') from None

    def send_ipv4(self, ip, rid=0):
        ip, _, target, _ = p.ipv4_packet(ip, source=self.identity.ipv4)
        if len(ip) > MTU:
            raise ValueError('IPv4 packet exceeds MTU 1500; fragment it through the raw API')
        group = p.group_mac(target)
        with self.lock:
            eligible = self.membership.eligible(self.selected) if not rid else None
            candidates = self.workers.items() if not rid else ((rid, self.workers[rid]),) if rid in self.workers else ()
            ready = [w for key, w in candidates if w.state == 'connected' and not w.stop.is_set()
                and (key in eligible if eligible is not None else self.membership.permits(key, self.selected))
                and (group is not None or w.peer['vip'] == target)]
            if not ready:
                raise ValueError('no authenticated connection to the destination; use connect and wait for connected')
            queued = 0
            for worker in ready:
                frame = (group or worker.channel.mac) + p.mac_for_ip(self.identity.ipv4) + b'\x08\x00' + ip
                try:
                    worker.tx.put_nowait(frame)
                    worker.last_use = time.monotonic()
                    queued += 1
                except queue.Full:
                    self.count('tx_queue_dropped')
            if not queued:
                raise ValueError('peer transmit queue is full')
        return dict(ok=True, queued=queued, bytes=len(ip))

    def send_ethernet(self, frame, rid=0):
        if not valid_frame(frame, self.identity.ipv4, p.mac_for_ip(self.identity.ipv4)):
            raise ValueError('invalid Ethernet frame or source identity')
        queued = 0
        with self.lock:
            eligible = self.membership.eligible(self.selected) if not rid else None
            candidates = self.workers.items() if not rid else ((rid, self.workers[rid]),) if rid in self.workers else ()
            for key, worker in candidates:
                permitted = key in eligible if eligible is not None else self.membership.permits(key, self.selected)
                if not permitted or worker.state != 'connected' or worker.stop.is_set():
                    continue
                if not valid_frame(frame, self.identity.ipv4, p.mac_for_ip(self.identity.ipv4), worker.peer['vip'], worker.channel.mac):
                    continue
                try:
                    worker.tx.put_nowait(frame)
                    worker.last_use = time.monotonic()
                    queued += 1
                except queue.Full:
                    self.count('tx_queue_dropped')
        if not queued:
            raise ValueError('no matching connected peer or peer transmit queue is full')
        return dict(ok=True, queued=queued, bytes=len(frame))

    def incoming_frame(self, worker, frame):
        identity = self.identity
        with self.lock:
            permitted = self.membership.permits(worker.peer['rid'], self.selected)
            listeners = (self.on_frame, *self.frame_listeners)
        if not permitted or not valid_frame(frame, worker.peer['vip'], worker.channel.mac, identity.ipv4, p.mac_for_ip(identity.ipv4)):
            self.count('invalid_rx')
            return
        self.count('peer_rx')
        for callback in listeners:
            try:
                callback(worker.peer['rid'], frame)
            except Exception as exc:
                self.count('frame_listener_failed')
                self.report('frame_listener_failed', error=type(exc).__name__)
        if self.tap:
            self.tap.deliver(frame)
            return
        # Only local address resolution and echo responses are synthesized.
        reply = None
        if frame[12:14] == b'\x08\x06' and frame[20:22] == b'\0\x01' and frame[38:42] == IPv4Address(identity.ipv4).packed:
            reply = p.arp_frame(p.mac_for_ip(identity.ipv4), identity.ipv4, worker.channel.mac, worker.peer['vip'], reply=True)
        elif frame[12:14] == b'\x08\x00' and frame[23] == 1:
            ip, _, target, header = p.ipv4_packet(frame[14:])
            echo = ip[header:]
            if target == identity.ipv4 and ip[9] == 1 and not int.from_bytes(ip[6:8], 'big') & 0x3fff and len(echo) >= 8 and echo[:2] == b'\x08\0' and not p.checksum(echo):
                echo = b'\0\0\0\0' + echo[4:]
                echo = echo[:2] + struct.pack('>H', p.checksum(echo)) + echo[4:]
                reply = p.ipv4_frame(p.mac_for_ip(identity.ipv4), worker.channel.mac, identity.ipv4, worker.peer['vip'], echo)
        if reply:
            try:
                worker.tx.put_nowait(reply)
            except queue.Full:
                self.count('tx_queue_dropped')

    def close(self):
        self.stop.set()
        with self.lock:
            workers = list(self.workers.values())
            for worker in workers:
                worker.stop.set()
        if self.thread.is_alive():
            self.thread.join(timeout=8)
        deadline = time.monotonic() + 7
        for worker in workers:
            if worker.thread.ident is not None:
                worker.thread.join(timeout=max(0, deadline - time.monotonic()))
        if self.tap:
            self.tap.close()


def has_net_admin():
    try:
        cap = next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('CapEff:'))
        return bool(int(cap, 16) & (1 << 12))
    except (OSError, StopIteration, ValueError):
        return os.geteuid() == 0


class Tap:
    """Optional TAP, created only with privileges already held by this process."""
    def __init__(self, engine, name='openradpy0'):
        if not name or len(name) > 15 or not all(c.isalnum() or c in '_-' for c in name):
            raise ValueError('invalid Linux TAP name')
        self.engine, self.fd = engine, os.open('/dev/net/tun', os.O_RDWR | os.O_NONBLOCK)
        try:
            response = fcntl.ioctl(self.fd, 0x400454ca, struct.pack('16sH22x', name.encode(), 0x1002))
            self.name = response[:16].split(b'\0', 1)[0].decode()
            for args in (['ip', 'link', 'set', self.name, 'address', p.mac_for_ip(engine.identity.ipv4).hex(':')],
                         ['ip', 'addr', 'replace', engine.identity.ipv4 + '/8', 'dev', self.name],
                         ['ip', 'link', 'set', self.name, 'mtu', str(MTU), 'up']):
                subprocess.run(args, check=True, capture_output=True, timeout=5)
        except BaseException:
            os.close(self.fd)
            self.fd = -1
            raise
        self.thread = threading.Thread(target=self.run, name='tap', daemon=True)

    def deliver(self, frame):
        try:
            os.write(self.fd, frame)
        except (BlockingIOError, OSError):
            self.engine.count('tap_rx_dropped')

    def run(self):
        while not self.engine.stop.is_set():
            try:
                if poll_readable([self.fd], .2):
                    frame = os.read(self.fd, MAX_ETHERNET + 1)
                    try:
                        self.engine.send_ethernet(frame)
                    except ValueError:
                        self.engine.count('tap_tx_dropped')
            except (OSError, ValueError):
                break

    def close(self):
        if self.fd >= 0:
            os.close(self.fd)
            self.fd = -1
        if self.thread.is_alive():
            self.thread.join(timeout=1)

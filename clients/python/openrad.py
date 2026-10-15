#!/usr/bin/env python3
"""Linux OpenRAD: asynchronous in-process client and localhost UDP API.

Import OpenRad to run without a local UDP socket or TUN, or run
`python3 openrad.py run` for the authenticated localhost UDP API (ORAD v1).
"""
from collections import OrderedDict, deque
import argparse
import asyncio
from dataclasses import dataclass
import getpass
import hashlib
import hmac
from ipaddress import IPv4Address
import json
import math
import os
from pathlib import Path
import queue
import secrets
import select
import signal
import socket
import struct
import subprocess
import sys
import threading
import time

import radproto as p
from radvpn import Engine, Framed, MTU, PeerChannel, Profile, Tap, WakeQueue, ensure_identity, has_net_admin, poll_readable, private_json


VERSION = '2.0.1'
__version__ = VERSION
MAGIC = b'ORAD'
HEADER = struct.Struct('!4sBBHIQI16s')
MAX_DATAGRAM = 64000
HELLO, STATUS, PEERS, COMMAND, BYE = 1, 2, 3, 4, 5
SEND_ETHERNET, SEND_IPV4, SEND_UDP = 16, 17, 18
RX_ETHERNET, RX_IPV4, RX_UDP = 64, 65, 66
RESPONSE = 128
ERROR = 1
UDP_SEND = struct.Struct('!4sHH')
UDP_RECEIVE = struct.Struct('!4s4sHH')
CLIENT_TTL = 120


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def encode_message(opcode, request, token, payload=b'', *, peer=0, flags=0):
    if not isinstance(opcode, int) or not 0 <= opcode < 256 or not isinstance(request, int) or not 0 <= request < 1 << 32:
        raise ValueError('invalid UDP API opcode or request ID')
    if not isinstance(peer, int) or not 0 <= peer < 1 << 64 or flags not in (0, ERROR):
        raise ValueError('invalid UDP API peer or flags')
    if len(token) != 16 or len(payload) > MAX_DATAGRAM - HEADER.size:
        raise ValueError('invalid UDP API token or payload length')
    return HEADER.pack(MAGIC, 1, opcode, flags, request, peer, len(payload), token) + payload


def decode_message(data, token):
    if len(data) < HEADER.size or len(data) > MAX_DATAGRAM:
        raise ValueError('invalid UDP API datagram size')
    magic, version, opcode, flags, request, peer, size, received = HEADER.unpack_from(data)
    if magic != MAGIC or version != 1 or size != len(data) - HEADER.size or flags & ~ERROR:
        raise ValueError('invalid UDP API header')
    if not hmac.compare_digest(received, token):
        raise ValueError('UDP API authentication failed')
    return opcode, flags, request, peer, data[HEADER.size:]


def json_object(data):
    if len(data) > 16384:
        raise ValueError('JSON request exceeds 16384 bytes')
    try:
        value = json.loads(data or b'{}')
    except RecursionError:
        raise ValueError('JSON nesting exceeds parser limit') from None
    if not isinstance(value, dict):
        raise ValueError('JSON request must be an object')
    return value


class UDPServer:
    """A bounded localhost-only API; invalid/authless datagrams receive no reply."""
    def __init__(self, engine, profile, port=0):
        if port and not 1024 <= port <= 65535:
            raise ValueError('localhost API port must be high (1024–65535), or 0 for random')
        self.engine, self.profile, self.token = engine, profile, secrets.token_bytes(16)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
        self.sock.setblocking(False)
        self.sock.bind(('127.0.0.1', port))
        self.endpoint = self.sock.getsockname()
        self.clients, self.cache = {}, OrderedDict()
        self.frames, self.replies = WakeQueue(512), WakeQueue(64)
        self.event_sequence = 0
        self.path = profile.path / 'api.json'
        private_json(self.path, dict(protocol='ORAD', version=1, host='127.0.0.1', port=self.endpoint[1],
            token=self.token.hex(), pid=os.getpid(), uid=os.geteuid(), header_bytes=HEADER.size, mtu=MTU))
        self.engine.add_frame_listener(self.incoming)

    def incoming(self, rid, frame):
        try:
            self.frames.put_nowait((rid, frame))
        except queue.Full:
            self.engine.count('api_rx_dropped')

    def send(self, address, data):
        try:
            self.sock.sendto(data, address)
            return True
        except OSError:
            self.engine.count('api_send_dropped')
            return False

    def complete(self, key, result):
        try:
            self.replies.put_nowait((key, result))
        except queue.Full:
            self.engine.count('api_reply_dropped')

    def _reply(self, key, value):
        record = self.cache.get(key)
        if record is None:
            return
        try:
            data = encode_message(record['opcode'] | RESPONSE, key[1], self.token, json_bytes(value),
                flags=0 if value.get('ok', True) else ERROR)
        except ValueError:
            data = encode_message(record['opcode'] | RESPONSE, key[1], self.token,
                json_bytes(dict(ok=False, error='response exceeds datagram limit; request a smaller page')), flags=ERROR)
        record['reply'], record['time'] = data, time.monotonic()
        self.send(key[0], data)

    def _hello(self, address, body):
        subscribe = body.get('subscribe', ['ipv4'])
        if not isinstance(subscribe, list) or len(subscribe) > 3 or any(s not in ('ethernet', 'ipv4', 'udp') for s in subscribe):
            raise ValueError('subscribe must be a list of ethernet, ipv4 and/or udp')
        ports = body.get('ports', [])
        if not isinstance(ports, list) or len(ports) > 64 or any(type(port) is not int or not 1 <= port <= 65535 for port in ports):
            raise ValueError('ports must be a list of at most 64 UDP destination ports')
        peers = body.get('peers', [])
        if not isinstance(peers, list) or len(peers) > 64 or any(type(rid) is not int or not 0 < rid < 1 << 64 for rid in peers):
            raise ValueError('peers must be a list of at most 64 RIDs')
        self.clients[address] = dict(time=time.monotonic(), subscribe=set(subscribe), ports=set(ports), peers=set(peers))
        return dict(ok=True, protocol='ORAD', version=1, endpoint=list(self.endpoint),
            header_bytes=HEADER.size, ttl_seconds=CLIENT_TTL, **self.engine.snapshot())

    def request(self, data, address):
        try:
            opcode, flags, request, peer, payload = decode_message(data, self.token)
            if address[0] != '127.0.0.1' or flags or not request:
                raise ValueError('invalid request flags, address or request ID')
        except (ValueError, struct.error):
            self.engine.count('api_invalid')
            return
        fingerprint = hashlib.sha256(data).digest()
        key, now = (address, request), time.monotonic()
        if key in self.cache:
            old = self.cache[key]
            if old['fingerprint'] != fingerprint:
                self.send(address, encode_message(opcode | RESPONSE, request, self.token,
                    json_bytes(dict(ok=False, error='request ID reused with different contents')), flags=ERROR))
            elif old['reply']:
                self.send(address, old['reply'])
            if address in self.clients:
                self.clients[address]['time'] = now
            return
        if address not in self.clients and (opcode != HELLO or len(self.clients) >= 32):
            self.send(address, encode_message(opcode | RESPONSE, request, self.token,
                json_bytes(dict(ok=False, error='send HELLO first; client capacity is 32')), flags=ERROR))
            return
        if len(self.cache) >= 4096:
            self.send(address, encode_message(opcode | RESPONSE, request, self.token,
                json_bytes(dict(ok=False, error='request cache is full; wait before retrying')), flags=ERROR))
            return
        self.cache[key] = dict(fingerprint=fingerprint, opcode=opcode, reply=None, time=now)
        if address in self.clients:
            self.clients[address]['time'] = now
        self.engine.count('api_requests')
        try:
            if opcode == HELLO:
                result = self._hello(address, json_object(payload))
            elif opcode == STATUS:
                if payload:
                    raise ValueError('STATUS takes no payload')
                result = dict(ok=True, **self.engine.snapshot())
            elif opcode == PEERS:
                body = json_object(payload)
                cursor, limit = body.get('cursor', 0), body.get('limit', 64)
                if type(cursor) is not int or cursor < 0 or type(limit) is not int or not 1 <= limit <= 64:
                    raise ValueError('invalid peer page; limit must be 1–64')
                result = dict(ok=True, **self.engine.snapshot(peers=True, cursor=cursor, limit=limit))
            elif opcode == SEND_ETHERNET:
                result = self.engine.send_ethernet(payload, peer)
                self.engine.count('api_packet_tx')
            elif opcode == SEND_IPV4:
                result = self.engine.send_ipv4(payload, peer)
                self.engine.count('api_packet_tx')
            elif opcode == SEND_UDP:
                if len(payload) < UDP_SEND.size:
                    raise ValueError('truncated UDP application request')
                target, sport, dport = UDP_SEND.unpack_from(payload)
                ip = p.udp_ipv4(self.engine.identity.ipv4, str(IPv4Address(target)), sport, dport, payload[UDP_SEND.size:])
                result = self.engine.send_ipv4(ip, peer)
                self.engine.count('api_packet_tx')
            elif opcode == COMMAND:
                body = json_object(payload)
                self.engine.command(body, lambda result: self.complete(key, result))
                return
            elif opcode == BYE:
                self.clients.pop(address, None)
                result = dict(ok=True)
            else:
                raise ValueError('unknown UDP API opcode')
            self._reply(key, result)
        except (ValueError, KeyError, TypeError, UnicodeError, struct.error) as exc:
            self._reply(key, dict(ok=False, error=str(exc)))

    def deliver(self, rid, frame):
        # Filter by peer before parsing: Ethernet-only or unsubscribed API
        # consumers must not pay for IPv4 and UDP checksum validation.
        recipients = tuple((address, client) for address, client in self.clients.items()
                           if not client['peers'] or rid in client['peers'])
        if not recipients:
            return
        self.event_sequence = self.event_sequence % 0xffffffff + 1
        sequence, ip, udp = self.event_sequence, None, None
        want_ipv4 = any('ipv4' in client['subscribe'] for _, client in recipients)
        want_udp = any('udp' in client['subscribe'] for _, client in recipients)
        if (want_ipv4 or want_udp) and frame[12:14] == b'\x08\x00':
            try:
                ip = p.ipv4_packet(frame[14:])[0]
                if want_udp:
                    udp = p.decode_udp(ip)
            except ValueError:
                self.engine.count('api_invalid_udp')
        for address, client in recipients:
            if 'ethernet' in client['subscribe']:
                if self.send(address, encode_message(RX_ETHERNET, sequence, self.token, frame, peer=rid)):
                    self.engine.count('api_packet_rx')
            if ip is not None and 'ipv4' in client['subscribe']:
                if self.send(address, encode_message(RX_IPV4, sequence, self.token, ip, peer=rid)):
                    self.engine.count('api_packet_rx')
            if udp is not None and 'udp' in client['subscribe']:
                src, dst, sport, dport, data = udp
                if not client['ports'] or dport in client['ports']:
                    payload = UDP_RECEIVE.pack(IPv4Address(src).packed, IPv4Address(dst).packed, sport, dport) + data
                    if self.send(address, encode_message(RX_UDP, sequence, self.token, payload, peer=rid)):
                        self.engine.count('api_packet_rx')

    def poll(self, timeout=.1):
        ready = poll_readable([self.sock, self.frames.reader, self.replies.reader],
            timeout if self.frames.empty() and self.replies.empty() else 0)
        for messages in (self.frames, self.replies):
            if messages.reader in ready:
                messages.drain_wake()
        if self.sock in ready:
            for _ in range(64):
                try:
                    data, address = self.sock.recvfrom(MAX_DATAGRAM + 1)
                except BlockingIOError:
                    break
                self.request(data, address)
        for _ in range(64):
            try:
                key, result = self.replies.get_nowait()
            except queue.Empty:
                break
            self._reply(key, result)
        for _ in range(64):
            try:
                rid, frame = self.frames.get_nowait()
            except queue.Empty:
                break
            self.deliver(rid, frame)
        now = time.monotonic()
        self.clients = {a: c for a, c in self.clients.items() if now - c['time'] < CLIENT_TTL}
        for key, record in tuple(self.cache.items()):
            lifetime = 90 if record['opcode'] == COMMAND else 10
            if now - record['time'] > lifetime:
                del self.cache[key]

    def close(self):
        self.engine.remove_frame_listener(self.incoming)
        self.sock.close()
        self.frames.close()
        self.replies.close()
        # Only remove this instance's discovery, never a successor's endpoint.
        try:
            value = json.loads(self.path.read_text())
            if value.get('token') == self.token.hex():
                self.path.unlink()
        except (OSError, ValueError):
            pass


class LocalClient:
    """Small synchronous Python SDK for the localhost UDP API.

    Requests retry with the same ID. A successful send confirms bounded queue
    admission; asynchronous RX events carry actual incoming network packets.
    Call status() at least once every 120 seconds during long receive sessions.
    """
    def __init__(self, endpoint, token, timeout=40):
        if endpoint[0] != '127.0.0.1' or not 1024 <= endpoint[1] <= 65535:
            raise ValueError('API endpoint must be localhost on a high UDP port')
        self.token, self.timeout = bytes(token), timeout
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
        self.sock.bind(('127.0.0.1', 0))
        self.sock.connect(endpoint)
        self.sock.setblocking(False)
        self.sequence, self.events, self.last_touch = secrets.randbits(31) + 1, deque(maxlen=512), 0

    @classmethod
    def from_profile(cls, directory, timeout=40):
        path = Path(directory).expanduser() / 'api.json'
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(fd, 'r') as source:
                info = os.fstat(source.fileno())
                if info.st_uid != os.geteuid() or info.st_mode & 0o077 or info.st_size > 4096:
                    raise ValueError('API discovery must be a private file owned by you')
                data = json.load(source)
            if data['protocol'] != 'ORAD' or data['version'] != 1:
                raise ValueError('unsupported API version')
            return cls((data['host'], data['port']), bytes.fromhex(data['token']), timeout)
        except FileNotFoundError:
            raise ValueError('no UDP service in this profile; start openrad.py run first') from None

    def _read(self, timeout):
        if not poll_readable([self.sock], max(0, timeout)):
            raise TimeoutError('localhost UDP receive timed out')
        data = self.sock.recv(MAX_DATAGRAM + 1)
        opcode, flags, request, peer, payload = decode_message(data, self.token)
        return dict(opcode=opcode, flags=flags, request=request, peer=peer, payload=payload)

    def request(self, opcode, payload=b'', peer=0, timeout=None):
        self.sequence = self.sequence % 0xffffffff + 1
        request = self.sequence
        encoded = encode_message(opcode, request, self.token, payload, peer=peer)
        until, next_send = time.monotonic() + (self.timeout if timeout is None else timeout), 0
        while time.monotonic() < until:
            now = time.monotonic()
            if now >= next_send:
                try:
                    self.sock.send(encoded)
                except ConnectionRefusedError:
                    raise ValueError('UDP service is no longer running') from None
                next_send = now + .5
            try:
                message = self._read(min(next_send, until) - time.monotonic())
            except TimeoutError:
                continue
            except ConnectionRefusedError:
                raise ValueError('UDP service is no longer running') from None
            if message['opcode'] == opcode | RESPONSE and message['request'] == request:
                result = json_object(message['payload']) if len(message['payload']) <= 16384 else json.loads(message['payload'])
                self.last_touch = time.monotonic()
                if message['flags'] & ERROR or result.get('ok') is False:
                    raise ValueError(result.get('error', 'UDP API rejected the request'))
                return result
            if message['opcode'] in (RX_ETHERNET, RX_IPV4, RX_UDP):
                self.events.append(message)
        raise TimeoutError('UDP API request timed out; its ID may still have a pending response')

    def hello(self, subscribe=('ipv4',), ports=(), peers=()):
        return self.request(HELLO, json_bytes(dict(subscribe=list(subscribe), ports=list(ports), peers=list(peers))))

    def status(self):
        return self.request(STATUS, timeout=3)

    def peers(self):
        cursor, peers = 0, []
        while cursor is not None:
            value = self.request(PEERS, json_bytes(dict(cursor=cursor, limit=32)), timeout=3)
            peers.extend(value['peers'])
            cursor = value['cursor']
        return peers

    def command(self, op, **fields):
        return self.request(COMMAND, json_bytes(dict(op=op, **fields)))

    def send_ipv4(self, packet, peer=0):
        return self.request(SEND_IPV4, packet, peer, timeout=3)

    def send_ethernet(self, frame, peer=0):
        return self.request(SEND_ETHERNET, frame, peer, timeout=3)

    def send_udp(self, target, source_port, target_port, data, peer=0):
        if type(source_port) is not int or type(target_port) is not int or not 1 <= source_port <= 65535 or not 1 <= target_port <= 65535:
            raise ValueError('UDP ports must be integers in 1–65535')
        payload = UDP_SEND.pack(IPv4Address(target).packed, source_port, target_port) + bytes(data)
        return self.request(SEND_UDP, payload, peer, timeout=3)

    def receive(self, timeout=None):
        timeout = self.timeout if timeout is None else timeout
        until = time.monotonic() + timeout
        while True:
            if self.events:
                return self.events.popleft()
            if time.monotonic() - self.last_touch > 50:
                self.status()  # Refresh subscription while preserving RX events.
                if self.events:
                    return self.events.popleft()
            left = until - time.monotonic()
            if left <= 0:
                raise TimeoutError('localhost UDP receive timed out')
            try:
                message = self._read(min(left, 30))
            except TimeoutError:
                if time.monotonic() >= until:
                    raise
                continue
            if message['opcode'] in (RX_ETHERNET, RX_IPV4, RX_UDP):
                return message

    @staticmethod
    def udp_payload(message):
        if message['opcode'] != RX_UDP or len(message['payload']) < UDP_RECEIVE.size:
            raise ValueError('expected an application UDP event')
        src, dst, sport, dport = UDP_RECEIVE.unpack_from(message['payload'])
        return str(IPv4Address(src)), str(IPv4Address(dst)), sport, dport, message['payload'][UDP_RECEIVE.size:]

    def close(self):
        try:
            self.sequence = self.sequence % 0xffffffff + 1
            self.sock.send(encode_message(BYE, self.sequence, self.token))
        except OSError:
            pass
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def default_profile():
    root = Path(os.environ.get('XDG_STATE_HOME', str(Path.home() / '.local/state')))
    return root / 'openrad-python'


class ChannelClosed(ConnectionError):
    """The packet channel or its OpenRad instance was closed."""


@dataclass(frozen=True, slots=True)
class Packet:
    """A received packet. ``peer`` identifies its authenticated Radmin channel.

    ``data`` is the full frame/IP packet, or only the UDP application payload.
    Ethernet sender fields in an ARP reply may differ from that peer's identity.
    """
    kind: str
    peer: int
    data: bytes
    sequence: int
    source: str | None = None
    destination: str | None = None
    source_port: int | None = None
    destination_port: int | None = None


_CLOSED = object()


class PacketChannel:
    """Bounded async subscription, confined to the owner's asyncio loop.

    Slow consumers drop new packets without blocking other subscriptions or
    peer workers. ``dropped`` counts those losses. Closing wakes all receivers.
    Multiple readers share this queue; use separate channels for fan-out.
    """
    def __init__(self, owner, kind, ports, peers, capacity):
        self.owner, self.kind, self.ports, self.peers = owner, kind, ports, peers
        self.queue = asyncio.Queue(capacity)
        self.closed, self.dropped = False, 0

    def _deliver(self, packet):
        if self.closed or packet.kind != self.kind or self.peers and packet.peer not in self.peers:
            return
        if self.ports and packet.destination_port not in self.ports:
            return
        try:
            self.queue.put_nowait(packet)
        except asyncio.QueueFull:
            self._drop()

    def _drop(self):
        self.dropped += 1
        self.owner._engine.count('import_channel_dropped')

    async def receive(self, timeout=None):
        self.owner._check_loop()
        if self.closed:
            raise ChannelClosed('packet channel is closed')
        if timeout is not None and (not math.isfinite(timeout) or timeout < 0):
            raise ValueError('timeout must be finite and nonnegative')
        if timeout == 0:
            try:
                packet = self.queue.get_nowait()
            except asyncio.QueueEmpty:
                raise TimeoutError('no packet available') from None
        elif timeout is None:
            packet = await self.queue.get()
        else:
            packet = await asyncio.wait_for(self.queue.get(), timeout)
        if packet is _CLOSED:
            self.queue.put_nowait(_CLOSED)
            raise ChannelClosed('packet channel is closed')
        return packet

    def close(self):
        self.owner._check_loop()
        if self.closed:
            return
        self.closed = True
        self.owner._channels.discard(self)
        while not self.queue.empty():
            self.queue.get_nowait()
        self.queue.put_nowait(_CLOSED)

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return await self.receive()
        except ChannelClosed:
            raise StopAsyncIteration from None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        self.close()


class OpenRad:
    """Async Linux client, with packets exchanged directly through memory.

    Construction and import have no I/O side effects. ``await start()`` holds
    the profile lock and starts the control/peer workers; ``await close()``
    joins them. No signal handlers are changed. Each instance belongs to one
    asyncio loop and one session; create a new instance after closing it.

    Default mode creates neither a UDP API socket nor a TAP. ``local_udp=True``
    optionally exposes the same engine through ORAD alongside the memory API.
    The Radmin control and encrypted TCP peer transports remain threaded;
    blocking startup/shutdown work runs outside the application's event loop.
    """
    def __init__(self, data_dir=None, *, name='openrad-python-linux', networks=None,
                 force_relay=None, passive=False, max_peers=32, local_udp=False,
                 udp_port=0, receive_queue=256, bridge_queue=1024,
                 host=p.DEFAULT_HOST, server_port=p.DEFAULT_PORT,
                 identity=None, modulus=p.SERVER_MODULUS):
        if type(max_peers) is not int or max_peers < 1:
            raise ValueError('max_peers must be a positive integer')
        if force_relay is not None and type(force_relay) is not bool:
            raise ValueError('force_relay must be boolean or None')
        if type(local_udp) is not bool or type(passive) is not bool:
            raise ValueError('local_udp and passive must be boolean')
        if type(udp_port) is not int or udp_port and not 1024 <= udp_port <= 65535:
            raise ValueError('udp_port must be 0 or 1024–65535')
        if type(server_port) is not int or not 1 <= server_port <= 65535:
            raise ValueError('invalid server_port')
        for capacity in (receive_queue, bridge_queue):
            if type(capacity) is not int or not 1 <= capacity <= 65536:
                raise ValueError('queue capacities must be 1–65536')
        if networks is not None:
            if isinstance(networks, (str, bytes)):
                raise ValueError('networks must be a collection of names')
            networks = tuple(networks)
            for network in networks:
                p.validate_network_name(network)
        p.build_login(name, 0, purpose=3)
        self.data_dir = Path(data_dir) if data_dir is not None else default_profile()
        self.name, self.networks, self.force_relay = name, networks, force_relay
        self.passive, self.max_peers = passive, max_peers
        self.local_udp, self.udp_port = local_udp, udp_port
        self.host, self.server_port, self.identity_path, self.modulus = host, server_port, identity, bytes(modulus)
        self.receive_queue = receive_queue
        self._loop = self._engine = self._profile = self._server = None
        self._lifecycle = asyncio.Lock()
        self._bootstrap_stop = threading.Event()
        self._channels, self._requests = set(), set()
        self._default_channel = self._monitor = self._close_task = None
        self._default_active, self._default_pending = False, deque()
        self._inbox, self._inbox_lock = queue.Queue(bridge_queue), threading.Lock()
        self._scheduled, self._closed, self._sequence = False, False, 0
        self._udp_readers, self._udp_timer, self._udp_pending = [], None, None

    def _check_loop(self):
        loop = asyncio.get_running_loop()
        if self._loop is None:
            self._loop = loop
        elif loop is not self._loop:
            raise RuntimeError('OpenRad must be used on its original asyncio loop')
        return loop

    def _require_engine(self):
        self._check_loop()
        if self._closed or self._engine is None or self._engine.stop.is_set():
            raise ChannelClosed('OpenRad is not running')
        return self._engine

    def _prepare(self):
        if not sys.platform.startswith('linux'):
            raise ValueError('OpenRad supports Linux only')
        profile = Profile(self.data_dir)
        try:
            profile.acquire()
            identity = ensure_identity(profile, self.name, import_path=self.identity_path,
                host=self.host, port=self.server_port, modulus=self.modulus, stop=self._bootstrap_stop)
            settings, path = {}, profile.path / 'settings.json'
            if path.exists():
                if path.stat().st_size > 65536:
                    raise ValueError('settings exceed limit')
                settings = json.loads(path.read_text())
                if (not isinstance(settings, dict) or type(settings.get('force_relay', False)) is not bool
                        or not isinstance(settings.get('networks', []), list)):
                    raise ValueError('invalid settings; retained for repair')
            names = self.networks if self.networks is not None else settings.get('networks', [])
            for network in names:
                p.validate_network_name(network)
            force = settings.get('force_relay', False) if self.force_relay is None else self.force_relay
            if self._bootstrap_stop.is_set():
                raise InterruptedError('OpenRad startup cancelled')
            private_json(path, dict(force_relay=force, networks=sorted(set(names))))
            if not self.local_udp:
                (profile.path / 'api.json').unlink(missing_ok=True)
            if self._bootstrap_stop.is_set():
                raise InterruptedError('OpenRad startup cancelled')
            return identity, profile, names, force
        except BaseException:
            profile.close()
            raise

    def _prepare_result(self):
        # Return errors as values: a cancelled shield on Python 3.14 logs
        # exceptions from its inner task even if another waiter handles them.
        try:
            return self._prepare(), None
        except BaseException as exc:
            return None, exc

    async def start(self, *, wait=True, timeout=45):
        """Start once; by default wait for all requested memberships.

        Startup failures/timeouts release the profile and close subscriptions.
        ``wait=False`` allows the application to observe connection progress.
        """
        self._check_loop()
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('timeout must be positive and finite')
        try:
            await self._start()
            if wait:
                await self.wait_online(timeout)
            return self
        except BaseException:
            await self.close()
            raise

    async def _start(self):
        self._check_loop()
        async with self._lifecycle:
            if self._closed:
                raise ChannelClosed('create a new OpenRad instance after closing')
            if self._engine is None:
                prepare = asyncio.create_task(asyncio.to_thread(self._prepare_result))
                try:
                    try:
                        prepared, error = await asyncio.shield(prepare)
                    except asyncio.CancelledError:
                        self._bootstrap_stop.set()
                        while not prepare.done():
                            try:
                                await asyncio.shield(prepare)
                            except asyncio.CancelledError:
                                continue
                        prepared, _ = prepare.result()
                        if prepared:
                            prepared[1].close()
                        raise
                    if error is not None:
                        raise error
                    identity, profile, names, force = prepared
                    self._profile = profile
                    self._engine = Engine(identity, profile, networks=names, force_relay=force,
                        passive=self.passive, max_peers=self.max_peers, modulus=self.modulus, port=self.server_port)
                    self._engine.mode = 'direct'
                    self._engine.add_frame_listener(self._incoming)
                    if self._default_channel is None:
                        self._default_channel = self.subscribe('ethernet')
                    if self.local_udp:
                        self._server = UDPServer(self._engine, profile, self.udp_port)
                        self._engine.mode = 'direct+udp'
                        for sock in (self._server.sock, self._server.frames.reader, self._server.replies.reader):
                            fd = sock.fileno()
                            self._loop.add_reader(fd, self._poll_udp)
                            self._udp_readers.append(fd)
                        self._udp_timer = self._loop.call_later(10, self._prune_udp)
                    self._engine.thread.start()
                    self._monitor = asyncio.create_task(self._watch_engine())

                except BaseException:
                    raise  # start() closes resources after releasing this lock.

    async def wait_online(self, timeout=45):
        engine = self._require_engine()
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('timeout must be positive and finite')
        async def ready():
            while True:
                self._require_engine()
                with engine.lock:
                    joined = {n['name'] for n in engine.membership.networks.values()}
                    if engine.state == 'online' and engine.selected <= joined:
                        return engine.snapshot()
                await asyncio.sleep(.05)
        return await asyncio.wait_for(ready(), timeout)

    def status(self):
        self._check_loop()
        if self._engine is None:
            return dict(state='stopped' if self._closed else 'not_started', mode='direct', uid=os.geteuid())
        result = self._engine.snapshot()
        result['local_udp'] = self._server.endpoint if self._server is not None else None
        result['import_channels'] = len(self._channels)
        return result

    def peers(self):
        engine = self._require_engine()
        with engine.lock:
            return engine.snapshot(peers=True, limit=len(engine.membership.peers))['peers']

    async def wait_peer(self, target, timeout=45):
        self._check_loop()
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('timeout must be positive and finite')
        deadline, requested = self._loop.time() + timeout, False
        while self._loop.time() < deadline:
            matches = [x for x in self.peers() if str(x['rid']) == str(target) or x['vip'] == str(target)]
            if len(matches) > 1:
                raise ValueError('target must identify exactly one peer')
            if matches:
                peer = matches[0]
                if peer['connection'] == 'connected' and peer['eligible']:
                    return peer
                if peer['eligible'] and not requested:
                    await self.command('connect', rid=peer['rid'], timeout=max(.01, deadline - self._loop.time()))
                    requested = True
            await asyncio.sleep(.05)
        raise TimeoutError('peer did not establish an authenticated channel')

    async def command(self, op, *, timeout=40, **fields):
        engine = self._require_engine()
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('timeout must be positive and finite')
        future = self._loop.create_future()
        self._requests.add(future)
        def complete(result):
            def deliver():
                if not future.done():
                    if result.get('ok'):
                        future.set_result(result)
                    else:
                        future.set_exception(ValueError(result.get('error', 'command failed')))
            try:
                self._loop.call_soon_threadsafe(deliver)
            except RuntimeError:
                pass  # The embedding application already closed its loop.
        try:
            engine.command(dict(fields, op=op), complete)
            return await asyncio.wait_for(future, timeout)
        finally:
            self._requests.discard(future)

    async def join(self, name, password=None, **options):
        return await self.command('join', name=name, password=password, **options)

    async def select_networks(self, names, **options):
        """Select packet-routing memberships without rejoining existing networks."""
        if isinstance(names, (str, bytes)):
            raise ValueError('networks must be a collection of names')
        return await self.command('select_networks', names=list(names), **options)

    async def create(self, name, password, **options):
        return await self.command('create', name=name, password=password, **options)

    async def leave(self, name, **options):
        return await self.command('leave', name=name, **options)

    async def delete(self, name, **options):
        return await self.command('delete', name=name, **options)

    async def connect(self, rid, **options):
        return await self.command('connect', rid=rid, **options)

    async def search(self, query='', cursor=0, **options):
        return await self.command('search', query=query, cursor=cursor, **options)

    async def set_force_relay(self, enabled, **options):
        return await self.command('force_relay', enabled=enabled, **options)

    @staticmethod
    def _peer_id(peer):
        if type(peer) is not int or not 0 <= peer < 1 << 64:
            raise ValueError('peer must be a RID or 0 for destination routing')
        return peer

    async def send_ethernet(self, frame, *, peer=0):
        return self._require_engine().send_ethernet(bytes(frame), self._peer_id(peer))

    async def send_ipv4(self, packet, *, peer=0):
        return self._require_engine().send_ipv4(bytes(packet), self._peer_id(peer))

    async def send_udp(self, destination, source_port, destination_port, data, *, peer=0):
        engine = self._require_engine()
        for port in (source_port, destination_port):
            if type(port) is not int or not 1 <= port <= 65535:
                raise ValueError('UDP ports must be 1–65535')
        packet = p.udp_ipv4(engine.identity.ipv4, str(IPv4Address(destination)),
            source_port, destination_port, bytes(data))
        return engine.send_ipv4(packet, self._peer_id(peer))

    def subscribe(self, kind='ethernet', *, ports=(), peers=(), capacity=None):
        self._check_loop()
        if self._closed:
            raise ChannelClosed('OpenRad is closed')
        if kind not in ('ethernet', 'ipv4', 'udp'):
            raise ValueError('kind must be ethernet, ipv4 or udp')
        ports, peers = frozenset(ports), frozenset(peers)
        if ports and kind != 'udp':
            raise ValueError('port filters require a udp subscription')
        if any(type(x) is not int or not 1 <= x <= 65535 for x in ports):
            raise ValueError('invalid UDP port filter')
        for rid in peers:
            if not self._peer_id(rid):
                raise ValueError('peer filters require nonzero RIDs')
        capacity = self.receive_queue if capacity is None else capacity
        if type(capacity) is not int or not 1 <= capacity <= 65536:
            raise ValueError('capacity must be 1–65536')
        if len(self._channels) >= 64:
            raise ValueError('at most 64 simultaneous subscriptions')
        channel = PacketChannel(self, kind, ports, peers, capacity)
        with self._inbox_lock:
            self._channels.add(channel)
        self._flush_default_pending()
        return channel

    def _flush_default_pending(self):
        # Called on the loop before a receiver/subscription can await. The
        # implicit raw receiver keeps its old bounded backlog without waking
        # asyncio or constructing Packet objects until somebody uses it.
        with self._inbox_lock:
            pending = tuple(self._default_pending)
            self._default_pending.clear()
        for rid, frame, sequence in pending:
            self._default_channel._deliver(Packet('ethernet', rid, frame, sequence))

    async def receive(self, timeout=None):
        self._require_engine()
        with self._inbox_lock:
            self._default_active = True
        self._flush_default_pending()
        return await self._default_channel.receive(timeout)

    def _incoming(self, rid, frame):
        # One loop wakeup per batch, rather than one task/future per packet.
        with self._inbox_lock:
            if self._closed:
                return
            if not self._channels:
                return
            if (not self._default_active and self._default_channel is not None
                    and self._channels == {self._default_channel} and not self._scheduled):
                self._sequence += 1
                if len(self._default_pending) + self._default_channel.queue.qsize() < self.receive_queue:
                    self._default_pending.append((rid, bytes(frame), self._sequence))
                else:
                    self._default_channel._drop()
                return
            sequence = self._sequence + 1
            try:
                self._inbox.put_nowait((rid, bytes(frame), sequence))
            except queue.Full:
                self._engine.count('import_bridge_dropped')
                return
            self._sequence = sequence
            if not self._scheduled:
                self._scheduled = True
                try:
                    self._loop.call_soon_threadsafe(self._drain_frames)
                except RuntimeError:
                    self._scheduled = False

    def _drain_frames(self):
        for _ in range(64):
            with self._inbox_lock:
                if self._closed or self._inbox.empty():
                    self._scheduled = False
                    return
                rid, frame, sequence = self._inbox.get_nowait()
            channels = tuple(c for c in self._channels if not c.closed and (not c.peers or rid in c.peers))
            kinds = {c.kind for c in channels}
            packets = [('ethernet', frame, ())] if 'ethernet' in kinds else []
            if frame[12:14] == b'\x08\x00' and kinds & {'ipv4', 'udp'}:
                ip, src, dst, _ = p.ipv4_packet(frame[14:])
                if 'ipv4' in kinds:
                    packets.append(('ipv4', ip, (src, dst)))
                if 'udp' in kinds:
                    try:
                        udp = p.decode_udp(ip)
                        if udp is not None:
                            src, dst, sport, dport, data = udp
                            packets.append(('udp', data, (src, dst, sport, dport)))
                    except ValueError:
                        pass
            for kind, data, metadata in packets:
                port = metadata[3] if kind == 'udp' else None
                ready = []
                for channel in channels:
                    if channel.kind != kind or channel.ports and port not in channel.ports:
                        continue
                    if channel.queue.full():
                        channel._drop()
                    else:
                        ready.append(channel)
                if ready:
                    packet = Packet(kind, rid, data, sequence, *metadata)
                    for channel in ready:
                        channel._deliver(packet)
        with self._inbox_lock:
            if self._inbox.empty() or self._closed:
                self._scheduled = False
            else:
                self._loop.call_soon(self._drain_frames)

    def _poll_udp(self):
        if self._udp_pending is not None:
            self._udp_pending.cancel()
            self._udp_pending = None
        if not self._closed and self._server is not None:
            self._server.poll(0)
            if not self._server.frames.empty() or not self._server.replies.empty():
                # poll() drains the wake socket but processes at most 64 items.
                # Keep draining queued batches without waiting for new traffic.
                self._udp_pending = self._loop.call_soon(self._poll_udp)

    def _prune_udp(self):
        self._poll_udp()
        if not self._closed:
            self._udp_timer = self._loop.call_later(10, self._prune_udp)

    async def _watch_engine(self):
        while not self._closed:
            if self._engine.stop.is_set():
                self._loop.create_task(self.close())
                return
            await asyncio.sleep(.1)

    async def close(self):
        self._check_loop()
        self._bootstrap_stop.set()
        if self._close_task is None:
            self._close_task = asyncio.create_task(self._close())
        await asyncio.shield(self._close_task)

    async def _close(self):
        async with self._lifecycle:
            self._closed = True
            for channel in tuple(self._channels):
                channel.close()
            for future in tuple(self._requests):
                if not future.done():
                    future.set_exception(ChannelClosed('OpenRad was closed during command'))
            for fd in self._udp_readers:
                self._loop.remove_reader(fd)
            self._udp_readers.clear()
            if self._udp_timer:
                self._udp_timer.cancel()
            if self._udp_pending:
                self._udp_pending.cancel()
            if self._monitor:
                self._monitor.cancel()
            if self._engine is not None:
                self._engine.remove_frame_listener(self._incoming)
                await asyncio.to_thread(self._engine.close)
            if self._server is not None:
                self._server.close()
                self._server = None
            with self._inbox_lock:
                self._default_pending.clear()
                while not self._inbox.empty():
                    self._inbox.get_nowait()
            if self._profile is not None:
                self._profile.close()
                self._profile = None

    async def __aenter__(self):
        return await self.start()

    async def __aexit__(self, *args):
        await self.close()


def print_json(value):
    print(json.dumps(value, ensure_ascii=False, indent=2), flush=True)


def read_password(args):
    if args.password_file:
        path = Path(args.password_file)
        if path.stat().st_size > 2048:
            raise ValueError('password file exceeds limit')
        password = path.read_text().rstrip('\r\n')
    else:
        password = getpass.getpass('Senha da rede: ')
    p.password_bytes(password)
    return password


def wait_peer(client, target, timeout=35):
    until, requested = time.monotonic() + timeout, False
    while time.monotonic() < until:
        matches = [peer for peer in client.peers() if str(peer['rid']) == str(target) or peer['vip'] == str(target)]
        if len(matches) > 1:
            raise ValueError('target must identify exactly one peer in the authenticated memberships')
        if not matches:
            time.sleep(.3)
            continue  # Peer metadata can follow the initial VIP assignment.
        peer = matches[0]
        if peer['connection'] == 'connected':
            return peer
        if not requested:
            client.command('connect', rid=peer['rid'])
            requested = True
        time.sleep(.3)
    raise TimeoutError('peer did not establish an authenticated channel')


def ping(client, target, count=1, timeout=5):
    peer = wait_peer(client, target)
    own = client.hello(subscribe=('ipv4',), peers=(peer['rid'],))
    identifier, results = secrets.randbelow(65536), []
    for sequence in range(1, count + 1):
        payload = b'openrad-python:' + secrets.token_bytes(20)
        echo = struct.pack('!BBHHH', 8, 0, 0, identifier, sequence) + payload
        echo = echo[:2] + struct.pack('!H', p.checksum(echo)) + echo[4:]
        ip = p.ipv4_frame(bytes(6), bytes(6), own['vip'], peer['vip'], echo)[14:]
        started = time.monotonic()
        client.send_ipv4(ip, peer=peer['rid'])
        verified, until = False, started + timeout
        while time.monotonic() < until:
            try:
                message = client.receive(until - time.monotonic())
            except TimeoutError:
                break
            if message['opcode'] != RX_IPV4 or message['peer'] != peer['rid']:
                continue
            data, src, dst, header = p.ipv4_packet(message['payload'])
            reply = data[header:]
            if src == peer['vip'] and dst == own['vip'] and data[9] == 1 and reply[:2] == b'\0\0' and reply[4:8] == struct.pack('!HH', identifier, sequence) and reply[8:] == payload and not p.checksum(reply):
                verified = True
                break
        results.append(dict(sequence=sequence, received=verified,
            rtt_ms=round((time.monotonic() - started) * 1000, 2) if verified else None))
    return dict(ok=all(r['received'] for r in results), peer=peer['rid'], target=peer['vip'],
        transport=peer['transport'], path='localhost UDP → encrypted peer channel → localhost UDP', results=results)


def run_service(args):
    profile, engine, server = Profile(args.data_dir), None, None
    profile.acquire()
    cancelled = threading.Event()
    previous = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        previous[sig] = signal.signal(sig, lambda *_: cancelled.set())
    try:
        modulus = Path(args.modulus).read_bytes() if args.modulus else p.SERVER_MODULUS
        identity = ensure_identity(profile, args.name, import_path=args.identity, host=args.host,
            port=args.server_port, modulus=modulus, stop=cancelled)
        settings_path = profile.path / 'settings.json'
        settings = {}
        if settings_path.exists():
            if settings_path.stat().st_size > 65536:
                raise ValueError('settings exceed limit')
            settings = json.loads(settings_path.read_text())
            if not isinstance(settings, dict) or type(settings.get('force_relay', False)) is not bool or not isinstance(settings.get('networks', []), list):
                raise ValueError('invalid settings; retained for repair')
        names = args.network or settings.get('networks', [])
        for name in names:
            p.validate_network_name(name)
        force = settings.get('force_relay', False) if args.force_relay is None else args.force_relay
        engine = Engine(identity, profile, networks=names, force_relay=force, passive=args.passive,
            max_peers=args.max_peers, modulus=modulus, port=args.server_port)
        mode = 'server' if args.no_tun else args.mode
        if mode == 'tap' or mode == 'auto' and has_net_admin():
            try:
                engine.tap = Tap(engine, args.interface)
                engine.mode = 'tap'
                engine.tap.thread.start()
            except (OSError, subprocess.SubprocessError) as exc:
                if mode == 'tap':
                    raise ValueError('TAP creation failed; use --mode server for unprivileged operation') from exc
                engine.report('tap_unavailable', error=type(exc).__name__)
        engine.save_settings()
        server = UDPServer(engine, profile, args.port)
        print_json(dict(event='udp_listening', host=server.endpoint[0], port=server.endpoint[1],
            uid=os.geteuid(), mode=engine.mode, api_file=str(server.path), vip=identity.ipv4,
            rid=identity.rid, force_relay=force))
        engine.thread.start()
        until = time.monotonic() + args.duration if args.duration else float('inf')
        event_index = None
        while not cancelled.is_set() and not engine.stop.is_set() and time.monotonic() < until:
            server.poll()
            latest = engine.snapshot()['recent_events']
            if latest and latest[-1]['time'] != event_index:
                event_index = latest[-1]['time']
                print(json.dumps(latest[-1], ensure_ascii=False), file=sys.stderr, flush=True)
        server.poll(0)
        print_json(dict(event='shutdown', **engine.snapshot()))
        return 0
    finally:
        cancelled.set()
        if engine:
            engine.close()
        if server:
            server.close()
        profile.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def self_test():
    """Portable unprivileged regressions, embedded to keep exactly three modules."""
    import tempfile
    import unittest
    from unittest.mock import patch
    from dataclasses import replace
    from types import SimpleNamespace
    from radvpn import PeerWorker, save_identity, valid_frame

    fixture = next((x for x in (Path(__file__).parent / 'fixtures',
        Path(__file__).parent / 'share/openrad-python/fixtures',
        Path(sys.prefix) / 'share/openrad-python/fixtures') if (x / 'reference.json').is_file()), None)
    if fixture is None:
        raise ValueError('self-test fixtures missing; reinstall the distribution or run from its source directory')

    class ProtocolTests(unittest.TestCase):
        def test_reference_crypto_and_rekey(self):
            f = json.loads((fixture / 'reference.json').read_text())
            s = f['sh']
            messages = [bytes.fromhex(m) for m in s['messages']]
            client = p.SHClient(s['rid'], s['password'].encode(), private=s['private'])
            self.assertEqual(client.start(), messages[0])
            self.assertEqual(client.receive_f10(messages[1]), messages[2])
            self.assertEqual(client.receive_f12(messages[3]), messages[4])
            self.assertEqual(client.receive_f14(messages[5]).hex(), s['key'])
            enc, dec = p.PureCC(bytes(range(32))), p.PureCC(bytes(range(32)))
            for entry in f['channel']:
                self.assertEqual(enc.enc(bytes.fromhex(entry['pt'])).hex(), entry['ct'])
                self.assertEqual(dec.dec(bytes.fromhex(entry['ct'])).hex(), entry['pt'])
            enc.set_key(bytes(range(32, 64)))
            dec.set_key(bytes(range(32, 64)))
            value = f['rekey']
            self.assertEqual(enc.enc(bytes.fromhex(value['pt'])).hex(), value['ct'])
            self.assertEqual(dec.dec(bytes.fromhex(value['ct'])).hex(), value['pt'])

        def test_stdlib_aes_and_tamper(self):
            key, plain = bytes(range(32)), bytes.fromhex('00112233445566778899aabbccddeeff')
            ct = p._python_enc_block(key, plain)
            self.assertEqual(ct.hex(), '8ea2b7ca516745bfeafc49904b496089')
            self.assertEqual(p._python_dec_block(key, ct), plain)
            data = bytearray(p.PureCC(key).enc(b'authenticated packet'))
            data[4] ^= 1
            with self.assertRaises(ValueError):
                p.PureCC(key).dec(data)

        def test_bulk_cbc_matches_stdlib_across_padding_and_rekeys(self):
            key = bytes(range(32))
            native, pure = p.PureCC(key), p.PureCC(key)
            native_dec, pure_dec = p.PureCC(key), p.PureCC(key)
            for size in (*range(1, 33), 60, 500, 1500):
                if size == 16:
                    for cc in (native, pure, native_dec, pure_dec):
                        cc.set_key(bytes(range(32, 64)))
                plain = bytes(i % 251 for i in range(size))
                ciphertext = native.enc(plain)
                with patch.object(p, '_AES', None):
                    self.assertEqual(pure.enc(plain), ciphertext)
                    self.assertEqual(pure_dec.dec(ciphertext), plain)
                self.assertEqual(native_dec.dec(ciphertext), plain)
                self.assertEqual(native.iv_e, pure.iv_e)
                self.assertEqual(native_dec.iv_d, pure_dec.iv_d)
            damaged = bytes(32)
            for cc in (native_dec, pure_dec):
                with self.assertRaises(ValueError):
                    cc.dec(damaged)
                self.assertEqual(cc.iv_d, damaged[-16:])

        def test_native_auth_and_sum_match_portable_implementations(self):
            for size in range(129):
                data = bytes((i * 173) % 256 for i in range(size))
                expected = sum(int.from_bytes(data[i:i + 8], 'little')
                               for i in range(0, size, 8)) & 0xffffffffffffffff
                for region in (data, bytearray(data), memoryview(data), memoryview(data)[::2]):
                    with patch.object(p, '_MD4', None):
                        portable = p.auth8(region)
                    self.assertEqual(p.auth8(region), portable)
                self.assertEqual(p.sum64_le(data), expected)
                with patch.object(p.sys, 'byteorder', 'big'):
                    self.assertEqual(p.sum64_le(data), expected)

        def test_cbc_backend_switches_preserve_chain_and_direct_iv_edits(self):
            aes = p._AES
            key = bytes(range(32))
            writer, reader, reference = p.PureCC(key), p.PureCC(key), p.PureCC(key)
            for index in range(12):
                if index == 6:
                    for cc in (writer, reader, reference):
                        cc.set_key(bytes(range(32, 64)))
                if index == 9:
                    writer.iv_e = reader.iv_d = reference.iv_e = bytes([99]) * 16
                plain = bytes([index]) * 49
                with patch.object(p, '_AES', None):
                    expected = reference.enc(plain)
                with patch.object(p, '_AES', aes if index % 3 else None):
                    self.assertEqual(writer.enc(plain), expected)
                with patch.object(p, '_AES', aes if index % 2 else None):
                    self.assertEqual(reader.dec(expected), plain)

        def test_peer_limit_accepts_large_loads_and_rejects_invalid_values(self):
            self.assertEqual(OpenRad(max_peers=400).max_peers, 400)
            self.assertEqual(OpenRad(max_peers=4096).max_peers, 4096)
            for value in (0, -1, True, 1.5, '400'):
                with self.assertRaises(ValueError):
                    OpenRad(max_peers=value)

        def test_framing_supports_descriptors_above_select_limit(self):
            import fcntl
            import resource
            if resource.getrlimit(resource.RLIMIT_NOFILE)[0] <= 1200:
                self.skipTest('open-file soft limit cannot allocate descriptor 1200')
            a, b = socket.socketpair()
            fd = fcntl.fcntl(a.fileno(), fcntl.F_DUPFD_CLOEXEC, 1200)
            a.close()
            high = socket.socket(fileno=fd)
            reader, writer = Framed(high), Framed(b)
            try:
                writer.send(b'high-file-descriptor')
                self.assertTrue(reader.ready(.1))
                self.assertEqual(reader.receive(until=time.monotonic() + .2), b'high-file-descriptor')
                reader.send(b'reply')
                self.assertEqual(writer.receive(until=time.monotonic() + .2), b'reply')
            finally:
                reader.close()
                writer.close()

        def test_framing_batches_records_and_preserves_buffered_successors(self):
            a, b = socket.socketpair()
            reader, writer = Framed(a), Framed(b)
            payloads = (b'first', bytes(range(100)), b'last')
            try:
                writer.write(b''.join(struct.pack('>I', len(body)) + body for body in payloads))
                self.assertEqual(reader.receive(), payloads[0])
                self.assertTrue(reader.ready(0))
                with patch.object(reader, '_fill', side_effect=AssertionError('buffered record reread')):
                    self.assertEqual(reader.receive(), payloads[1])
                    self.assertEqual(reader.receive(), payloads[2])
                self.assertEqual(reader.buffer, bytearray())
            finally:
                reader.close()
                writer.close()

        def test_single_peer_permission_matches_full_scan_after_live_edits(self):
            members = p.Membership(1)
            members.networks = {'a': dict(name='one'), 'b': dict(name='two')}
            members.peers = {rid: dict(rid=rid, server='127.0.0.1', state=state,
                network_ids=['a', 'b']) for rid, state in ((1, 1), (2, 1), (3, 5), (4, 0))}
            for names in ((), {'one'}, {'two'}, {'missing'}):
                for roles in ({}, {'a': {1: 0}}, {'a': {2: 0}, 'b': {3: 0}}):
                    members.roles = roles
                    for rid in range(1, 6):
                        self.assertEqual(members.permits(rid, names), rid in members.eligible(names))
            members.peers[2]['state'] = 0
            self.assertFalse(members.permits(2))
            members.peers[2]['state'] = 5
            members.roles = {}
            members.remove_member('a', 2)
            self.assertTrue(members.permits(2, {'two'}))
            members.remove_network('b')
            self.assertFalse(members.permits(2))

        def test_scheduler_counts_capacity_once_when_many_peers_wait(self):
            with tempfile.TemporaryDirectory() as folder:
                ident = p.Identity(1, '26.1.2.3', 'fixture', b'a' * 16, b'fixture-password', '127.0.0.1')
                engine = Engine(ident, Profile(folder), max_peers=400)
                engine.membership.networks['a'] = dict(name='fixture')
                for rid in range(2, 202):
                    engine.membership.peers[rid] = dict(rid=rid, server='127.0.0.1', state=1, network_ids=['a'])
                calls = []
                def alive():
                    calls.append(1)
                    return True
                def make_worker(engine, peer, incoming):
                    return SimpleNamespace(peer=peer, state='connecting', offers=queue.Queue(16), retry_at=0,
                        stop=threading.Event(), thread=SimpleNamespace(start=lambda: None, is_alive=alive))
                with patch('radvpn.PeerWorker', side_effect=make_worker):
                    engine.schedule()
                    self.assertEqual(len(engine.workers), engine._max_connecting)
                    calls.clear()
                    engine.schedule()
                    self.assertLessEqual(len(calls), engine._max_connecting * 3)
                    self.assertEqual(len(engine.workers), engine._max_connecting)
                    self.assertIsNone(engine._schedule_capacity)

        def test_private_network_and_password_proof(self):
            f = json.loads((fixture / 'network-verifier.json').read_text())
            self.assertEqual(p.network_identity(f['name']), f['identity'])
            self.assertEqual(p.network_verifier(f['name'], f['password'], bytes.fromhex(f['salt'])).hex(), f['verifier'])
            op = p.NetworkOperation('join', 9, 7, name='Test private', password='private-password')
            server = p.SHServer(p.network_identity('Test private'), p.password_bytes('private-password'))
            fields = p.parse_records(p.unique(p.parse_records(op.packet), 0x131c).value)
            hello = p.unique(fields, 0x0a00030e).value
            def reply(blob, rid=9):
                return p.u32(p.SERVER_OPERATION, 40) + p.tlv(0x131c,
                    p.u64(0x02000303, 0) + p.u64(0x02000340, rid) + p.u32(0x010003be, 7) + p.tlv(0x0a00030e, blob))
            membership = p.Membership(5)
            f10 = server.hello(hello)
            self.assertEqual(op.handle(reply(f10, rid=99), membership), (None, None))
            sent, _ = op.handle(reply(f10), membership)
            blob = p.unique(p.parse_records(p.unique(p.parse_records(sent), 0x131c).value), 0x0a00030e).value
            sent, _ = op.handle(reply(server.public(blob)), membership)
            blob = p.unique(p.parse_records(p.unique(p.parse_records(sent), 0x131c).value), 0x0a00030e).value
            confirm, _ = server.proof(blob)
            self.assertEqual(op.handle(reply(confirm), membership), (None, None))
            self.assertEqual(op.sh.state, 7)
            wrong = p.SHClient(5, b'wrong-password', private=3333)
            responder = p.SHServer(5, b'right-password', private=2222)
            proof = wrong.receive_f12(responder.public(wrong.receive_f10(responder.hello(wrong.start()))))
            with self.assertRaises(ValueError):
                responder.proof(proof)

        def test_envelopes_are_validated_before_any_delivery(self):
            frame = p.arp_frame(p.mac_for_ip('26.1.2.3'), '26.1.2.3', bytes(6), '26.1.2.4')
            record = p.encode_frame(frame)
            two = record + record[2:]
            self.assertEqual(len(p.decode_packet(two)['frames']), 2)
            bad = bytearray(two)
            bad[len(record)] = 1
            with self.assertRaises(ValueError):
                p.decode_packet(bad)
            with self.assertRaises(ValueError):
                p.decode_packet(two[:-1])

        def test_partial_tcp_record_survives_timeout(self):
            a, b = socket.socketpair()
            framed = Framed(a)
            try:
                b.sendall(struct.pack('>I', 6) + b'ab')
                with self.assertRaises(TimeoutError):
                    framed.receive(until=time.monotonic() + .02)
                b.sendall(b'cdef')
                self.assertEqual(framed.receive(), b'abcdef')
                b.sendall(struct.pack('>I', 0xffffffff))
                with self.assertRaises(ValueError):
                    framed.receive()
            finally:
                framed.close()
                b.close()

        def test_checksum_handles_odd_lengths_and_mutable_buffers(self):
            def reference(data):
                total = sum((data[i] << 8) | (data[i + 1] if i + 1 < len(data) else 0)
                            for i in range(0, len(data), 2))
                while total >> 16:
                    total = (total & 0xffff) + (total >> 16)
                return (~total) & 0xffff

            for size in (0, 1, 2, 3, 7, 8, 9, 31, 32, 33, 1472, 1500, 1514, 65535):
                sample = bytearray((n * 37 + 11) & 0xff for n in range(size))
                original = sample[:]
                expected = reference(sample)
                self.assertEqual(p.checksum(sample), expected)
                self.assertEqual(p.checksum(memoryview(sample)), expected)
                self.assertEqual(p.checksum(bytes(sample)), expected)
                self.assertEqual(sample, original)

        def test_packet_builders_reject_oversize_and_invalid_fields(self):
            mac = p.mac_for_ip('26.1.2.3')
            args = (mac, mac, '26.1.2.3', '26.1.2.4')
            with self.assertRaisesRegex(ValueError, '16-bit total length'):
                p.ipv4_frame(*args, b'x' * 65516)
            with self.assertRaisesRegex(ValueError, 'Ethernet addresses'):
                p.ipv4_frame(mac[:5], mac, '26.1.2.3', '26.1.2.4', b'payload')
            for invalid in (mac[:5], mac + b'\0'):
                for source, target in ((invalid, mac), (mac, invalid)):
                    with self.subTest(mac_length=len(invalid), source=source == invalid):
                        with self.assertRaisesRegex(ValueError, 'Ethernet addresses'):
                            p.arp_frame(source, '26.1.2.3', target, '26.1.2.4')
            for protocol in (-1, 256, True, '17'):
                with self.subTest(protocol=protocol), self.assertRaisesRegex(ValueError, 'IPv4 protocol'):
                    p.ipv4_frame(*args, b'payload', protocol=protocol)
            for port in (-1, 0, 65536, True, '5000'):
                with self.subTest(port=port), self.assertRaisesRegex(ValueError, 'UDP ports'):
                    p.udp_ipv4('26.1.2.3', '26.1.2.4', port, 5000, b'payload')

        def test_local_udp_delivery_decodes_only_for_interested_subscribers(self):
            mac = p.mac_for_ip('26.1.2.3')
            packet = p.udp_ipv4('26.1.2.3', '26.1.2.4', 5000, 5001, b'hello')
            frame = mac + mac + b'\x08\x00' + packet
            server = object.__new__(UDPServer)
            server.token = bytes(16)
            server.event_sequence = 0
            counts, sent = [], []
            server.engine = SimpleNamespace(count=counts.append)
            server.send = lambda address, message: (sent.append((address, message)) or True)
            ethernet_only = {'peers': set(), 'subscribe': {'ethernet'}, 'ports': set()}
            udp_only = {'peers': set(), 'subscribe': {'udp'}, 'ports': {5001}}
            ipv4_only = {'peers': set(), 'subscribe': {'ipv4'}, 'ports': set()}
            server.clients = {}
            with patch.object(p, 'ipv4_packet', side_effect=AssertionError('no subscribers')):
                server.deliver(123, frame)
            self.assertEqual((server.event_sequence, sent), (0, []))
            server.clients = {('127.0.0.1', 1111): ethernet_only}
            with patch.object(p, 'ipv4_packet', side_effect=AssertionError('Ethernet only')):
                server.deliver(123, frame)
            self.assertEqual(len(sent), 1)
            self.assertEqual(decode_message(sent[0][1], server.token)[0], RX_ETHERNET)
            sent.clear()
            server.clients = {('127.0.0.1', 1112): ipv4_only}
            with patch.object(p, 'decode_udp', side_effect=AssertionError('IPv4 only')):
                server.deliver(123, frame)
            self.assertEqual(decode_message(sent[0][1], server.token)[0], RX_IPV4)
            sent.clear()
            server.clients = {('127.0.0.1', 1113): udp_only}
            server.deliver(123, frame)
            self.assertEqual(decode_message(sent[0][1], server.token)[0], RX_UDP)
            self.assertEqual(UDP_RECEIVE.unpack_from(decode_message(sent[0][1], server.token)[-1])[3], 5001)
            sent.clear()
            server.clients = {('127.0.0.1', 1113): dict(udp_only, peers={456})}
            with patch.object(p, 'ipv4_packet', side_effect=AssertionError('wrong peer')):
                server.deliver(123, frame)
            self.assertFalse(sent)
            self.assertEqual(counts, ['api_packet_rx'] * 3)

        def test_udp_checksum_source_and_group_routing(self):
            ip = p.udp_ipv4('26.1.2.3', '26.1.2.4', 50001, 50002, b'payload\0\xff')
            self.assertEqual(p.decode_udp(ip)[-1], b'payload\0\xff')
            bad = bytearray(ip)
            bad[-1] ^= 1
            with self.assertRaises(ValueError):
                p.decode_udp(bad)
            frame = p.mac_for_ip('26.1.2.4') + p.mac_for_ip('26.1.2.3') + b'\x08\x00' + ip
            self.assertTrue(valid_frame(frame, '26.1.2.3', p.mac_for_ip('26.1.2.3'), '26.1.2.4', p.mac_for_ip('26.1.2.4')))
            self.assertFalse(valid_frame(frame, '26.1.2.99', p.mac_for_ip('26.1.2.3')))
            self.assertFalse(valid_frame(frame, '26.1.2.3', p.mac_for_ip('26.1.2.3'), '26.1.2.9', p.mac_for_ip('26.1.2.9')))

        def test_membership_removal_with_own_peer_and_actor(self):
            m = p.Membership(10)
            nid = '01' * 16
            m.networks[nid] = dict(name='n', network_id=nid)
            for rid in (10, 20):
                m.peers[rid] = dict(rid=rid, network_ids=[nid])
            event = p.u32(p.SERVER_OPERATION, 41) + p.tlv(0x131f, p.tlv(0x131d,
                p.u64(0x020001e1, 10) + p.u64(0x020001e1, 20) + p.tlv(0x0d000309, bytes.fromhex(nid))))
            m.changes(event)
            self.assertFalse(m.networks)
            self.assertFalse(m.peers)

        def test_profile_lock_and_private_identity(self):
            with tempfile.TemporaryDirectory() as folder:
                a, b = Profile(folder), Profile(folder)
                a.acquire()
                try:
                    with self.assertRaises(ValueError):
                        b.acquire()
                    identity = p.Identity(11, '26.1.2.3', 'fixture', b'a' * 16, b'fixture-password', '127.0.0.1')
                    save_identity(Path(folder) / 'identity.json', identity)
                    self.assertEqual((Path(folder) / 'identity.json').stat().st_mode & 0o777, 0o600)
                    self.assertEqual(p.Identity.load(Path(folder) / 'identity.json'), identity)
                finally:
                    a.close()

        def test_force_relay_waits_for_a_ready_offer_without_consuming_slots(self):
            with tempfile.TemporaryDirectory() as folder:
                identity = p.Identity(11, '26.1.2.3', 'fixture', b'a' * 16, b'fixture-password', '127.0.0.1')
                engine = Engine(identity, Profile(folder), force_relay=True, passive=True)
                nid = '01' * 16
                engine.membership.networks[nid] = dict(name='fixture', network_id=nid)
                engine.membership.peers[12] = dict(rid=12, name='fixture', vip='26.1.2.4',
                    server='127.0.0.1', state=1, network_ids=[nid])
                engine.pending[13] = dict(rid=12, password=b'fixture-password', created=time.monotonic(),
                    records=[p.u32(p.SERVER_OPERATION, 29) + p.u64(0x020001c1, 13)])
                spawned = []
                engine._spawn = lambda *args: spawned.append(args) or True
                engine.schedule()
                self.assertFalse(spawned)
                # A late reply for an unknown CID is harmless.
                engine.control_data(p.u32(p.SERVER_OPERATION, 23) + p.u64(0x020001c1, 99))
                engine.control_data(p.u32(p.SERVER_OPERATION, 23) + p.u64(0x020001c1, 13))
                engine.schedule()
                self.assertEqual(len(spawned), 1)
                self.assertEqual(spawned[0][1], (13, b'fixture-password'))
                self.assertFalse(engine.pending)

        def test_local_udp_through_authenticated_peer_channels(self):
            # Real localhost UDP plus real TCP, SH, CChannel and Ethernet.
            with tempfile.TemporaryDirectory() as folder:
                profiles = [Profile(Path(folder) / str(i)) for i in range(2)]
                identities = [p.Identity(11 + i, '26.1.2.' + str(3 + i), 'fixture', b'a' * 16,
                    b'fixture-password', '127.0.0.1') for i in range(2)]
                engines = [Engine(identities[i], profiles[i], force_relay=True) for i in range(2)]
                listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                listener.bind(('127.0.0.1', 0))
                listener.listen(1)
                sa = socket.create_connection(listener.getsockname())
                sb, _ = listener.accept()
                listener.close()
                peers = [dict(rid=identities[1-i].rid, vip=identities[1-i].ipv4, name='fixture',
                    server='127.0.0.1', state=1, network_ids=['01' * 16]) for i in range(2)]
                channels, errors = {}, []
                def channel_b():
                    try:
                        channels[1] = PeerChannel(Framed(sb), identities[1], peers[1], b'channel-password', incoming=True)
                    except Exception as exc:
                        errors.append(exc)
                setup = threading.Thread(target=channel_b)
                setup.start()
                channels[0] = PeerChannel(Framed(sa), identities[0], peers[0], b'channel-password')
                setup.join(timeout=5)
                self.assertFalse(errors)
                self.assertIn(1, channels)
                stop, workers = threading.Event(), []
                for i, engine in enumerate(engines):
                    engine.state = 'online'
                    engine.membership.networks['01' * 16] = dict(name='fixture', network_id='01' * 16)
                    engine.membership.peers[peers[i]['rid']] = peers[i]
                    worker = PeerWorker(engine, peers[i])
                    worker.channel, worker.state, worker.transport = channels[i], 'connected', 'relay'
                    engine.workers[peers[i]['rid']] = worker
                    workers.append(worker)
                apis = [UDPServer(engines[i], profiles[i]) for i in range(2)]
                def pump(i):
                    channel = channels[i]
                    while not stop.is_set():
                        for _ in range(8):
                            try:
                                channel.send(p.encode_frame(workers[i].tx.get_nowait()))
                            except queue.Empty:
                                break
                        if channel.stream.ready(.01):
                            try:
                                packet = p.decode_packet(channel.receive(time.monotonic() + .05))
                            except TimeoutError:
                                continue
                            if packet['kind'] == 0:
                                for entry in packet['frames']:
                                    engines[i].incoming_frame(workers[i], entry['ethernet'])
                def api_loop():
                    while not stop.is_set():
                        for api in apis:
                            api.poll(.001)
                threads = [threading.Thread(target=pump, args=(i,), daemon=True) for i in range(2)]
                threads.append(threading.Thread(target=api_loop, daemon=True))
                for thread in threads:
                    thread.start()
                clients = [LocalClient.from_profile(profile.path, timeout=3) for profile in profiles]
                try:
                    for client in clients:
                        client.hello(subscribe=('udp', 'ipv4', 'ethernet'), ports=(50002,))
                    sent = clients[0].send_udp(identities[1].ipv4, 50001, 50002, b'udp-only\0\xff')
                    self.assertEqual(sent['queued'], 1)
                    seen = {}
                    for _ in range(3):
                        event = clients[1].receive(2)
                        seen[event['opcode']] = event
                    self.assertEqual(set(seen), {RX_IPV4, RX_ETHERNET, RX_UDP})
                    self.assertEqual(LocalClient.udp_payload(seen[RX_UDP])[-1], b'udp-only\0\xff')
                    self.assertEqual(seen[RX_IPV4]['peer'], identities[0].rid)
                    clients[0].hello(subscribe=('udp',), ports=(50001,))
                    clients[1].send_udp(identities[0].ipv4, 50002, 50001, b'reply\0\xff')
                    self.assertEqual(LocalClient.udp_payload(clients[0].receive(2))[-1], b'reply\0\xff')
                    # UDP retries must enqueue the same packet only once.
                    clients[0].events.clear()
                    request = 0xf1234567
                    wire = encode_message(SEND_UDP, request, clients[0].token,
                        UDP_SEND.pack(IPv4Address(identities[1].ipv4).packed, 50001, 50002) + b'once')
                    before = engines[0].counters['api_packet_tx']
                    clients[0].sock.send(wire)
                    clients[0].sock.send(wire)
                    time.sleep(.08)
                    self.assertEqual(engines[0].counters['api_packet_tx'], before + 1)
                    # Invalid API headers and tokens cannot trigger a send.
                    clients[0].sock.send(wire[:-1])
                    clients[0].sock.send(encode_message(SEND_IPV4, request + 1, bytes(16), b'bad'))
                    time.sleep(.05)
                    self.assertEqual(engines[0].counters['api_packet_tx'], before + 1)
                    self.assertGreaterEqual(engines[0].counters['api_invalid'], 2)
                    self.assertEqual(engines[0].mode, 'server')
                    self.assertIsNone(engines[0].tap)
                finally:
                    for client in clients:
                        client.close()
                    stop.set()
                    for thread in threads:
                        thread.join(timeout=1)
                    for api in apis:
                        api.close()
                    for channel in channels.values():
                        channel.close()

    class AsyncTests(unittest.IsolatedAsyncioTestCase):
        """Offline integration: actual SH-authenticated encrypted TCP channels."""
        async def asyncSetUp(self):
            self.folder = tempfile.TemporaryDirectory()
            self.clients, self.workers, self.errors = [], [], []
            self.loop_errors = []
            asyncio.get_running_loop().set_exception_handler(lambda loop, context: self.loop_errors.append(context))
            self.nid = 'ab' * 16
            def control(engine):
                engine.membership.networks[self.nid] = dict(name='fixture', network_id=self.nid)
                engine.state = 'online'
                while not engine.stop.is_set():
                    try:
                        body, callback = engine.commands.get(timeout=.02)
                    except queue.Empty:
                        continue
                    callback(dict(ok=True, op=body['op']))
                engine.state = 'stopped'
            self.patch = patch.object(Engine, 'run', control)
            self.patch.start()
            for i in range(3):
                self.clients.append(await self.new_client(i))
            for a, b in ((0, 1), (0, 2), (1, 2)):
                await asyncio.to_thread(self.link, self.clients[a], self.clients[b])

        async def new_client(self, index, **options):
            directory = Path(self.folder.name) / str(index)
            profile = Profile(directory)
            identity = p.Identity(100 + index, f'26.1.2.{3 + index}', f'fixture-{index}',
                bytes([index + 1]) * 16, b'fixture-password', '127.0.0.1')
            save_identity(directory / 'identity.json', identity)
            client = OpenRad(directory, networks=['fixture'], force_relay=True, passive=True, **options)
            await client.start(timeout=2)
            return client

        def link(self, a, b):
            identities = [a._engine.identity, b._engine.identity]
            peers = [dict(rid=identities[1-i].rid, vip=identities[1-i].ipv4, name='fixture',
                server='127.0.0.1', state=1, network_ids=[self.nid]) for i in range(2)]
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            sa = socket.create_connection(listener.getsockname())
            sb, _ = listener.accept()
            listener.close()
            channels = {}
            def accept():
                try:
                    channels[1] = PeerChannel(Framed(sb), identities[1], peers[1], b'fixture-channel', incoming=True)
                except Exception as exc:
                    self.errors.append(exc)
            setup = threading.Thread(target=accept)
            setup.start()
            channels[0] = PeerChannel(Framed(sa), identities[0], peers[0], b'fixture-channel')
            setup.join(timeout=5)
            if self.errors or 1 not in channels:
                raise RuntimeError('fixture channel authentication failed')
            for i, client in enumerate((a, b)):
                engine = client._engine
                worker = PeerWorker(engine, peers[i])
                worker.channel, worker.state, worker.transport = channels[i], 'connected', 'relay'
                with engine.lock:
                    engine.membership.peers[peers[i]['rid']] = peers[i]
                    engine.workers[peers[i]['rid']] = worker
                worker.thread = threading.Thread(target=self.pump, args=(engine, worker), daemon=True,
                    name=f'fixture-peer-{engine.identity.rid}-{peers[i]["rid"]}')
                self.workers.append(worker)
                worker.thread.start()

        def pump(self, engine, worker):
            channel = worker.channel
            try:
                while not engine.stop.is_set() and not worker.stop.is_set():
                    ready = poll_readable([worker.tx.reader, channel.stream.sock],
                        0 if len(channel.stream.buffer) >= 4 else .05)
                    if worker.tx.reader in ready:
                        worker.tx.drain_wake()
                    for _ in range(32):
                        try:
                            channel.send(p.encode_frame(worker.tx.get_nowait()))
                        except queue.Empty:
                            break
                    if channel.stream.sock in ready or len(channel.stream.buffer) >= 4:
                        packet = p.decode_packet(channel.receive(time.monotonic() + .2))
                        for item in packet.get('frames', ()):
                            engine.incoming_frame(worker, item['ethernet'])
            except ConnectionError:
                pass  # Closing either endpoint is normal fixture teardown.
            except (OSError, ValueError) as exc:
                if not engine.stop.is_set() and not worker.stop.is_set():
                    self.errors.append(exc)
            finally:
                worker.state = 'closed'
                channel.close()
                worker.tx.close()

        async def asyncTearDown(self):
            # Set all stops before joining either half of a channel.
            for worker in self.workers:
                worker.stop.set()
            for client in self.clients:
                await client.close()
            self.patch.stop()
            self.folder.cleanup()
            self.assertFalse(self.errors)
            self.assertFalse(self.loop_errors)
            self.assertTrue(all(not w.thread.is_alive() for w in self.workers))

        async def test_direct_binary_roundtrip_all_packet_layers(self):
            a, b, _ = self.clients
            udp = b.subscribe('udp', ports=[50002], peers=[100])
            ipv4, ethernet = b.subscribe('ipv4'), b.subscribe('ethernet')
            replies = a.subscribe('udp', ports=[50001])
            peer = await a.wait_peer(101, timeout=1)
            self.assertEqual(peer['transport'], 'relay')
            for size in (0, 1, 511, 1472):
                data = bytes(i & 255 for i in range(size))
                self.assertEqual((await a.send_udp(b.status()['vip'], 50001, 50002, data))['queued'], 1)
                u, ip, eth = await udp.receive(2), await ipv4.receive(2), await ethernet.receive(2)
                self.assertEqual((u.data, u.peer, u.source_port, u.destination_port), (data, 100, 50001, 50002))
                self.assertEqual(u.sequence, ip.sequence)
                self.assertEqual(u.sequence, eth.sequence)
                self.assertEqual(p.decode_udp(ip.data)[-1], data)
                self.assertEqual(eth.data[14:], ip.data)
                await b.send_udp(u.source, u.destination_port, u.source_port, u.data, peer=u.peer)
                self.assertEqual((await replies.receive(2)).data, data)
            self.assertIsNone(a.status()['local_udp'])
            self.assertEqual(a.status()['mode'], 'direct')
            self.assertIsNone(a._engine.tap)
            self.assertFalse((a.data_dir / 'api.json').exists())

        async def test_broadcast_multicast_fanout_and_membership_filters(self):
            a, b, c = self.clients
            channels = [x.subscribe('udp', ports=[50002]) for x in (b, c)]
            for destination in ('255.255.255.255', '26.255.255.255', '224.1.2.3', '239.255.255.250'):
                data = destination.encode()
                self.assertEqual((await a.send_udp(destination, 50001, 50002, data))['queued'], 2)
                for channel in channels:
                    packet = await channel.receive(2)
                    self.assertEqual((packet.data, packet.destination), (data, destination))
            await a.send_udp('224.1.2.3', 50001, 50002, b'only-b', peer=101)
            self.assertEqual((await channels[0].receive(2)).data, b'only-b')
            with self.assertRaises(TimeoutError):
                await channels[1].receive(.03)
            # Shared networks do not duplicate a peer. A pending role is excluded
            # immediately, even if its authenticated channel has not retired yet.
            with a._engine.lock:
                a._engine.membership.networks['cd' * 16] = dict(name='fixture', network_id='cd' * 16)
                a._engine.membership.peers[101]['network_ids'].append('cd' * 16)
                a._engine.membership.roles[self.nid] = {102: 0}
            self.assertEqual((await a.send_udp('26.255.255.255', 50001, 50002, b'eligible'))['queued'], 1)
            self.assertEqual((await channels[0].receive(2)).data, b'eligible')
            with self.assertRaises(TimeoutError):
                await channels[1].receive(.03)
            with a._engine.lock:
                a._engine.selected = {'another-network'}
            with self.assertRaises(ValueError):
                await a.send_udp('224.1.2.3', 50001, 50002, b'excluded')

        async def test_l2_broadcast_requests_and_ipv6_multicast_reach_every_peer(self):
            a, b, c = self.clients
            channels = [x.subscribe('ethernet') for x in (b, c)]
            src = p.mac_for_ip(a.status()['vip'])
            frames = [p.arp_frame(src, a.status()['vip'], bytes(6), b.status()['vip']),
                b'\x33\x33\0\0\0\x01' + src + b'\x86\xdd' + bytes(40),
                b'\xff' * 6 + src + b'\x88\xb5' + b'raw-broadcast']
            for frame in frames:
                self.assertEqual((await a.send_ethernet(frame))['queued'], 2)
                for channel in channels:
                    self.assertEqual((await channel.receive(2)).data, frame)
            # The received broadcast was not reflected into B→C or C→B.
            for channel in channels:
                with self.assertRaises(TimeoutError):
                    await channel.receive(.03)

        async def test_arp_reply_arbitrary_source_survives_both_directions(self):
            a, b, c = self.clients
            channels = [x.subscribe('ethernet') for x in (b, c)]
            foreign_mac, foreign_ip = b'\x02\x99\x88\x77\x66\x55', '10.77.88.99'
            for target_ip, dst_mac, expected in ((b.status()['vip'], p.mac_for_ip(b.status()['vip']), 1),
                    (b.status()['vip'], b'\xff' * 6, 2), (foreign_ip, b'\xff' * 6, 2)):
                frame = p.arp_frame(foreign_mac, foreign_ip, dst_mac, target_ip, reply=True)
                frame = frame[:6] + b'\x06\x11\x22\x33\x44\x55' + frame[12:]
                self.assertEqual((await a.send_ethernet(frame))['queued'], expected)
                self.assertEqual((await channels[0].receive(2)).data, frame)
                if expected == 2:
                    self.assertEqual((await channels[1].receive(2)).data, frame)
                else:
                    with self.assertRaises(TimeoutError):
                        await channels[1].receive(.03)
            with self.assertRaises(ValueError):
                await a.send_ethernet(p.arp_frame(foreign_mac, foreign_ip, bytes(6), b.status()['vip']))
            bad = p.mac_for_ip(b.status()['vip']) + p.mac_for_ip(a.status()['vip']) + b'\x08\x00'
            bad += p.udp_ipv4(foreign_ip, b.status()['vip'], 50001, 50002, b'wrong-source')
            with self.assertRaises(ValueError):
                await a.send_ethernet(bad)
            malformed = b'\xff' * 6 + foreign_mac + b'\x08\x06' + bytes(28)
            with self.assertRaises(ValueError):
                await a.send_ethernet(malformed)

        async def test_cancel_timeout_and_independent_consumers(self):
            a, b, _ = self.clients
            one, two = b.subscribe('udp'), b.subscribe('udp')
            waiting = asyncio.create_task(one.receive())
            await asyncio.sleep(0)
            waiting.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await waiting
            with self.assertRaises(TimeoutError):
                await one.receive(.01)
            await a.send_udp(b.status()['vip'], 50001, 50002, b'after-cancel')
            for channel in (one, two):
                self.assertEqual((await channel.receive(2)).data, b'after-cancel')
            callbacks = []
            with patch.object(a._engine, 'command', lambda body, cb: callbacks.append(cb)):
                request = asyncio.create_task(a.search('x', timeout=.01))
                with self.assertRaises(TimeoutError):
                    await request
                callbacks[0](dict(ok=True, late=True))
                await asyncio.sleep(0)
                request = asyncio.create_task(a.search('y'))
                await asyncio.sleep(0)
                request.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await request
                callbacks[1](dict(ok=False, error='late error'))
                await asyncio.sleep(0)
            self.assertFalse(a._requests)
            one.close()
            with self.assertRaises(StopAsyncIteration):
                await one.__anext__()

        async def test_bounded_slow_consumer_does_not_block_fast_consumer(self):
            a, b, _ = self.clients
            slow, fast = b.subscribe('udp', capacity=1), b.subscribe('udp', capacity=128)
            for i in range(100):
                await a.send_udp(b.status()['vip'], 50001, 50002, struct.pack('!I', i))
            packets = [await fast.receive(2) for _ in range(100)]
            self.assertEqual([struct.unpack('!I', x.data)[0] for x in packets], list(range(100)))
            self.assertEqual(slow.queue.qsize(), 1)
            self.assertEqual(slow.dropped, 99)
            self.assertGreaterEqual(b.status()['counters']['import_channel_dropped'], 99)

        async def test_bridge_backpressure_has_bounded_memory_and_coalesced_wakeup(self):
            a, b, _ = self.clients
            channel = b.subscribe('udp', ports=[60002])
            b._inbox = queue.Queue(4)
            ip = p.udp_ipv4(a.status()['vip'], b.status()['vip'], 60001, 60002, b'bridge')
            frame = p.mac_for_ip(b.status()['vip']) + p.mac_for_ip(a.status()['vip']) + b'\x08\x00' + ip
            def burst():
                for _ in range(20):
                    b._incoming(100, frame)
            producer = threading.Thread(target=burst)
            producer.start()
            producer.join(timeout=1)  # Deliberately hold this loop until the burst is queued.
            self.assertFalse(producer.is_alive())
            self.assertEqual(b._inbox.qsize(), 4)
            self.assertTrue(b._scheduled)
            self.assertEqual(b.status()['counters']['import_bridge_dropped'], 16)
            for _ in range(4):
                self.assertEqual((await channel.receive(1)).data, b'bridge')
            self.assertFalse(b._scheduled)

        async def test_unused_default_receiver_buffers_without_asyncio_delivery(self):
            a, b, _ = self.clients
            ip = p.udp_ipv4(a.status()['vip'], b.status()['vip'], 60001, 60002, b'raw-backlog')
            frame = p.mac_for_ip(b.status()['vip']) + p.mac_for_ip(a.status()['vip']) + b'\x08\x00' + ip
            worker = b._engine.workers[a.status()['rid']]
            with patch.object(b, '_drain_frames', side_effect=AssertionError('unused receiver woke asyncio')):
                for _ in range(3):
                    await asyncio.to_thread(b._engine.incoming_frame, worker, frame)
                self.assertFalse(b._scheduled)
                self.assertTrue(b._inbox.empty())
                self.assertTrue(b._default_channel.queue.empty())
                packets = [await b.receive(0) for _ in range(3)]
                self.assertEqual([x.data for x in packets], [frame] * 3)
                self.assertEqual([x.sequence for x in packets], sorted(x.sequence for x in packets))
            await a.send_udp(b.status()['vip'], 60001, 60002, b'after-activation')
            self.assertTrue((await b.receive(2)).data.endswith(b'after-activation'))

        async def test_ethernet_subscription_skips_unused_udp_decode(self):
            a, b, _ = self.clients
            channel = b.subscribe('ethernet')
            with patch.object(p, 'decode_udp', side_effect=AssertionError('unrequested UDP parsing')):
                await a.send_udp(b.status()['vip'], 60001, 60002, b'raw-only')
                self.assertTrue((await channel.receive(2)).data.endswith(b'raw-only'))

        async def test_unused_receiver_still_answers_icmp_and_udp_subscriber_ignores_it(self):
            a, b, _ = self.clients
            channel = a.subscribe('ipv4')
            b.subscribe('udp')
            echo = b'\x08\0\0\0' + struct.pack('>HH', 123, 1) + b'echo-without-packet-reader'
            echo = echo[:2] + struct.pack('>H', p.checksum(echo)) + echo[4:]
            frame = p.ipv4_frame(p.mac_for_ip(a.status()['vip']), p.mac_for_ip(b.status()['vip']),
                a.status()['vip'], b.status()['vip'], echo)
            await a.send_ethernet(frame)
            packet = (await channel.receive(2)).data
            self.assertEqual(packet[20:22], bytes(2))
            self.assertEqual(packet[24:], echo[4:])
            self.assertEqual(p.checksum(packet[20:]), 0)

        async def test_start_and_import_mode_create_no_udp_socket_or_tap(self):
            original = socket.socket
            handlers = [signal.getsignal(signal.SIGINT), signal.getsignal(signal.SIGTERM)]
            class NoUDP(original):
                def __init__(self, family=socket.AF_INET, type=socket.SOCK_STREAM, *args, **kwargs):
                    if type & socket.SOCK_DGRAM == socket.SOCK_DGRAM:
                        raise AssertionError('UDP socket created in import mode')
                    super().__init__(family, type, *args, **kwargs)
            with patch.object(socket, 'socket', NoUDP), patch.object(Tap, '__init__', side_effect=AssertionError('TAP opened')):
                client = await self.new_client(10)
                self.clients.append(client)
                self.assertIsNone(client.status()['local_udp'])
            self.assertEqual(handlers, [signal.getsignal(signal.SIGINT), signal.getsignal(signal.SIGTERM)])

        async def test_start_cancellation_waits_for_worker_and_releases_profile(self):
            directory = Path(self.folder.name) / 'cancelled'
            Profile(directory)
            save_identity(directory / 'identity.json', self.clients[0]._engine.identity)
            client, started = OpenRad(directory, networks=['fixture']), threading.Event()
            def delayed_identity(profile, *args, **kwargs):
                started.set()
                time.sleep(.08)
                return p.Identity.load(profile.path / 'identity.json')
            ticks = 0
            async def heartbeat():
                nonlocal ticks
                while True:
                    ticks += 1
                    await asyncio.sleep(.005)
            ticker = asyncio.create_task(heartbeat())
            try:
                with patch.dict(globals(), ensure_identity=delayed_identity):
                    start = asyncio.create_task(client.start())
                    while not started.is_set():
                        await asyncio.sleep(.001)
                    start.cancel()
                    with self.assertRaises(asyncio.CancelledError):
                        await start
                self.assertGreaterEqual(ticks, 3)
                self.assertIsNone(client._profile)
                profile = Profile(directory)
                profile.acquire()
                profile.close()
                await client.close()
            finally:
                ticker.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await ticker

        async def test_stop_command_automatically_closes_receivers(self):
            a = self.clients[0]
            channel = a.subscribe('udp')
            self.assertTrue((await a.command('stop'))['stopping'])
            with self.assertRaises(ChannelClosed):
                await channel.receive(2)
            await a.close()
            self.assertFalse(a._engine.thread.is_alive())

        async def test_close_wakes_receivers_commands_and_releases_profile(self):
            a = self.clients[0]
            channel = a.subscribe('udp', capacity=1)
            receivers = [asyncio.create_task(channel.receive()) for _ in range(4)]
            with patch.object(a._engine, 'command', lambda body, cb: None):
                request = asyncio.create_task(a.search('pending'))
                await asyncio.sleep(0)
                await a.close()
                for task in receivers + [request]:
                    with self.assertRaises(ChannelClosed):
                        await task
            await a.close()
            with self.assertRaises(ChannelClosed):
                await a.start()
            profile = Profile(a.data_dir)
            profile.acquire()
            profile.close()

        async def test_duplicate_profile_and_failed_start_cleanup(self):
            a = self.clients[0]
            duplicate = OpenRad(a.data_dir, networks=['fixture'])
            with self.assertRaises(ValueError):
                await duplicate.start(timeout=1)
            self.assertTrue(a._engine.thread.is_alive())
            self.assertIsNone(duplicate._profile)
            await duplicate.close()
            directory = Path(self.folder.name) / 'timeout'
            Profile(directory)
            save_identity(directory / 'identity.json', a._engine.identity)
            stalled = OpenRad(directory, networks=['missing'])
            with self.assertRaises(TimeoutError):
                await stalled.start(timeout=.03)
            self.assertIsNone(stalled._profile)
            profile = Profile(directory)
            profile.acquire()
            profile.close()

        async def test_optional_local_udp_and_import_share_same_engine(self):
            a = self.clients[0]
            d = await self.new_client(3, local_udp=True)
            self.clients.append(d)
            await asyncio.to_thread(self.link, a, d)
            channel = d.subscribe('udp', ports=[50002])
            sdk = LocalClient.from_profile(d.data_dir, timeout=2)
            try:
                await asyncio.to_thread(sdk.hello, subscribe=['udp'], ports=[50002])
                await a.send_udp(d.status()['vip'], 50001, 50002, b'both-apis')
                local = await channel.receive(2)
                wire = await asyncio.to_thread(sdk.receive, 2)
                self.assertEqual(local.data, sdk.udp_payload(wire)[-1])
                self.assertEqual(local.peer, wire['peer'])
                self.assertEqual(d.status()['mode'], 'direct+udp')
                self.assertTrue((d.data_dir / 'api.json').exists())
                # More than one poll batch, with no later traffic to wake it.
                # Both subscriptions must receive all 150 queued packets now.
                for index in range(150):
                    ip = p.udp_ipv4(a.status()['vip'], d.status()['vip'], 50001, 50002, struct.pack('!I', index))
                    frame = p.mac_for_ip(d.status()['vip']) + p.mac_for_ip(a.status()['vip']) + b'\x08\x00' + ip
                    d._engine.incoming_frame(d._engine.workers[a.status()['rid']], frame)
                def wire_burst():
                    return [sdk.udp_payload(sdk.receive(2))[-1] for _ in range(150)]
                async def memory_burst():
                    return [(await channel.receive(2)).data for _ in range(150)]
                wire_packets, memory_packets = await asyncio.gather(asyncio.to_thread(wire_burst), memory_burst())
                self.assertEqual(wire_packets, memory_packets)
                self.assertEqual([struct.unpack('!I', item)[0] for item in memory_packets], list(range(150)))
                sdk.close()
                await d.close()
                self.assertFalse((d.data_dir / 'api.json').exists())
            finally:
                sdk.close()

    class ImportTests(unittest.TestCase):
        def test_imported_by_separate_modules_without_startup_side_effects(self):
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root / 'producer.py').write_text('from openrad import OpenRad, Packet\nclient = OpenRad("must-not-exist")\n')
                (root / 'consumer.py').write_text('from openrad import OpenRad, PacketChannel, ChannelClosed\nfrom producer import client\nassert isinstance(client, OpenRad)\n')
                code = '''import socket, threading, signal, pathlib
before = threading.enumerate()
handlers = [signal.getsignal(signal.SIGINT), signal.getsignal(signal.SIGTERM)]
class ForbiddenSocket(socket.socket):
    def __init__(self, *args, **kwargs): raise AssertionError('socket created during import/construction')
socket.socket = ForbiddenSocket
import consumer
assert threading.enumerate() == before
assert handlers == [signal.getsignal(signal.SIGINT), signal.getsignal(signal.SIGTERM)]
assert not pathlib.Path('must-not-exist').exists()
'''
                result = subprocess.run([sys.executable, '-c', code], cwd=root,
                    env=dict(os.environ, PYTHONPATH=str(Path(__file__).parent)), capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(case)
        for case in (ProtocolTests, AsyncTests, ImportTests))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


def parser():
    cli = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    cli.add_argument('--data-dir', type=Path, default=default_profile(), help='private profile directory')
    cli.add_argument('--version', action='version', version=VERSION)
    sub = cli.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run', help='start the VPN and localhost UDP server without sudo')
    run.add_argument('--name', default=socket.gethostname()[:80] + '-python')
    run.add_argument('--identity', type=Path, help='import an inactive owned Python/Rust identity into a new profile')
    run.add_argument('--host', default=p.DEFAULT_HOST, help='bootstrap IPv4 address')
    run.add_argument('--server-port', type=int, default=p.DEFAULT_PORT)
    run.add_argument('--modulus', type=Path, help='optional RSA public modulus override')
    run.add_argument('--network', action='append', default=[])
    run.add_argument('--force-relay', action=argparse.BooleanOptionalAction, default=None)
    run.add_argument('--passive', action='store_true', help='accept incoming peers and explicit connect only')
    run.add_argument('--max-peers', type=int, default=32)
    run.add_argument('--mode', choices=('auto', 'server', 'tap'), default='auto')
    run.add_argument('--no-tun', action='store_true', help='alias for --mode server')
    run.add_argument('--interface', default='openradpy0')
    run.add_argument('--port', type=int, default=0, help='localhost UDP API port; 0 selects a random high port')
    run.add_argument('--duration', type=float, default=0, help='optional bounded run in seconds')
    for command in ('status', 'peers', 'stop', 'self-test'):
        sub.add_parser(command)
    search = sub.add_parser('search')
    search.add_argument('query', nargs='?', default='')
    search.add_argument('--cursor', type=int, default=0)
    for command in ('join', 'create'):
        cmd = sub.add_parser(command)
        cmd.add_argument('name')
        cmd.add_argument('--private', action='store_true', help='prompt for a private network password')
        cmd.add_argument('--password-file', type=Path, help='read password without putting it in argv')
    for command in ('leave', 'delete'):
        cmd = sub.add_parser(command)
        cmd.add_argument('name')
    connect = sub.add_parser('connect')
    connect.add_argument('rid', type=int)
    relay = sub.add_parser('force-relay')
    relay.add_argument('enabled', choices=('true', 'false'))
    probe = sub.add_parser('ping')
    probe.add_argument('target', help='virtual IPv4 or peer RID')
    probe.add_argument('--count', type=int, default=1)
    probe.add_argument('--timeout', type=float, default=5)
    echo = sub.add_parser('udp-echo')
    echo.add_argument('--port', type=int, default=49000, help='virtual UDP port to answer')
    echo.add_argument('--duration', type=float, default=0)
    udp = sub.add_parser('udp-send')
    udp.add_argument('target', help='virtual IPv4 or peer RID')
    udp.add_argument('port', type=int)
    udp.add_argument('message', nargs='?', default='openrad-python UDP echo')
    udp.add_argument('--source-port', type=int, default=49001)
    udp.add_argument('--wait', type=float, default=5, help='seconds to wait for an exact reply; 0 sends only')
    return cli


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if not sys.platform.startswith('linux'):
            raise ValueError('this client supports Linux only')
        for field in ('duration', 'wait'):
            value = getattr(args, field, 0)
            if not math.isfinite(value) or value < 0:
                raise ValueError(f'{field} must be finite and nonnegative')
        if args.command == 'self-test':
            return self_test()
        if args.command == 'run':
            if args.max_peers < 1 or args.duration < 0 or not 1 <= args.server_port <= 65535:
                raise ValueError('max-peers must be positive; duration must be nonnegative; server port must be valid')
            return run_service(args)
        with LocalClient.from_profile(args.data_dir) as client:
            client.hello(subscribe=())
            if args.command == 'status':
                value = client.status()
            elif args.command == 'peers':
                value = dict(ok=True, peers=client.peers())
            elif args.command == 'search':
                value = client.command('search', query=args.query, cursor=args.cursor)
            elif args.command in ('join', 'create'):
                password = read_password(args) if args.command == 'create' or args.private or args.password_file else None
                value = client.command(args.command, name=args.name, password=password)
            elif args.command in ('leave', 'delete'):
                value = client.command(args.command, name=args.name)
            elif args.command == 'force-relay':
                value = client.command('force_relay', enabled=args.enabled == 'true')
            elif args.command == 'connect':
                value = client.command('connect', rid=args.rid)
            elif args.command == 'stop':
                value = client.command('stop')
            elif args.command == 'ping':
                if not 1 <= args.count <= 10 or not 0 < args.timeout <= 60:
                    raise ValueError('ping count must be 1–10 and timeout 0–60 seconds')
                value = ping(client, args.target, args.count, args.timeout)
            elif args.command == 'udp-send':
                peer = wait_peer(client, args.target)
                client.hello(subscribe=('udp',), ports=(args.source_port,), peers=(peer['rid'],))
                payload = args.message.encode('utf-8')
                sent = client.send_udp(peer['vip'], args.source_port, args.port, payload, peer=peer['rid'])
                received, until = False, time.monotonic() + args.wait
                while time.monotonic() < until:
                    try:
                        message = client.receive(until - time.monotonic())
                    except TimeoutError:
                        break
                    src, _, sport, dport, data = LocalClient.udp_payload(message)
                    if src == peer['vip'] and sport == args.port and dport == args.source_port and data == payload:
                        received = True
                        break
                value = dict(ok=received or args.wait == 0, queued=sent['queued'], received=received,
                    peer=peer['rid'], target=peer['vip'], bytes=len(payload), transport=peer['transport'])
            elif args.command == 'udp-echo':
                client.hello(subscribe=('udp',), ports=(args.port,))
                print_json(dict(event='udp_echo_ready', vip=client.status()['vip'], port=args.port))
                until = time.monotonic() + args.duration if args.duration else float('inf')
                while time.monotonic() < until:
                    try:
                        message = client.receive(min(10, until - time.monotonic()))
                    except TimeoutError:
                        continue
                    src, _, sport, dport, data = LocalClient.udp_payload(message)
                    client.send_udp(src, dport, sport, data, peer=message['peer'])
                    print_json(dict(event='udp_echo', peer=message['peer'], source=src, bytes=len(data)))
                value = dict(ok=True)
            else:
                raise ValueError('unknown command')
            print_json(value)
            return 0 if value.get('ok', True) else 1
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print_json(dict(ok=False, error=str(exc)))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

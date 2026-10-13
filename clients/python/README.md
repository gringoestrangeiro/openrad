# OpenRAD in Python for Linux

Standalone client in **three Python files**, adapted from the OpenRAD Python
and Rust projects under the MIT license. It needs no Wine, no Radmin
binaries, no Rust, and no access to the original source trees at runtime.

There are two unprivileged modes: the importable, asynchronous **`OpenRad`**
class exchanges packets directly in memory; the `run` command additionally
serves UDP on `127.0.0.1` with a system-chosen high port. Both carry IPv4,
Ethernet, and application UDP across the Radmin network over authenticated,
encrypted peer channels. They run as a regular user, with no sudo and no
TUN/TAP. The import API opens no UDP socket.

## Install

```bash
pip install "git+https://github.com/gringoestrangeiro/openrad.git@main#subdirectory=clients/python"  # from the repo
pip install ./clients/python                                                                          # local checkout
pip install "git+https://github.com/gringoestrangeiro/openrad.git@main#subdirectory=clients/python[fast]"  # with AES acceleration
```

## Import and use with asyncio

The three modules can live next to your application or be installed into a
virtual environment. The package includes the `openrad` command and the
self-test fixtures:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install .
# Optional, for accelerated AES: .venv/bin/python -m pip install '.[fast]'
```

This echo server runs inside your application's event loop and does not use
the local UDP protocol. Port 49000 below is a **virtual port**, inside the
packets carried by the Radmin network:

```python
import asyncio
from openrad import OpenRad

async def main():
    async with OpenRad(
        data_dir="./profiles/my-app",
        networks=["Minecraft [Português 15]"],
        force_relay=False,
    ) as vpn:
        print(vpn.status()["vip"])
        async with vpn.subscribe("udp", ports=[49000]) as packets:
            async for packet in packets:
                await vpn.send_udp(
                    packet.source, packet.destination_port, packet.source_port,
                    packet.data, peer=packet.peer,
                )

asyncio.run(main())
```

To send from another module, import the same class and pass the live
instance around:

```python
from openrad import OpenRad

async def send(vpn: OpenRad, destination: str, data: bytes):
    peer = await vpn.wait_peer(destination, timeout=45)  # VIP or RID
    return await vpn.send_udp(peer["vip"], 49001, 49000, data, peer=peer["rid"])
```

`await vpn.start()` starts the engine and waits for the requested networks.
`await vpn.start(wait=False)` followed by `await vpn.wait_online()` is also
available. The internal control and transport threads keep running while your
application does other work; registration, network waits, and shutdown never
block the event loop. `await vpn.close()` stops the engine, joins the
threads, releases the profile, and wakes consumers with `ChannelClosed`. The
`async with` context does this automatically, including when your code raises.

Importing or constructing `OpenRad(...)` opens no sockets, creates no
directories, starts no threads, and changes no signals. Each instance belongs
to a single event loop and a single session; build a new one after closing.
Concurrent instances need different profiles and identities. A second
instance on the same profile fails without disturbing the first. By default,
`networks=None` reuses the saved selection; `networks=[]` accepts every
network already joined by the identity.

The main API operations are:

| Operation | Usage |
| --- | --- |
| State and members | `vpn.status()`, `vpn.peers()`; synchronous in-memory snapshots |
| Connect | `await vpn.connect(rid)`, `await vpn.wait_peer(vip_or_rid)` |
| Networks | `await vpn.join(name, password=None)`, `create(name, password)`, `leave(name)`, `delete(name)` |
| Network selection | `await vpn.select_networks([name, ...])`; selects networks without re-joining |
| Search and relay | `await vpn.search(query)`, `await vpn.set_force_relay(True)` |
| Send | `await vpn.send_udp(dest, src_port, dst_port, bytes)`, `send_ipv4(bytes)`, `send_ethernet(bytes)` |
| Receive | `vpn.subscribe("udp"/"ipv4"/"ethernet", ports=(), peers=(), capacity=256)` |
| Default Ethernet frame | `await vpn.receive(timeout=None)` |

Each subscription is a `PacketChannel`, consumed with
`await channel.receive(timeout=...)` or `async for packet in channel`. An
immutable `Packet` holds `kind`, `peer`, `data`, `sequence`, `source`,
`destination`, `source_port`, and `destination_port`. On Ethernet/IPv4
channels `data` is the full frame/packet; on UDP channels it is only the
application payload. IP/port fields are `None` where they do not apply. The
three representations of one frame share `sequence`.

Separate subscriptions receive their own logical copies of events, sharing
the same immutable bytes. Several consumers of one subscription share its
queue. Empty filters accept all peers/ports; ports can only be filtered on
UDP channels. Cancelling a wait closes neither the client nor the
subscription. `timeout=0` polls only what is already queued; an empty queue
raises `TimeoutError`. Closing the subscription ends its `async for`.

The memory channel uses one batch notification per event-loop wakeup, bounded
queues, and delivery in batches of up to 64 frames. It does no periodic
polling to receive packets. The default is 256 packets per subscription and
1024 frames on the inter-thread bridge, tunable via `receive_queue`,
`bridge_queue`, and `capacity`. A slow consumer drops its own queue's new
events and increments `channel.dropped`; other subscriptions are unaffected.
Counts also appear in `status()["counters"]`. There are at most 64
subscriptions per instance.

With no explicit subscriptions or `receive()` use, the default receiver keeps
its bounded backlog as raw frames: it never wakes asyncio nor builds `Packet`
objects. First use converts the backlog preserving order and `sequence`.
Active subscriptions decode only the requested layers. Authentication,
validation, keepalives, and ARP/ICMP replies stay in the engine.

Sends return `{"ok": True, "queued": N, "bytes": ...}` once queued; this is
not a remote-delivery acknowledgement. Full queues, disconnected targets,
and invalid packets raise `ValueError`. Commands accept `timeout=40`;
cancelling or timing out a command call does not undo an operation already
sent to the server.

`OpenRad(..., local_udp=True, udp_port=0)` **also** exposes the ORAD protocol
on localhost, served by the same event loop. You can mix programs using
`LocalClient` with in-memory subscriptions without starting a second engine.
The default `local_udp=False` creates no UDP socket, no `api.json`, and no
TAP interface.

## Run

Requires Python 3.10 or newer on Linux. The standard library is enough. If
PyCryptodome is already installed, the client uses its AES implementation to
speed up encryption; the pure-Python fallback is tested too.

```bash
python3 openrad.py run --network 'Minecraft [Português 15]' --force-relay
```

`--mode auto`, the default, probes `CAP_NET_ADMIN`. Without that capability
it uses the UDP server. To request that mode explicitly:

```bash
python3 openrad.py run --mode server --force-relay --network 'Minecraft [Português 15]'
# --no-tun also selects server mode.
```

The process prints the UDP address, the port, the VIP, and the `api.json`
path. The access token lives in that private file and never appears in the
log. The default profile is `$XDG_STATE_HOME/openrad-python`, or
`~/.local/state/openrad-python` when the variable is unset.

First use registers an identity and saves it for reuse. Registrations with
an ambiguous response are not retried automatically. Authenticated server
redirects have a retry limit. You can also import your own inactive identity
into a fresh profile:

```bash
python3 openrad.py --data-dir ./profiles/my-client run --identity /path/identity.json
```

The profile directory is mode `0700`; identity, settings, and API discovery
are mode `0600`. Each profile allows only one active engine. Private-network
passwords are never saved to settings. The profile is separate from the Rust
OpenRAD one and does not control that program's existing service.

## Commands

Run these commands in another terminal while `run` is active. All of them
talk to the engine through the local UDP API.

```bash
python3 openrad.py status
python3 openrad.py peers
python3 openrad.py search 'Minecraft'
python3 openrad.py join 'Minecraft [Português 15]'
python3 openrad.py join 'My private network' --private
python3 openrad.py create 'My private network'
python3 openrad.py leave 'My private network'
python3 openrad.py delete 'My private network'
python3 openrad.py connect PEER_RID
python3 openrad.py force-relay true
python3 openrad.py ping VIP_OR_RID --count 3
python3 openrad.py stop
```

`--private` and `create` prompt for the password without echoing it. There is
also `--password-file /path/file` for automation. Put `--data-dir` before the
subcommand to use a different profile. `delete` forwards the deletion to the
server and requires your identity to have administrator rights on that
network.

`force-relay true` forbids direct setup in both directions and drops existing
direct connections. Peer connections then use TCP relay; without that option
the client also tries direct TCP. The Radmin direct ENet/UDP transport is not
ported in this version. In the CLI, the **local API stays UDP** either way;
`OpenRad` offers the in-memory API regardless of transport.

The engine keeps control and peer heartbeats, reconnects with progressive
backoff, caps simultaneous connections, and tracks member join, leave, and
state. A failed connection shows up in `peers` and never crashes the engine.
Connections requested via `connect` get priority when capacity is full.
`--passive` disables automatic outbound connections; `connect` still works.
`--max-peers` accepts any positive integer, default 32. For example,
`--max-peers 400` allows testing hundreds of connections. The number of
simultaneous handshakes grows with that limit, up to 64; the default still
uses four handshakes. Linux I/O uses `poll()`, accepting descriptors above
1023 without root. A low open-file limit can be raised up to the user's hard
limit, e.g. with `ulimit -n 4096`.

## Use application UDP without TUN

You can test with two of your own clients on the same network. On client B:

```bash
python3 openrad.py --data-dir ./profiles/b run --force-relay --network 'Minecraft [Português 15]'
# In another terminal:
python3 openrad.py --data-dir ./profiles/b udp-echo --port 49000
```

On client A:

```bash
python3 openrad.py --data-dir ./profiles/b udp-send VIP_OR_RID_OF_B 49000 'Hello over local UDP' --source-port 49001
```

The path is: program A → localhost UDP A → encrypted peer channel →
localhost UDP B → program B. The reply travels back. `udp-send` checks the
exact reply bytes; `--wait 0` only sends.

Programs using the API can exchange their own packets with no kernel network
interface. Games that expect a system network adapter need API integration
or the optional TAP. Server mode installs no routes and never assigns the
VIP to the machine's interfaces.

## Synchronous SDK for the local UDP server

`LocalClient` lives in `openrad.py` itself; there is no separate SDK module.
The example below subscribes to a virtual UDP port and answers incoming
packets:

```python
from openrad import LocalClient, default_profile

with LocalClient.from_profile(default_profile()) as client:
    client.hello(subscribe=["udp"], ports=[49000])
    while True:
        message = client.receive(timeout=60)
        source, destination, source_port, destination_port, data = client.udp_payload(message)
        client.send_udp(source, destination_port, source_port, data, peer=message["peer"])
```

`data` is `bytes`: NUL, non-UTF-8 bytes, and empty payloads are supported.
Use `send_ipv4(packet, peer=rid)` for a full IP packet or
`send_ethernet(frame, peer=rid)` for Ethernet. The source VIP and MAC must be
the ones assigned to your identity; see `status()`. The MTU is 1500 and the
application UDP payload limit is 1472 bytes. ARP replies have the source
exception described below. Fragments are exposed on the IPv4/Ethernet
interfaces; the UDP SDK does no fragment reassembly.

A send reply confirms queue admission. To verify delivery, receive an
application-level reply packet from the remote side. `ping` does this with a
correlated ICMP identifier, sequence, checksum, and payload.

`LocalClient` keeps its subscription across long `receive()` calls. One
instance is synchronous and must be used by one thread at a time; create
separate clients for concurrent consumers.

## Local UDP protocol ORAD v1

Each datagram has a **40-byte** binary header followed by the payload.
Integers are network order, big-endian. The Python layout is
`struct.Struct("!4sBBHIQI16s")`.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | Magic ASCII `ORAD` |
| 4 | 1 | Version: `1` |
| 5 | 1 | Opcode |
| 6 | 2 | Flags: `0` in requests; mask `0x0001` marks errors in replies |
| 8 | 4 | Request ID; must be nonzero |
| 12 | 8 | Peer RID; `0` selects by route/destination |
| 20 | 4 | Exact payload size |
| 24 | 16 | Binary token from `api.json`, hex-decoded |

| Opcode | Operation | Payload |
| --- | --- | --- |
| `0x01` | HELLO / subscribe | UTF-8 JSON with `subscribe`, `ports`, `peers` |
| `0x02` | STATUS | Empty |
| `0x03` | PEERS | JSON: `cursor` and `limit` (1–64) |
| `0x04` | COMMAND | JSON: `op` plus command fields |
| `0x05` | BYE | Empty; releases the subscription |
| `0x10` | SEND_ETHERNET | Full Ethernet frame, up to 1514 bytes |
| `0x11` | SEND_IPV4 | Full IPv4 packet, up to 1500 bytes |
| `0x12` | SEND_UDP | Destination IPv4 (4 bytes), source port (`u16`), destination port (`u16`), data |
| `0x40` | RX_ETHERNET | Received Ethernet frame |
| `0x41` | RX_IPV4 | Received IPv4 packet |
| `0x42` | RX_UDP | Source IPv4, destination IPv4, source port (`u16`), destination port (`u16`), data |

Replies use `opcode | 0x80`, the request's ID, and JSON with `ok`. RX events
carry the authenticated RID in the header plus an event sequence; the
Ethernet, IPv4, and UDP views of one frame share that sequence. The RX_UDP
payload starts with `struct.Struct("!4s4sHH")`, 12 bytes.

HELLO example:

```json
{"subscribe":["ethernet","ipv4","udp"],"ports":[49000],"peers":[]}
```

Empty `ports`/`peers` lists accept every UDP destination and peer in the
subscription. The port filter affects RX_UDP only. There can be up to 32
clients; an idle subscription expires after 120 seconds. Any request renews
the subscription. Send STATUS periodically when implementing another client.

COMMAND accepts `search` (`query`, `cursor`), `join` (`name`, optional
`password`), `create` (`name`, `password`), `leave`/`delete` (`name` or
`network_id`), `connect` (`rid`), `force_relay` (`enabled`, boolean), and
`stop`. Network operations are serialized to avoid ambiguous private-auth
responses.

The SDK retries requests with the same ID every 500 ms. The API keeps packet
replies for 10 seconds and command replies for 90 seconds: repeating the same
datagram inside that window never sends the packet or command twice. Never
reuse an ID with different content. Network commands can take up to 35
seconds. The API datagram limit is 64000 bytes and JSON requests are limited
to 16384 bytes.

`peer=0` routes by destination VIP/MAC. IPv4 broadcast and multicast,
Ethernet, and raw IPv6 are fanned out **once per eligible connected peer** of
the selected networks; sharing several networks never duplicates a peer.
This includes broadcast ARP requests/replies, even when the ARP target IP is
a single member's VIP. An explicit RID restricts sending to that peer.
Offline members, members pending approval, and members outside the selection
are excluded, even while a closing connection still exists. The configured
connection cap (`max_peers`, default 32, no fixed ceiling) also bounds
fan-out; packets are never queued for disconnected members.

Source Ethernet MAC and IPv4/ARP-request source IP stay bound to the
authenticated channel. **ARP replies (`opcode=2`) have a deliberate exception
in both directions:** `send_ethernet()` and reception accept arbitrary
source Ethernet MAC, ARP sender MAC, and ARP sender IP. This covers normal,
broadcast, and gratuitous replies, with bytes unrewritten. Size limits, ARP
shape, destination routing, and network membership still apply. In an
Ethernet `Packet`, `peer` is the channel RID even when the ARP frame
advertises another sender.

That policy is identical in memory mode, local UDP, and TAP. Received frames
are never forwarded to other peers. Without TAP, the engine answers ARP
requests and ICMP echo for its own VIP.

## Optional TAP and tests

`--mode tap` uses `/dev/net/tun` and `ip` when the process **already has**
`CAP_NET_ADMIN`. The program never calls sudo and never asks for elevation.
In auto mode, TAP creation failure falls back to the UDP server. The UDP API
stays available when TAP is up.

```bash
python3 openrad.py self-test
python3 -S openrad.py self-test  # including without PyCryptodome/site-packages
```

The tests use synthetic crypto vectors, private-password proofs, partial TCP
framing, API authentication, tampered-packet rejection, deduplication, and a
real localhost UDP exchange over an encrypted TCP channel. They also cover
three importable instances with encrypted SH/TCP, every packet type,
broadcast/multicast to several peers, arbitrary-source ARP replies on egress
and ingress, imports by external modules, full queues, cancellation,
timeouts, concurrent consumers, and profile/thread release.

Live-service checks are documented in `docs/VALIDATION.md`.

## Limitations

- No direct UDP/ENet peer transport: peers connect over encrypted TCP
  channels (direct TCP or relay TCP); `force_relay` restricts both
  directions to relay TCP.
- Passwords are dropped, not wiped: CPython `bytes` are immutable, so the
  client releases password and key-schedule references as soon as possible
  (`SHClient`/`SHServer` drop `password` once the exponent is derived,
  peer offers are released after setup or after a 40 s TTL, and every
  `PureCC` rekey calls `expand.cache_clear()`), but it cannot overwrite the
  underlying memory. Passwords never reach logs, serialization, or settings.

## Benchmarking hundreds of peers

The offline harness uses real TCP connections and SH authentication,
`PeerChannel`, `PureCC`, `PeerWorker.run`, `Engine`, and the memory bridge.
Only the external coordination service is stubbed. The generator runs in
another process and is excluded from the client's CPU/RSS. The load is 10%
ARP plus 30% of each Ethernet UDP frame size: 60, 500, and 1500 bytes.
`--rate` is the per-peer, per-direction rate, and ARP replies count toward
L2.

```bash
python3 tools/benchmark.py --peers 400 --seconds 60 --rate 5 --output reports/load.json
python3 tools/benchmark.py --peers 400 --seconds 10 --rate 5 --subscribers udp
python3 tools/benchmark.py --peers 400 --seconds 5 --rate 5 --profile reports/load.prof --profile-scope loop
python3 -m pstats reports/load.prof
```

Unprofiled load results are the performance numbers. CPU is
`process_time / monotonic_elapsed`, summed over all threads: 0.5 is roughly
half an estimated core. RSS/HWM come from `/proc/self/status`. The JSON also
records drops, subscriber delivery, held connections, and requested-rate
compliance; holding idle peers proves no throughput.

To repeat the test on the eight public Minecraft [Português 1–8] networks,
with a separate persistent profile, relay connection, and a 60-second
window:

```bash
python3 tools/live_benchmark.py --force-relay --seconds 60
```

That last command reaches the live service and joins all eight networks. The
details and measured limits, including the PyCryptodome backend, are in
`docs/VALIDATION.md`.

| File | Responsibility |
| --- | --- |
| `openrad.py` | CLI, UDP server, SDK, and integrated tests |
| `radvpn.py` | Engine, peers, sockets, profiles, and optional Linux TAP |
| `radproto.py` | Crypto, authentication, TLV, networks, and packets |

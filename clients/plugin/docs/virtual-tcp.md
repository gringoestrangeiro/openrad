# In-memory Ethernet to loopback TCP endpoint

`bridge.VirtualTcpBridge` implements a passive Ethernet/ARP/IPv4/TCP endpoint in
Java 21. It receives authenticated tunnel frames and returns Ethernet frames to
the same peer RID. A permitted SYN opens one nonblocking `SocketChannel` to the
single configured, resolved loopback backend. The virtual destination port and
the backend port can differ. No TAP adapter, packet driver, native library,
subprocess, system route, or privileged socket is used by this component.

## Integration contract

```java
try (var tcp = new VirtualTcpBridge(virtualIpv4, localMac, virtualPort,
        new InetSocketAddress(InetAddress.getLoopbackAddress(), backendPort),
        VirtualTcpBridge.Limits.defaults(),
        (rid, frame) -> authenticatedPeerSendQueue(rid).offer(frame))) {
    tcp.accept(new InboundFirewall.PeerGrant(rid, true, allowedJoinedNetworkIds,
            authenticatedPeerIpv4, negotiatedPeerMac), ethernetFrame);
    // On removal of the authenticated transport or its allowed memberships:
    tcp.revokePeer(rid);
}
```

The caller supplies the authenticated RID, current allowed joined networks,
membership-provided IPv4 address, and MAC from authenticated service negotiation.
They must never be inferred from packet claims. RID and IPv4 are independent.
The canonical OpenRad local MAC is `02:1a:<four IPv4 octets>`, matching Rust
`src/tunnel.rs::mac`; `link.OpenRadAddress.mac` implements that mapping. A peer's
negotiated unicast MAC is used as its actual binding.

`accept` copies into a bounded queue and returns false on queue pressure,
ineligible grants, invalid frame length, or shutdown. True means queued; the
selector subsequently performs firewall checks. The sender callback runs on the
selector thread and must enqueue immediately without blocking. A transport queue
may drop frames under pressure: ordinary TCP retransmission then recovers. Do not
invoke Bukkit APIs from either path. `revokePeer` invalidates queued frames from
the old epoch and closes that RID's channels. Callers must stop submitting old
grants after revocation and must bind worker lifetime to its attachment generation.

## Admission and packet scope

The firewall checks authenticated allowed membership, bound source MAC/IP, the
exact local destination MAC/IP, and the single exposed TCP port before opening
any backend channel. ARP replies are restricted to authenticated requests for the
local IP, with matching Ethernet and ARP sender identities. IPv4 header and TCP
pseudo-header checksums are verified before state mutation. Malformed lengths,
IPv4 fragments, IPv4 options, unsupported protocols/EtherTypes, TCP reserved
header bits, and urgent data are dropped.

This is a service endpoint, not a general network router. There is no IPv6, inbound UDP service,
ICMP, active TCP open, multicast routing, VLAN, or arbitrary destination support.
The separate LAN discovery component emits only validated local Minecraft advertisements;
see `lan-discovery.md`. It does not broaden this TCP endpoint's ingress policy.
SYN data is not accepted as early data; only SYN is acknowledged and the client
must retransmit the data after the handshake. MSS is negotiated up to 1460 bytes
(default 536 without an option); MSS below 64 is rejected. Window scaling, SACK,
TCP timestamps, and ECN are not negotiated. ECN-capable SYNs are accepted with an
ordinary SYN-ACK. The unscaled receive window is at most 65535 bytes.

## Connection behavior

One selector thread owns channels and TCP state. The endpoint implements:

- Random initial sequence numbers, three-way handshake, duplicate SYN handling,
  and handshake retransmissions without duplicate backend connections.
- Serial-number wrap, cumulative and partial ACKs, rejection of ACKs beyond
  bytes sent, ordered delivery, overlap/duplicate suppression, and out-of-order
  data/FIN reassembly in a fixed 65536-byte ring.
- Advertised receive windows based on queued backend bytes and global pressure;
  backend `OP_WRITE` drains reopen the window. Backend reads stop at configured
  retained-data limits. Peer windows and window updates constrain every new send.
- Retained unacknowledged segments, RTT estimation with Karn's retransmission
  exclusion, a 200ms–8s RTO with exponential backoff, bounded retries, slow start,
  additive congestion growth, congestion reduction, and three-duplicate-ACK
  fast retransmit. This is a compact congestion controller, not a complete
  implementation of all optional modern TCP recovery algorithms.
- Zero-window persist probes, loss recovery for data and FIN, independent
  half-closes, delayed backend `shutdownOutput` until accepted bytes are written,
  bounded half-close lifetime, and TIME_WAIT with duplicate-FIN acknowledgment.
- Exact-sequence RST admission and challenge ACKs for in-window reset attempts;
  reset on backend refusal/error or an expired connection deadline.

Default limits are 128 flows globally, 8 per RID, 2048 queued inbound frames,
60 incomplete TCP handshakes, 256KiB retained bytes per direction per flow, and 64MiB globally accounted
buffering. The global accounting includes every flow's 64KiB reassembly storage
plus queued backend bytes, occupied reorder bytes, and retained outbound bytes.
It excludes bounded object/BitSet overhead, short-lived packet serialization
copies, inbound frames (at most 2048 × 1518 bytes), the bounded transport queues,
and OS socket storage. Socket send/receive buffers are requested as 32KiB each;
the operating system controls their actual size. Actual heap use is consequently
larger than `Snapshot.bufferedBytes`, but all queues and payload retention are
bounded. Per-flow data limits apply separately in each direction.

`state(rid, sourcePort)` publishes the selector's derived TCP state for debugging:
LISTEN, CONNECTING_BACKEND, SYN_RECEIVED, ESTABLISHED, FIN_WAIT_1/2, CLOSE_WAIT,
CLOSING, LAST_ACK, TIME_WAIT, or CLOSED after bridge shutdown. Passive close order
is retained so LAST_ACK and simultaneous CLOSING are distinguished. Regression
tests observe handshake, both active-close states, passive-close states, TIME_WAIT,
and shutdown transitions. The pending-SYN cap excludes established connections;
an expired handshake frees its pending slot without disrupting a healthy flow.

Default deadlines are 5s backend connect, 15s handshake, 3min without stream
progress, 2min half-close drain, and 30s TIME_WAIT. Arbitrary duplicate ACK traffic
does not refresh the stream-progress deadline. Production runtime may set the
flow/connect/handshake limits from configuration. Shutdown closes all channels
and releases queues without waiting for TCP teardown on the tunnel.

## Executed synthetic checks

`gradlew.bat test` includes real unprivileged loopback sockets and an in-memory
Ethernet client. The bridge integration suite verifies:

- Byte-exact 2MiB-plus duplex echo with reverse-ordered packet pairs, duplicates,
  initial client sequence wrap, and a deliberately lost server data segment.
- A separate 2MiB-plus upload to a stopped backend reader: receive window closes
  at the configured 131072 globally accounted bytes, reopens when reads resume,
  and the backend receives the exact original stream.
- Overlapping receive intervals with an out-of-order FIN, partial outbound ACK
  suffix recovery, SYN-ACK and FIN retransmissions, and TIME_WAIT reclamation.
- Both half-close directions, advertised zero-window persist/resume, source
  spoofing and unjoined peers, authenticated ARP, peer revocation, per-RID/global
  flow caps, out-of-window RST, ACK of unsent data, handshake expiry, backend
  refusal, and rejection of non-loopback/unresolved backend destinations.
- A selector-barrier regression that queues old-epoch packets, revokes the peer,
  and queues a new SYN before the deferred revocation is drained. Old traffic
  cannot open a backend and the new authenticated connection survives.

`runtime.VpnRuntimeTest` additionally runs a complete local synthetic RSA/SRP
attachment and coordinator, an authenticated direct-TCP peer, Ethernet packets
through the virtual endpoint, and real backend echo. It verifies repeated control
ticks preserve the same healthy peer, role withdrawal closes its backend, ACL and
role checks apply to the same network, reconnect publishes the new VIP, and stop
unblocks attachment/peer authentication sockets. An interrupted ambiguous
registration retains its recovery marker and cannot write a late identity. A
package-private runtime constructor injects only the synthetic RSA public modulus;
the production constructor retains the official public-key default.

Codec/firewall tests additionally check canonical MAC mapping, serial arithmetic,
checksums, strict exposure rules, and 10000 deterministic malformed frame inputs,
including checksum-correct malformed IPv4/TCP headers. These are regression and
bounded robustness checks, not a claim of exhaustive fuzzing or RFC conformance.
They do not establish interoperability with the public service, a real OpenRad
or official Radmin client, a Minecraft client, or a deployed Paper server. Those
checks require a separately authorized real-session acceptance run.

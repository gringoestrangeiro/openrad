# Peer transport implementation and evidence

The production implementation is Java 21 using JDK cryptography, sockets and
virtual threads. It does not start Rust, another VPN process, a native library,
a driver, or a virtual network adapter. `src/test/interop` contains separate
development tools; these are not part of the plugin JAR.

The reference was the unmodified OpenRad Rust source in `src/peer.rs`,
`src/incoming.rs`, `src/session.rs`, `src/udp.rs`, and `src/tunnel.rs` from the
provided checkout (Cargo package version 2.0.2).

## Implemented behavior

| Layer | Behavior |
| --- | --- |
| Framed TCP | Big-endian length framing; bounded records, bytes, reads and writes; interruptible NIO connection establishment; setup deadlines; closing a socket interrupts blocked work. |
| TCP rendezvous | Exact 24-byte connector preamble, authenticated RID/CID correlation and canonical non-reflected checksum. |
| Peer SH | Both roles use the real six-message SRP handshake and verify the remote proof before installing the key. |
| Service | Encrypted race marker/GUID, RID-correlated service SYN, version 5–8 negotiation, MAC validation and final ACK. |
| Tunnel | Stateful authenticated AES-CBC through `crypto.Channel`; admission is checked before encryption. Uncompressed Ethernet envelopes and keepalive request/reply types use canonical bytes. Every envelope in a record is validated before a frame is exposed. |
| Reliable UDP | Candidate-correlated P/A/C punching, same-host port learning bounded to eight endpoints, ENET CONNECT/VERIFY, canonical checksum, ACKs, retransmission, wrapping sequence order, deduplication, bounded reordering and fragment reassembly. |
| UDP bounds | Send window 256, receive sequence window 512, up to 128 fragments/message, 16 incomplete reassemblies, 65,536 bytes/message; retry interval 400 ms, silent-peer retry limit 20, liveness/wall-clock bound 35 seconds. Channel-backed production sockets retain a send refused for congestion and retry after 10 ms. |
| UES | Two distinct authenticated hosts are queried from one socket. Transactions and source endpoints are correlated. Requests resend at 0/400/900/1500 ms. Agreement/disagreement uses the first server's observation; a lone observation is accepted only at the 2500 ms deadline. |
| Relay | Exact ticket pairing followed by the same mutual peer SH, encryption and Ethernet service protocol as direct paths. |
| Negotiation | Four diversified outgoing TCP lanes run alongside UDP, mapped UDP and relay preparation. Relay authentication waits for the direct head start (4 seconds, bounded extension 8 seconds; active direct progress can hold it 16 seconds). The first authenticated service channel wins; losing transports are closed and setup workers canceled. |
| Incoming | Authenticated attachment offers are correlated by CID and RID. Duplicate CIDs cannot replace a secret. Pending offers, followup mailboxes, lifetimes and concurrent rendezvous checks are bounded. Partial TCP rendezvous cannot block another candidate; TCP service authentication is serialized. |
| Policy/lifetime | Disabled transports open no candidate/listener path. Relay-only incoming setup advertises no direct endpoint. A near-term attachment refusal disables future acceptor-initiated relay requests. Setup resource registration precedes workers, including cancellation races. Peer SH temporary keys and rejected/expired offer secrets are cleared. |

`PeerConnector` returns the winning `PeerChannel` and retains the outgoing
coordinator for the connection lifetime. Its resource callback allows the runtime
to cancel setup or close a successful connection. Incoming mapped UDP receives
authenticated UES hosts through the final-argument `accept` overload.

## Executed tests

The JUnit suite uses synthetic credentials and local sockets. It exercises:

- Real RSA purpose-5 control authentication, correlated coordinator negotiation,
  `IncomingHub`, both peer roles, and bidirectional encrypted Ethernet over TCP,
  UDP and a relay forwarding between two real TCP sockets.
- Four TCP lanes bypassing three stalled endpoints; partial and wrong incoming
  rendezvous bypass; cancellation closing incoming listeners; a blocked TCP write
  closed by its total session deadline.
- Two hundred bidirectional full-size Ethernet frames over fragmented encrypted
  UDP, preserving payload order and the CBC chain.
- Independent raw ENET packets checking command bytes, retransmission bytes,
  checksum rejection, ACK holes, window admission, reordering, deduplication,
  out-of-order fragment reassembly and gapped-fragment rejection.
- Correlated/reordered UES responses, lost-request retransmission, disagreement
  and lone-observation deadlines; malformed tunnel records and incoming offer
  deduplication/mailbox/lifetime bounds.

Actual Java/canonical-Rust interoperability was also executed in both directions
over direct TCP and reliable UDP. Each of the four cases exchanged 50
bidirectional 1514-byte Ethernet frames after mutual SH and service negotiation.
The Rust acceptor calls the actual public `PeerChannel::accept_authenticated`;
the Rust initiator uses the canonical `ShClient`, `Channel`, `Framed`, `Enet` and
service codec because the public Rust peer connector also opens a coordinator.
The harness links existing canonical library artifacts without editing its
source. Its UDP socket reproduces canonical dual-stack binding on Windows.

To repeat on Windows after building the unmodified canonical library with a
compatible Rust toolchain:

```powershell
.\gradlew.bat test
.\src\test\interop\run-transport-interop.ps1 -OpenRadRoot C:\path\to\openrad
```

The harness requires Rust and its existing build artifacts only for this
development check. Deployment requires Java and Paper.

## Limits of this evidence

Local interoperability establishes the peer wire protocol and encrypted frame
exchange, not reachability through every real NAT or firewall. Real WAN IPv6,
NAT mapping behavior, packet loss on a public relay and large peer-count/load
behavior have not been established by these local tests. The Java scheduling
implementation has the same main bounds and timing rules, but it is not a
line-for-line port of every canonical scheduling/reporting regression. It does
not emit Rust's per-attempt JSON reports. The production channel-backed UDP
socket handles nonblocking send refusal; an externally supplied plain
`DatagramSocket` reports its socket write errors directly.

On Windows an abruptly closed UDP peer can produce ICMP port-unreachable while
its final ACK is being sent. Complete validated messages already queued are
delivered before surfacing that terminal transport error. Tests use an explicit
application receipt before orderly teardown, including when talking to Rust.

These transport checks do not establish Minecraft protocol behavior or a live
Paper server's full packet path; those belong to the bridge/runtime tests and
deployment validation.

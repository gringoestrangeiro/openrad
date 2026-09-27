# Performance improvements

OpenRad now allows up to **64 outgoing peer handshakes at once**, compared with
24 in the initial 0.3.0 build. A total limit of **80 handshakes** leaves capacity for incoming
offers when outgoing attempts are stalled. Established channels do not consume
handshake slots, and cancelled workers retain their slots until they exit.

Both the desktop application and CLI use these limits. Incoming offers have
priority, followed by peers selected for application traffic. Within each
priority, peers with fewer failures go first, so repeated failures cannot keep
new peers behind them. Incoming offers have a bounded 256-entry mailbox, and
the advertisement queue accommodates a burst from all 80 setup workers.

## Initial peer connection

- TCP candidates start as soon as they arrive from the authenticated coordinator.
  Two bounded TCP workers try alternating candidates, allowing a reachable
  address to bypass an unresponsive first address.
- Local UDP advertisement and the relay request no longer wait for mapped UDP
  discovery. Mapping runs in its own cancellable worker on both connection roles.
- Both authenticated UDP discovery servers are queried concurrently on the same
  socket with a shared two-second deadline. Both replies must still match their
  transactions and agree on the external address. Only unanswered queries retry.
- Relay setup starts 750 ms after its ticket arrives, allowing direct paths a
  short head start. It races with direct authentication instead of waiting for
  an eight-second direct timeout. The first fully authenticated Ethernet service
  wins. A relay can therefore win while a slower direct path was still viable;
  established sessions are not subsequently migrated between transports.
- Linux TCP connects use one nonblocking connection attempt, with cancellation
  checks at most 50 ms apart. Losing or superseded attempts no longer occupy
  setup slots until the blocking connect timeout expires.
- The first retry waits 500–625 ms with per-peer jitter, followed by exponential
  backoff capped at 60–75 seconds. Incoming offers can bypass the retry timer.

For 100 outgoing peers, the capacity check now needs two batches (`64 + 36`)
instead of five (`24 + 24 + 24 + 24 + 4`). This is a scheduling comparison, not
a measurement of live VPN connection time. Each outgoing handshake can run up
to five transport workers (two TCP, local UDP, mapped UDP, relay) and one mapping
worker, all bounded by the peer setup limits. Every losing worker is cancelled
and joined before its setup slot is released.

Headless regression tests use local sockets and synthetic credentials to verify
concurrent discovery, transaction checks, direct TCP progress despite a stalled
candidate, relay progress before direct timeout, and prompt cancellation. They
also exercise the full peer/service authentication and encrypted traffic on the
winning path. They do not measure NAT traversal or public relay performance.

## Local measurements

| Operation | Before | After |
| --- | ---: | ---: |
| 100,000 idle socket checks | 37.97 ms | 21.66 ms |
| 200,000 Ethernet packet encodes | 12.55 ms | 5.26 ms |
| 10,000 TCP frames over localhost | 27.65 ms | 14.68 ms |

These measurements were collected on Linux x86-64 using an optimized release
build. Each result is the median of seven runs, alternating the order of the
original and optimized implementations. Socket checks used a zero timeout;
Ethernet frames and TCP payloads were 1,414 bytes. The TCP measurement includes
framing, transmission, and receipt over localhost, without encryption.

The measurements describe local processing and I/O overhead. They do not
measure public-network connection time or end-to-end VPN throughput. Actual
connection time also depends on peer responses, NAT, and relay availability.

## Implementation changes

- Linux TCP channels use `poll` instead of repeatedly changing socket flags and
  peeking for data. Long waits remain cancellable.
- TCP frame headers and bodies use vectored writes, including correct handling
  of partial and interrupted writes.
- Ethernet envelopes use one allocation.
- The desktop rebuilds membership views and forwarding eligibility only when
  membership changes, rather than every five milliseconds.
- Disabled reports skip JSON serialization.

Cryptography, authentication sequences and UDP wire formats are unchanged.
Scheduling and transport timing changes are described above. Vectored TCP writes
preserve the same framing and payload byte stream.

# Performance improvements

OpenRad now allows up to **64 outgoing peer handshakes at once**, compared with
24 in the initial 0.3.0 build. A total limit of **80 handshakes** leaves capacity for incoming
offers when outgoing attempts are stalled. Established channels do not consume
handshake slots, and cancelled workers retain their slots until they exit.

Both the desktop application and CLI use these limits. Incoming offers have
priority, followed by peers whose channels were connected before, other
retries, and new peers. Within each group, peers selected for application
traffic and then peers with fewer failures go first. Retry concurrency stays
below the outgoing limit so new peers retain setup capacity. Incoming offers
have a bounded 256-entry mailbox, and
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
- Direct paths get a four-second head start before relay setup, on both incoming
  and outgoing connections. A later UDP/TCP attempt extends this window, up to
  eight seconds after the relay ticket arrives. Outgoing relay setup can start
  sooner if all direct candidate responses have arrived, mapping has completed,
  and every direct attempt has failed. Explicit relay-only CLI diagnostics skip
  the wait. Late direct candidates continue to be accepted during relay setup.
  Once relay starts, the first fully authenticated Ethernet service wins;
  established sessions are not subsequently migrated between transports.
- Linux TCP connects use one nonblocking connection attempt, with cancellation
  checks at most 50 ms apart. Losing or superseded attempts no longer occupy
  setup slots until the blocking connect timeout expires.
- Desktop recovery retries include `Refused` peers. The first retry waits 1.5–4.5
  seconds, the second 3–9, the third 6–18, and later retries 12–36 seconds, with
  per-peer jitter. Recovery starts alongside initial handshakes; peers that
  were previously connected go first. The scheduler allows 16, 32, or 48
  outgoing retries in progress as recovery demand grows, with 200, 100, or
  50 milliseconds between starts. If at least three quarters of eight or more
  attempts in the last 30 seconds fail, it limits retries to at most 32 and
  spaces starts by at least 150 milliseconds. The overall 64 outgoing / 80
  total handshake limits still apply. Due retries reserve outgoing slots, while
  other slots remain available for new peers and incoming offers. The Retry
  button uses this same queue without resetting failure
  history or established connections. Incoming offers bypass the retry timer
  and pacing because their authenticated rendezvous windows expire.

For 100 outgoing peers, the capacity check now needs two batches (`64 + 36`)
instead of five (`24 + 24 + 24 + 24 + 4`). This is a scheduling comparison, not
a measurement of live VPN connection time. Each outgoing handshake can run up
to five transport workers (two TCP, local UDP, mapped UDP, relay) and one mapping
worker, all bounded by the peer setup limits. Every losing worker is cancelled
and joined before its setup slot is released.

Headless regression tests use local sockets and synthetic credentials to verify
concurrent discovery, transaction checks, direct TCP progress despite a stalled
candidate, a slower direct handshake winning over a ready relay, bounded relay
fallback, paced recovery of 100 overdue retries, and prompt cancellation. They
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

## Packet-path memory and CPU

- A TAP read now reuses one 64 KiB read buffer and returns a vector sized to the
  actual frame. Previously every read allocated and zeroed a 64 KiB vector,
  including reads of ordinary Ethernet frames.
- Outgoing peer queues share one reference-counted frame buffer when a TAP frame
  or address announcement reaches multiple peers. For a 1,514-byte frame sent
  to 120 peers, the frame payload is held once instead of 120 times; queue
  entries hold references to it. Each queue still has its existing 64-entry
  bound and makes its own send/drop decision.
- UDP receive checksum validation reads the datagram in place instead of
  copying it to zero its first four bytes. Duplicate reliable commands and
  fragments copy their payloads only when first admitted to the reorder buffer.
  Retransmission reads the saved command rather than cloning it.
- The shared checksum routine uses a 256-entry static lookup table instead of
  eight bit steps per byte. Encryption and TLV output vectors reserve their
  final size before writing data.

These changes preserve Ethernet payloads, encrypted records, ENET datagrams,
and the order of forwarding decisions. Fixed wire vectors, independent
checksum equivalence tests, local socket tests, and the workspace test suite
cover the affected paths. The memory example is a payload-size calculation,
not a measured process RSS reduction.

### 0.7.0 local microbenchmarks

| Operation | Before | After | Speedup |
| --- | ---: | ---: | ---: |
| 30,000 checksums of 1,400-byte packets | 201.4 ms | 74.6 ms | 2.7× |
| Queue and drain 2,000 1,514-byte frames across 120 peers | 45.0 ms | 6.2 ms | 7.3× |

The medians come from nine alternating runs on Linux x86-64 with optimized
code and host Rust 1.98.1. The checksum comparison uses the prior bitwise
implementation and the current release implementation. The fan-out comparison
uses bounded per-peer channels and compares copied-frame queueing with the
current shared-frame approach. These are isolated local operations, not
measured VPN throughput or process RSS.

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

## Reused forwarding validation and desktop peer views

- TAP forwarding validates Ethernet source, IPv4 checksum, IP addresses, and ARP
  sender once per frame. Each authenticated peer then checks the validated
  destination metadata. Previously those checks were repeated for every peer.
  Invalid frames are dropped before scanning peers or allocating a shared frame.
  Forwarding eligibility, destination rules, queue bounds, and frame bytes stay
  the same in the persistent engine and the bounded diagnostic client.
- Reliable UDP commands reserve their complete header and payload size before
  encoding. ACK commands use an eight-byte stack buffer. Retries traverse the
  pending entries directly, without collecting keys and looking them up again;
  they retain key order, the 400 ms retry interval, and the existing retry limit.
- Desktop peer rows borrow the current snapshot instead of cloning peer names,
  membership sets, server addresses, and details on every repaint. Name and
  status sorting compute each lowercase name once, and an empty search avoids
  lowercase/address conversions. Unicode filtering and stable sort ties remain
  unchanged.

Ethernet envelopes remain uncompressed. Cryptographic algorithms, authentication,
wire formats, and queue limits are unchanged. Regression tests
compare forwarding against the previous rules for valid, mutated, truncated,
and oversized frames. Local UDP tests check exact command/ACK bytes,
fragmentation across sequence wraparound, and retry ordering and deadlines.
The existing fixed cryptographic vectors and workspace tests also pass.

### Local operation measurements

| Operation | Before | After | Speedup |
| --- | ---: | ---: | ---: |
| Validate 30,000 IPv4 broadcast frames for 120 peers | 60.3 ms | 4.6 ms | 13.1× |
| Build 2,000 desktop peer lists, 120 peers, sorted by name | 381.9 ms | 29.1 ms | 13.1× |

These are medians of nine alternating runs on Linux x86-64 with Rust 1.98.1 and
optimized code. The forwarding comparison compiles the previous and current
validation rules together, uses 1,514-byte frames with 20-byte IPv4 headers, and
checks every peer in the same order. The peer-list comparison uses synthetic
peers with Unicode names, two network memberships, server addresses, and
details, with an empty filter. It measures list preparation, excluding painting.

For one 120-peer list sorted by name, a separate allocator-instrumented run
recorded **2,651 → 127 allocation/reallocation calls** and **71,924 → 7,504
bytes** of peak temporary live heap memory. These figures exclude the existing
snapshot and the rest of the application. They describe this synthetic
operation; they do not measure process RSS, total application CPU usage, or
end-to-end VPN throughput.

## Event waits, destination indexing, and buffer reuse

- Established peer workers on Linux wait for socket readiness, queued frames,
  cancellation, or the next transport/keepalive deadline. A coalesced `eventfd`
  notification replaces repeated 2 ms UDP polling and the worker's fixed 20 ms
  queue check. Reliable UDP still retransmits after 400 ms and keeps the same
  retry and liveness limits. Callers that only supply an atomic cancellation
  flag retain a maximum 50 ms wait between cancellation checks.
- The engine waits for TAP readiness and peer/control notifications, with a
  maximum 50 ms delay for external commands and cancellation. Connection
  scheduling is recalculated on state changes or every 50 ms, rather than for
  every traffic event. The scheduler reuses its candidate vector.
- A membership-derived destination index narrows unicast IPv4 and directed ARP
  forwarding to peers with that virtual IP. Duplicate IP bindings retain every
  eligible peer in RID order. Broadcast, multicast, and gratuitous ARP still
  visit all eligible peers. Authentication, MAC, membership, and traffic policy
  checks remain in the forwarding path.
- Peer workers reuse an Ethernet envelope/encryption buffer. Receive processing
  decrypts the owned transport buffer directly. CBC chaining, authentication
  trailers, padding, and encrypted bytes are unchanged. A full UDP send window
  is checked before encryption, so a dropped frame cannot advance the CBC chain.
  Each established worker reserves 1,536 bytes for normal full-size frames;
  this trades a small persistent buffer for fewer transient allocations/copies.
- Linux UDP sends use a stack header and two stack-backed scatter/gather
  descriptors to transmit the existing command buffer. They still produce one
  datagram with the same checksum and bytes,
  without allocating and copying a concatenated datagram for each transmission.
  The diagnostic client also shares immutable queued frames between peers.
- The desktop notice queue replaces consecutive state snapshots with the latest
  snapshot. Phase transitions and operation results remain ordered barriers.
  An inactive UI therefore does not accumulate every periodic state update.

### Local operation measurements

| Operation | Before | After | Improvement |
| --- | ---: | ---: | ---: |
| Sum of worker CPU time, 120 idle UDP peers over 2 seconds | 391.6 ms | 1.2 ms | 99.7% less CPU time |
| Select recipients for 30,000 directed ARP frames, 120 peers | 60.8 ms | 1.2 ms | 50.6× faster |
| Select recipients for 30,000 gratuitous ARP frames, 120 peers | 49.7 ms | 23.2 ms | 2.1× faster |

The idle comparison uses three release-mode runs on Linux x86-64 with Rust
1.98.1, taking the median for each implementation. It compares the previous
2 ms pump/sleep loop with the event wait, using synthetic connected UDP sockets
and per-thread CPU clocks. It excludes authentication, TAP, the engine thread,
and GUI work. Run the explicit local benchmark with:

```bash
cargo test -p openrad-client --lib --release --locked \
  benchmark_event_wait_cpu_with_120_idle_peers -- --ignored --nocapture
```

Recipient-selection figures are medians of nine alternating optimized runs.
Both implementations validate the frame once; the baseline then scans the
worker map, while the new implementation uses the destination index. These
measurements exclude queueing, encryption, and socket transmission. They cannot
be read as whole-application CPU, process RSS, or VPN throughput improvements.

Regression coverage includes fixed cryptographic/wire vectors, checksums at
every header/payload split, forwarding equivalence with duplicate IP bindings,
full queues, retry deadlines, cancellation, and concurrent notifications.
A local UDP load test sends 1,024 full-size Ethernet frames through synthetic
peer channels with a shared test key and checks every payload byte, delivery
order, CBC chaining, and buffer reuse. A separate UI test queues 20,000 snapshots
around operation/phase events and verifies that only the two latest snapshots
and both barriers remain.

## Additional allocation and repeated-work reductions

- Reliable UDP tracks the oldest outstanding data sequence and next retry
  deadline. Send-window checks no longer scan all pending commands; pumps skip
  retransmission scans until a deadline is due and use one clock read per pass.
  In-order messages go straight to delivery, avoiding reorder-map insertion and
  removal. These paths preserve sequence wraparound and acknowledgement rules.
- Fragment reassembly copies each accepted fragment into one bounded message
  buffer and tracks receipt with a bitmap. Completion validates indexed coverage
  before moving that same buffer into delivery. Duplicate fragments do not copy
  their payload again. Completed messages refused by a full reorder queue retain
  their buffer and remain unacknowledged until admission succeeds.
- Incoming tunnel records are validated completely before delivery. Single-frame
  records move their decrypted buffer into the engine queue; multi-frame records
  share one immutable allocation. The queue remains bounded by frame count and
  the record-size limit remains unchanged. A queued frame can retain its entire
  containing record until it is consumed.
- Outbound byte, frame, and drop counters are accumulated locally and published
  once per worker batch. Successful sends are still counted if a later send
  fails. Membership eligibility borrows peer records and network IDs, existing
  peer metadata reuses allocations, and daemon status serializes a shared
  immutable snapshot after releasing its state lock. Desktop JSON parsing takes
  ownership of subtrees instead of cloning them; the local IPC schema is unchanged.
- Desktop lists cache normalized names, formatted addresses, sorted peer IDs,
  and network counts. Row layout measurements allow offscreen rows to reserve
  their space without rebuilding controls. Relevant input changes invalidate
  the affected caches, including filters, sort order, favorites, peer metadata,
  status, language, and layout. Traffic-only updates reuse presentation work.
- Windows TAP writes copy into the persistent overlapped-operation buffer and
  translate Ethernet/ARP MAC fields there before submission. This removes an
  intermediate frame allocation and copy while retaining the existing wire
  identity, bounds checks, cancellation, and completion lifetime.

These changes do not alter MTU, datagram formats, encryption, authentication,
the 400 ms retransmission interval, the 20-attempt retry limit, or peer admission
policy. The earlier measurement tables above describe earlier isolated changes;
they are not measurements of this additional set of optimizations.

Release 1.0.0 validation on Linux x86-64 with Rust 1.98.1 includes the full
default workspace suite (252 passed, six intentionally ignored), formatting,
Clippy with warnings denied, and a release build. Four optional desktop CPU
rendering/screenshot tests also passed, producing eleven synthetic images.
The Debian 12/Rust 1.95.0 distribution build retains a glibc 2.35 minimum.

Windows GNU test targets and release binaries were cross-built. Under Wine
11.11, 241 Rust tests passed and one existing named-pipe first-instance exclusivity
check failed; the detached CLI service suite stalled and remains unverified in
Wine. Passed checks cover reliable UDP, fixed protocol/cryptographic vectors,
owned tunnel buffers, encrypted forwarding/RTT, peer-list rendering, and
transformed overlapped writes. The Windows write test uses a temporary file and
verifies that a failed transform issues no write; it does not exercise an
installed TAP driver. See the [1.0.0 release validation](releases/1.0.0.md) for
complete scope. Live interoperability, native Windows service/driver behavior,
end-to-end throughput, and process RSS still require measurements on real hosts.

# Changelog

## 0.8.0 — 2026-09-29

### CPU and forwarding

- Replaced repeated idle UDP polling and established peer queue checks with Linux socket/event notifications. Queued frames and cancellation wake workers immediately; keepalive, reliable UDP retransmission, and liveness deadlines remain enforced. The engine also waits for TAP and peer/control events, with external commands and cancellation checked within 50 ms.
- Validate outbound Ethernet/IP/ARP headers once per frame and index eligible recipients by virtual IP for unicast and directed ARP. Duplicate IP bindings, RID order, authenticated MAC checks, traffic policy, and group forwarding rules are preserved.
- Recalculate connection scheduling on state changes or every 50 ms, reuse candidate storage, traverse UDP retries directly, reserve command sizes, and encode ACK commands on the stack.

### Memory and desktop

- Reuse each peer's Ethernet envelope/encryption buffer and decrypt received records in their owned transport buffer. Preserve CBC chaining, padding, authentication trailers, and fixed ciphertext vectors. Check the reliable UDP send window before advancing the encryption chain.
- Send Linux UDP headers and existing command buffers as one datagram with scatter/gather I/O, removing the concatenated packet allocation. Share immutable frames between diagnostic-client peer queues.
- Borrow desktop peer views and cache sort keys instead of cloning peer data and repeatedly converting names. Coalesce consecutive queued state snapshots while retaining operation results and phase transitions in order.

### Local measurements

- For 120 idle UDP peers over 2 seconds, summed worker CPU time fell from 391.6 ms to 1.2 ms, a 99.7% reduction. These are medians of three release-mode local runs.
- Selecting recipients for 30,000 directed ARP frames with 120 peers fell from 60.8 ms to 1.2 ms (50.6× faster). Preparing 2,000 name-sorted desktop peer lists fell from 381.9 ms to 29.1 ms (13.1× faster). These are medians of nine alternating optimized runs.
- One 120-peer desktop list used 127 allocation/reallocation calls instead of 2,651, with peak temporary live heap falling from 71,924 to 7,504 bytes (89.6% less). The existing snapshot is excluded.
- These figures measure individual operations, not whole-application CPU, process RSS, or VPN throughput. Each established peer retains a 1,536-byte send buffer to reduce transient allocations. See `docs/performance.md` for methodology.

### Validation

- Added forwarding equivalence, buffer reuse, checksum split, exact UDP command/ACK, sequence wraparound, retry, cancellation, concurrent notification, and snapshot coalescing tests. A local UDP load test checks every byte and delivery order across 1,024 full-size Ethernet frames.
- Passed 147 default workspace tests, formatting, Clippy with warnings denied, and host release builds. The explicit idle CPU benchmark was also run. The privileged TAP test remains opt-in; no new live interoperability session or process-RSS measurement was performed during this release preparation.

## 0.7.0 — 2026-09-28

### Headless CLI and service

- Replaced the short, bounded CLI connection workflow with a persistent per-user service. Closing the terminal no longer closes the server session, peer channels, or nonpersistent Linux TAP interface. The service refreshes memberships and reconnects after server failures with bounded backoff.
- Added `init`, `start`, `status`, `stop`, `networks`, `peers`, `search`, `join`, `create`, `leave`, `delete`, `kick`, `grant-admin`, `revoke-admin`, `retry-peers`, and `retry-interface` commands. `start --no-tap` supports control-only sessions, and `--json` provides machine-readable replies.
- Added a private profile directory, owner-only Unix control socket, single-instance lock, bounded command/reply sizes, service log, and reusable identity and public-modulus storage. Private-network passwords still come from files rather than command arguments and are not saved by the service.
- Correlated network-operation replies with the requesting CLI command, including search results, refusals, timeouts, and disconnected sessions. Network administration uses exact names or IDs and requires explicit confirmation for deletion. Removed the former bounded `run` and report-oriented CLI command syntax; see `docs/cli.md` when upgrading scripts.

### Peer recovery and desktop

- Reduced retry delays to 1.5–4.5 seconds after the first failure, 3–9 after the second, 6–18 after the third, and 12–36 for later failures. Recovery can begin while new peers are connecting, with previously connected channels and incoming offers prioritized.
- Scaled concurrent outgoing retries to 16, 32, or 48 according to backlog, starting attempts 200, 100, or 50 milliseconds apart. Recent high failure rates reduce retry pressure; due retries reserve setup capacity without blocking incoming offers or all new peers.
- Added failed/refused, offline, active-retry, and queued-retry counts to the desktop network page. Desktop redraws no longer clone the sidebar and network-selector lists.

### Memory and CPU

- Shared outgoing Ethernet frame payloads across peer queues during fan-out and reused the TAP read buffer. A 1,514-byte frame queued for 120 peers avoids about 176 KiB of duplicate payloads; 64 such queued frames avoid about 11 MiB. These are payload-size calculations, not measured process RSS.
- Validated UDP checksums without copying datagrams; deferred payload copies for duplicate reliable commands and fragments; retransmitted from saved commands without cloning them.
- Replaced eight checksum bit steps per byte with a 256-entry lookup table. Reserved final buffer sizes for encryption and TLV output.
- On Linux x86-64, nine alternating optimized local microbenchmark runs had these medians: 30,000 checksums of 1,400-byte packets took 201.4 ms before and 74.6 ms after (2.7× faster); queuing and draining 2,000 1,514-byte frames across 120 peer channels took 45.0 ms before and 6.2 ms after (7.3× faster). These measure isolated operations, not end-to-end VPN throughput.

### Validation

- Added headless persistent-service, command-correlation, adaptive-recovery, shared-buffer, and checksum-equivalence tests using synthetic data and local sockets. Fixed wire vectors still pass.
- Passed workspace tests, formatting, Clippy with warnings denied, and a Linux release build. The privileged TAP test remains opt-in; live NAT and process-RSS measurements were not performed.

## 0.6.0 — 2026-09-28

### Fixed

- Fixed private-network authentication compatibility when joining or creating networks with the official client.

### Desktop

- Added settings for the maximum reconnect attempts and initial retry delay, with bounded exponential backoff.
- Added startup page, traffic display and units, offline-peer visibility, peer sorting, recent activity count, and developer diagnostic display preferences.
- Added Save, Discard, and Restore defaults actions for preferences. Existing settings files receive defaults for the new fields.

### Validation

- Confirmed private-network joins through both development and release CLIs using the test network, followed by a fresh membership check.
- Passed the default workspace tests, formatting, Clippy, and the Linux release build. The privileged TAP test remains opt-in.

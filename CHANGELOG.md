# Changelog

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

- Private-network joins now authenticate with networks created by the official Radmin VPN client. The network password's UTF-8 wire representation includes its terminating NUL byte; the same correction applies when creating a private network.

### Desktop

- Added settings for the maximum reconnect attempts and initial retry delay, with bounded exponential backoff.
- Added startup page, traffic display and units, offline-peer visibility, peer sorting, recent activity count, and developer diagnostic display preferences.
- Added Save, Discard, and Restore defaults actions for preferences. Existing settings files receive defaults for the new fields.

### Validation

- Confirmed private-network joins through both development and release CLIs using the test network, followed by a fresh membership check.
- Passed the default workspace tests, formatting, Clippy, and the Linux release build. The privileged TAP test remains opt-in.

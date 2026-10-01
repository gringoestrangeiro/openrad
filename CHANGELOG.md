# Changelog

## 1.0.0 — 2026-10-01

This release includes all local changes since remote `main` at
`8d793b44` (v0.9.5), with fresh Linux and Windows binaries. The
[file-by-file inventory](docs/releases/1.0.0-changes.md) accounts for every
changed and added file; [release notes](docs/releases/1.0.0.md) document upgrading,
packages, checks, and limitations.

### Shared desktop and CLI service

- Move the per-user daemon into the shared library. Both frontends attach to one engine, identity, membership roster, peer set, and TAP session per profile; either frontend may start first. Desktop status polling and commands run off the UI thread, and closing the desktop leaves the service running. Desktop Disconnect and CLI Stop stop the shared session.
- Resolve both frontends to the same default directory, retaining an existing desktop profile when the canonical CLI profile has not been initialized. Keep existing identities in their credential store or private file. Reject initialization over a saved profile, stored identity, or running service, and prevent conflicting identity imports.
- Serialize simultaneous service launches with a startup lock; coordinate service startup and identity changes with separate profile/service locks. Persist service reconnect, relay, and traffic-allowlist preferences with atomic private writes. Status reports the process ID and service preferences; snapshots and transport paths support deserialization.
- Add CLI `ping PEER_RID`, `rename NODE_NAME`, and `force-relay true|false`. Validate nonblank names, control characters, and protocol length; renaming keeps the RID, address, credentials, and memberships, reconnecting only to advertise the new name. Allow offline rename/policy changes and explicit restart after reconnect attempts are exhausted.
- Apply saved reconnect enablement, 1–10 retry attempts, and 1–30 second base delay in the service, doubling delays to a five-minute cap. Relay or traffic-policy changes cancel and finish existing workers before restarting.
- Bound local requests with aggregate deadlines, including nonblocking Linux Unix-socket connects when the listen backlog is full, incremental bounded reply reads, and Windows named-pipe connection/message deadlines. Recover a stale Unix socket only while holding the service lock; refuse to delete a non-socket file.

### Desktop networks, search, and layout

- Add persistent favorites for public and private networks. Pin favorites in discovery, the sidebar, and joined-network selectors; sort favorite ties by member count and then name. Preserve search filters, server order for non-favorites, and roster/role count fallbacks.
- Add an Auto join page with public/private selections, exact-name entry, selecting already joined networks, clearing selections, and per-network password fields. Validate all required passwords before submission, skip existing/pending memberships, and show independent joining, queued, success, approval, failure, or interrupted results.
- Save, update, load, and delete up to 32 named configurations containing up to 128 networks each. Persist network names/access types/favorites separately in a bounded, validated, atomically written private `network-preferences.json`; keep corrupt data available for repair. Favorites and configurations survive restarts and identity replacement. Passwords stay in zeroized memory, are cleared on loading/identity changes, and are never saved; loading or launching does not submit joins automatically.
- Pace overlapping joins at least 50 ms apart and correlate each result with its row. A refusal does not cancel other joins. Interrupt queued work on disconnect, identity change, or frontend close; already submitted requests may still take effect remotely.
- Debounce live public-network searches for 300 ms, browse when discovery opens, keep typing enabled during requests, filter visible results immediately, discard stale query replies/failures, and retain pagination for the current query.
- Replace the eight-network sidebar limit with a scrollable list and reserved device/interface/version footer. Make the sidebar resizable and clamp it to smaller windows. Scroll joined-network selectors horizontally, truncate long labels with full-name hover text, center the connection-card icon/title/caption, and keep connection actions and timing readable at narrow widths and translated scales.
- Cache peer names, normalized filters, formatted addresses, selected/sorted rows, network membership counts, favorites, public results, and Auto join ordering. Reuse cached work for traffic-only snapshots. Measure peer row heights and reserve offscreen space without rebuilding controls; invalidate rows for metadata, role, ping, language, scale, style, width, or display-setting changes, keeping open menus active.

### Peer RTT, relay policy, and release notices

- Measure peer RTT with correlated authenticated tunnel keepalives and distinct sequences/request tokens. Keep normal forwarding active, accept only the intended reply, and expire probes after 3000 ms. Show translated RTT, pending, and failure states per peer, with a frontend deadline protecting against stalled control requests or stale replies. No ICMP privileges are needed.
- Enforce relay-only policy for outgoing and incoming channels: suppress direct TCP/UDP requests, advertisements, mapping discovery, listeners, and attempts, and use relay-only incoming setup. Save the policy through either frontend and expose it in snapshots/settings.
- Check GitHub's latest stable release at launch and hourly using semantic version precedence. Ignore drafts/prereleases/older versions and reject untrusted release links. Use the system curl client with HTTPS-only redirects, certificate verification, five-second connection/15-second transfer limits, and a 1 MiB response cap; hide its console on Windows. Keep a discovered notice through navigation, reconnects, and failed checks until explicitly dismissed for that launch.

### Provisioning and identity reset

- Make DNS waits cancellable and bounded and attempt resolved TCP addresses within the connection budget. Retry transient connection/authentication-transport failures before registration up to three times per server with one-/two-second delays and a 90-second aggregate provisioning budget; preserve bounded redirects. Fail authentication/protocol rejection immediately, and never automatically resend registration after an ambiguous lost response.
- Cancel queued connect work before reset, acquire the profile lock without indefinite waiting, and retry service shutdown for up to ten seconds before obtaining exclusive stopped-service ownership. Keep old service status from hiding a reset failure behind Connecting.
- Publish provisioning/redirect/retry/registration/save progress in the UI and retained activity. Retry replacement storage up to three times, preserve the old identity until saving succeeds, retain an issued unsaved replacement for storage-only retries, and finish a successful reset disconnected. Protect pending replacements during close handling and expose an identity-reset log-path action.
- Record elapsed stages, attempts, full error chains, shutdown retries, and pending-save state in early desktop/CLI diagnostics while excluding credentials, keys, passwords, and packet contents.
- Route concurrent private-network password replies using request IDs/authentication sequences. Retain compatibility with one legacy uncorrelated private join; reject ambiguity before applying a proof to multiple joins. Timed-out membership changes still force reattachment to reload authoritative membership.

### Transport allocation and Unix resource limits

- Cache the oldest unacknowledged UDP data sequence and next retry deadline, skip premature retransmission scans, capture one clock per due pass, and bypass reorder-map insertion for in-order delivery. Preserve wraparound, first-arrival precedence, checksum/ACK admission, 400 ms retries, and the 20-attempt limit.
- Reassemble up to 128 fragments into one bounded payload allocation with indexed coverage metadata and a receipt bitmap. Avoid duplicate payload copies, validate gaps/overlaps/total length before delivery, move the completed buffer, and retain unacknowledged completed data while reorder admission is full.
- Validate all Ethernet envelopes in an incoming decrypted record before delivering any frame. Move single-frame buffers and share multi-frame buffers across owned frame views in the runtime and diagnostic client, preserving lengths, offload rejection, keepalive parsing, and bounded queues. A queued frame retains its containing record until consumed.
- Batch sent-byte/frame/drop counter publication, including successful sends before later I/O failure. Borrow eligible membership records/IDs, reuse existing peer metadata allocations, serialize shared immutable service snapshots outside the state lock, and move desktop JSON subtrees into parsers instead of cloning them.
- Translate Windows Ethernet/ARP MAC fields directly in the persistent overlapped-write buffer. Validate frame bounds before submission, issue no write after a failed transform, and preserve kernel-buffer ownership through completion/cancellation.
- Raise the Unix soft open-file limit to at least 8192 at desktop/CLI startup, capped by the unchanged hard limit. Preserve higher inherited values, pass the limit to the service, and log restrictions/errors without preventing startup or requiring sudo.

### Localization, tests, documentation, and distribution

- Add 100 complete English/Portuguese/Russian/Vietnamese catalog entries for the new UI, commands, validation, provisioning, IPC, and release errors. Extend catalog audits to release/HTTP modules and recognize intentionally English diagnostic records.
- Add regressions for shared-session startup in either order/concurrently, offline rename and preserved secrets, preference persistence, aggregate IPC/backlog deadlines, inherited descriptor limits, encrypted RTT correlation/timeouts, relay-only incoming/outgoing behavior, overlapping public/private joins, refusal isolation/legacy ambiguity, provisioning retry safety, reset cancellation/storage/shutdown/stale sockets, owned tunnel frames, UDP window/reassembly/order/deadlines, counters after errors, JSON ownership, cached layouts, favorites/configurations, live search, and release dismissal. Add a Windows transformed-write test using a temporary file.
- Extend offscreen software rendering into a reusable helper and add synthetic feature, many-network, and identity-reset screenshots. Update all six platform/usage/architecture/performance guides, README, and repository guidelines; ignore local network/service preference and lock files. Restore changelog history for 0.1.0–0.5.0 from the published releases.
- Synchronize both workspace package versions and lockfile at 1.0.0; add library keyring and semver dependencies. Include complete release/change guides and five public synthetic screenshots in Linux, Windows portable, and installer package allowlists; preserve root Windows guide/validation/Credits links and include the MIT license beside ZIP documentation. Publish fresh Linux and Windows archives, offline setup, dependency/standard-library notices, build/import provenance, corresponding signed-driver source/licenses, and SHA-256 checksums.
- Validation and platform limitations are recorded in the [release notes](docs/releases/1.0.0.md). Existing performance numbers measure prior isolated changes; this release does not claim new end-to-end throughput, process-RSS, or native Windows driver measurements.

## 0.9.5 — 2026-09-30

### Windows Radmin VPN migration

- Automatically recover when the enabled official Famatech Radmin VPN adapter occupies `26.0.0.0/8`: run a temporary local SYSTEM task, stop the `RvControlSvc.exe` service, terminate remaining `RvControlSvc.exe` processes, and disable only the selected official adapter.
- Recheck active interfaces and continue the same desktop/CLI connection attempt. Bound recovery waits and remove the temporary task on success or failure. Keep unrelated VPN and stale OpenRad conflicts as errors; ignore addresses retained on administratively disabled interfaces.
- Use an immutable embedded worker and Windows' built-in PowerShell/Task Scheduler. Keep the application's existing user profile, leave Radmin's startup setting unchanged, and document how to switch back.
- Add synthetic Rust and PowerShell recovery regressions. Publish newly compiled Windows x64 applications/installer and reuse the exact 0.9.0 Linux archive; its Linux binaries continue reporting 0.9.0.

## 0.9.0 — 2026-09-30

### Experimental Windows support

- Add Windows x64 desktop and persistent CLI support through the shared VPN engine and the official TAP-Windows6 Layer 2 driver. Use exclusive overlapped packet I/O, driver MAC translation, media controls and IP Helper session-address/route cleanup; preserve Linux's short-lived sudo helper.
- Add an offline setup EXE with both applications, the unchanged signed TAP-Windows6 9.27.0 package, complete corresponding driver source/notices and shortcuts. Create/repair the dedicated adapter, reuse ready installations, open the desktop automatically, and support `--no-launch` for CLI use.
- Fix adapter GUID comparisons for Windows NetAdapter string values and use device-specific SetupAPI driver keys instead of scanning protected unrelated registry keys. Persistent CLI control uses current-user protected named pipes and profiles.
- Prefer native Direct3D 12 hardware rendering, then Windows WARP CPU rendering, then OpenGL. Preserve explicit renderer selection and prevent graphics retries after App creation from starting a second VPN backend. Use Windows' system FXC compiler without additional graphics DLL bundles.
- Add early desktop/CLI/daemon diagnostics, a desktop child crash monitor, Rust panic backtraces, best-effort native exception module/address logging, backend credential-store checkpoints and individual TAP setup stages. Keep credentials and packet contents out of logs.
- Add an explicit local SYSTEM diagnostic launcher with isolated profiles; verify the official Microsoft PsExec signature and present its license. Normal setup stays offline and ordinary administrator startup remains the default.
- Windows support is experimental. It has been tested only on Windows 10 so far, where the tester reports it working perfectly in their setup. Windows 11 and more hardware/network combinations need testing and issue reports before stable Windows support in 1.0.0.

### Four-language desktop and CLI

- Add complete English, Portuguese, Russian and Vietnamese catalogs, automatic system-language detection, saved desktop language preferences and `--language` overrides for both binaries.
- Translate dialogs, validation, peer/transport states, retained activity, command help and CLI output while preserving names, paths, protocol values and machine-readable JSON.
- Embed Noto Sans for Cyrillic and Vietnamese accents; include the font's SIL Open Font License.

### Documentation and releases

- Publish separate Linux x86-64 and experimental Windows x64 packages, an offline Windows setup EXE, source/license notices and SHA-256 checksums.
- Add native Linux and Windows build instructions to the README, Windows diagnostic/issue-report guidance for 1.0.0 and a macOS roadmap note: support is planned, but development has not started.
- Record the tester's Windows 10 comparison on the same network with 76 peers: official Radmin VPN 176 MB GUI + 23 MB service (199 MB total), OpenRad 41 MB client; idle CPU 1–4% versus 0–3%. These are observations from that setup; further CPU/memory improvements are planned.

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

## 0.5.0 — 2026-09-27

- Retry refused peers with bounded per-peer backoff of 7.5–22.5, 15–45, then 30–90 seconds. Wait five seconds for initial setup to settle; pace retries 2–4 seconds apart with four outgoing retries at once, while incoming offers can bypass the wait. Manual retries preserve failure history.
- Give direct TCP/UDP four seconds of preference over relay, extending to eight seconds after a relay ticket for late direct attempts; start relay sooner when direct options are exhausted. Apply the same preference to incoming channels except explicit relay-only diagnostics.
- Announce the TAP address with a 42-byte gratuitous ARP reply when authenticated channels become ready and when the TAP is recreated. Apply the desktop Ethernet validation and explicit traffic allowlist to CLI forwarding, preserving source IP/MAC and membership checks.
- Publish Linux x86-64 binaries built with Rust 1.95 on Debian 12. Development regressions passed before the final timing adjustment; they were not rerun for that adjustment. [Published release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.5.0).

## 0.4.0 — 2026-09-27

- Increase outgoing handshake capacity to 64 with 80 total slots and reserved incoming capacity. Discover UDP mappings concurrently through both authenticated servers, requiring agreement, independently of direct/relay setup.
- Start direct transports as candidates arrive, with two TCP workers so an unresponsive address does not hold up others. Start relay 750 ms after its ticket; the first fully authenticated peer/service connection wins.
- Prioritize first attempts over retries within traffic priority, start initial transient retries after 500–625 ms, enlarge bounded offer/advertisement queues, and promptly cancel superseded Linux TCP connects.
- Accept member-removal/status events containing mandatory subject and optional source identifiers with the same tag without disconnecting or confusing their roles; retain strict singleton checks elsewhere.
- Pass 111 headless tests, formatting, and Clippy; publish Linux binaries requiring glibc 2.35+. [Published release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.4.0).

## 0.3.0 — 2026-09-27

- Keep attachment heartbeats running under queue pressure and separate packet/counter work from lifecycle events. Preserve established peers across equivalent online roster states and consume queued UDP ACKs before timeout checks.
- Retry transient peer failures with individual staggered backoff. Keep search timeout, full command queues, and TAP read failures from unnecessarily ending the whole session; reconnect after uncertain membership mutations.
- Add persistent session/peer/transport/retry/roster/heartbeat/queue diagnostics rotating over four 4 MiB files, without passwords, keys, or payloads. Show peer failure details in Recent activity and add Copy connection log path.
- Pass 99 headless tests, formatting, and Clippy; publish Linux binaries requiring glibc 2.35+. [Published release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.3.0).

## 0.2.0 — 2026-09-27

- Add password-protected private-network creation and joining to the desktop and CLI, private membership views, administrator-authorized member removal and permission grants/revocations, and owner-authorized network deletion.
- Use passwords only during authenticated operations and retain OS credential storage, public discovery/joining, and the 0.1.0 transport/I/O improvements.
- Publish experimental Linux x86-64 binaries built with Rust 1.95 on Debian 12, requiring glibc 2.35+, with guides, license, build information, and checksums. [Published release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.2.0).

## 0.1.0 — 2026-09-27

- Release the native Linux desktop and headless CLI with public networks, direct TCP/UDP, and relay transport; private-network management arrived in 0.2.0.
- Run up to 24 concurrent outgoing handshakes with 32 total slots to retain incoming capacity. Use Linux poll readiness and vectored TCP writes, reduce Ethernet allocations, avoid redundant membership updates, and omit JSON serialization for disabled reports.
- Preserve protocol, cryptography, authentication, UDP formats, and deadlines. Seven-run local medians improved 100,000 idle socket checks from 37.97 to 21.66 ms, 200,000 Ethernet encodes from 12.55 to 5.26 ms, and 10,000 localhost TCP frames from 27.65 to 14.68 ms; these measure local overhead.
- Publish experimental Linux x86-64 binaries built with Rust 1.95 on Debian 12, requiring glibc 2.35+, with setup/license/build/performance documentation and checksums. [Published release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.1.0).

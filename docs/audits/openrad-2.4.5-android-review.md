# OpenRad 2.4.5 development: Android and Rust follow-up

Date: 2026-11-19. Review baseline: e9bc6f9 (Rust follow-up) and the Android
2.4.0 code present on that branch. The development scope now includes Rust
and Android. Android package metadata remains 2.4.0 pending release approval.
Python, Go and Minecraft are outside the implementation scope. This is an
engineering checkpoint, not a release approval.

## Scope and method

Reviewed the Android control/session loop, peer worker lifecycle, protocol
decoders and roster, identity codec, repository, catalog and batch operations,
VpnService ownership, TUN attachment and descriptor lifetime, manifest,
dependency configuration and workflow. Compared relevant invariants against
the earlier Rust audit. Baseline Android tests, lint and debug packaging ran
before edits. The Android integration fixture uses real loopback sockets and
a synthetic coordinator identity, not an external VPN service.

## Android findings and implementation

| ID | Priority | Finding and correction | Evidence / residual risk |
| --- | --- | --- | --- |
| A01 | High | Identity replacement was handled like an ordinary control error, allowing retry by a displaced identity. Decode replacement reason and halt without replacing stored credentials. | Loopback integration test exercises in-flight search, stale join and preserved identity. Official server behavior remains unqualified. |
| A02 | High | Queued commands, peer advertisements and old membership could survive reconnection. Drain active and pending commands, wipe passwords, clear per-attachment state and reject stale command generation. | Engine integration and catalog regressions. A dispatched operation can still have an unknown remote outcome; no replay is attempted. |
| A03 | High | Same-VIP reconnect and stale service destruction could reuse or detach the wrong interface. Track host/session/service ownership, serialize engine transitions, bind both TUN directions to their attachment and reject stale packets inside the engine. | Engine stale-session regression, build and instrumented APK compilation. Actual Android TUN/FD runtime validation remains required. |
| A04 | High | Stop could acknowledge early when the control thread had already left the running state, permitting two engine lifetimes. Join even after terminal failure, cancel workers and refuse a new engine until the previous one stops. | Engine lifecycle tests and replacement regression. Blocking native I/O and process/service races still need device stress tests. |
| A05 | High | Membership changes were incremental in-place without a shared admission ceiling. Apply to a temporary roster; commit only on complete validation; bound peers (1024), networks (256), links (16384) and UTF-8 text (1 MiB). Bound worker count. | Rollback and budget regression. Measure actual heap/CPU at the ceiling on low-memory devices. |
| A06 | Medium | Stored identity input lacked corresponding size, node-name and address validation. Reject invalid identity records over 64 KiB and validate the node name and numeric IPv4 endpoint. | Codec regression. Real Keystore failure/recovery and upgrade tests pending. |
| A07 | Medium | Strict text decoding could accept control characters or reject valid empty text. Strict decoding validates controls; display decoding replaces invalid text while preserving roster usability. | Protocol tests; legacy text interchange still needs real peer qualification. |
| A08 | Medium | Late catalog replies, duplicate pagination, repeated cursors and stale results across sessions. Introduce a single catalog state with session/request correlation, deduplication, progress checks and 4096-result ceiling. | PublicCatalogTest covers out-of-order responses, repeated cursor, cancellation and bound. |
| A09 | Medium | Concurrent batch joins and missing private-network passwords could dispatch unintended commands. Serialize batches, use structured concurrency, bind commands to session, fail missing private password and wipe input on completion/cancellation. | Full JVM suite and engine regression; dedicated Compose UI interaction/device validation pending. |
| A10 | Medium | RTT probe could be submitted during peer shutdown, leaving a callback unresolved. Recheck state after atomic admission; isolate callback failures from cleanup. | Full engine suite; sustained cancellation stress remains pending. |
| A11 | Medium | Android CI did not require lint or compile instrumentation tests and used mutable action tags. Pin the actions, restrict permissions and expand the checks. | Local equivalent passes; new GitHub Actions run is not yet available. |
| A12 | Medium | Android dependency/lifecycle maintenance remains necessary. | Lint reports 33 warnings: 11 Gradle dependency, 11 plural resource, 6 unused resources, 1 each old target API, app bundle locale changes, data extraction rules, obsolete SDK check and monochrome launcher icon. Do not suppress warnings or perform blind dependency/target upgrades. |

## Android qualification findings (open)

- Manifest declares a non-exported VPN service with BIND_VPN_SERVICE and
  disables backup. These declarations are not proof of device-level
  confidentiality, permission behavior or identity recovery.
- Validate encrypted storage on real devices, including locked/unavailable
  Keystore, app update with the expected signing identity and interrupted
  identity writes. Plan migration away from the aging security-crypto API
  without silently discarding existing credentials.
- Validate target SDK and dependency upgrades separately; exercise Android
  foreground-service rules, permission revocation, notification handling,
  process death, Wi-Fi/mobile switching, airplane mode, sleep/Doze and
  rapid connect/disconnect/rename. Confirm actual TUN read/write, descriptor
  cleanup, routes, resource use and traffic blocking.
- Run instrumented tests on an isolated emulator and representative physical
  Android devices, then official-client and mixed Rust/Android integration
  with disposable authorized identities. Perform long-run resource and
  throughput measurements. Add dedicated batch/RTT UI race and lifecycle
  stress regressions.
- Control heartbeat compatibility and per-attachment relay-objection state
  need a separate protocol review. Do not assume the Rust implementation
  automatically fixes the Kotlin implementation.

## Rust follow-up

The prior Rust findings remain tracked in openrad-2.4.5-status.md and
openrad-2.4.5-independent-followup.md. This round adds a bounded in-memory
peer quality history to runtime Snapshot, exposed in the daemon status JSON
(openrad --json status). Retain at most 16 events per known peer for the
current session: authenticated transport establishment, disconnection,
correlated RTT replies and actual pending-probe timeouts. No additional
probes, IP addresses or credentials are recorded. Elapsed times are monotonic
relative to the engine start. Remove history with roster removal; session
replacement naturally creates a new snapshot. Close an outstanding probe
when its channel closes instead of leaving it pending until the timeout.
Older snapshots without peer_quality deserialize with an empty history.
The regression checks eviction, unknown-peer rejection, JSON round trip and
roster removal. Desktop chart/tooltip, long-term persistence, loss-rate
estimation and incremental status are not implemented by this feature.

Linux fuzz campaigns and Miri, native Linux/Windows TAP and cleanup, official
Radmin compatibility, soak tests, full unsafe/cryptography review and engine
path consolidation remain open. Do not count previous portable packaging or
Windows headless tests as native VPN or cross-platform qualification.

## Validation

- Android baseline: 160 passed, 1 ignored (161 total); debug APK and lint passed.
- Post-change Android checkpoint: 169 passed, 1 ignored (170 total);
  debug APK, lint and instrumentation APK compilation passed. No instrumented
  tests were executed: disposable AVD creation failed because the installed
  Android 35 image lacks devices.xml.
- Final Rust workspace: 388 passed, 0 failed, 2 ignored. Clippy for all
  targets with warnings denied and formatting passed.
- Android final repeat after the TUN session admission and probe correction:
  169 passed, 0 failed, 1 ignored; lint and both APK builds passed.
- actionlint on the Android workflow, check-sync.py and git diff --check passed.
- Implementation commits: 597360b (Rust) and b635366 (Android).
- The earlier Rust optimized build and package are not rebuilt from these
  commits; debug Android APK is not a signed release artifact.
- No published APK, signing changes, version bump, tag or remote push.

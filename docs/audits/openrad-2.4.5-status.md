# OpenRad 2.4.5 Rust audit implementation

Development checkpoint based on the 2.4.0 audit at `d4314fa`. Not a release qualification. Only the Rust CLI, daemon, desktop, platform code and their tests/workflow are in scope. Other clients and the published 2.4.0 must remain unchanged.

## Findings

| ID | Implementation state | Evidence or remaining work |
| --- | --- | --- |
| R01 | Implemented | Validate imported identity and modulus before reserving a profile; retain profile if remote registration outcome is uncertain. `invalid_import_does_not_reserve_the_profile`. |
| R02 | Partial | TCP peer inactivity deadline; control deadline enabled once heartbeat replies establish support. Silent legacy servers and native interoperability still require qualification. |
| R03 | Implemented | Typed disconnect reason prevents automatic retry after identity replacement. Validate coordinator behavior in interoperability matrix. |
| R04 | Implemented | Read current reconnect policy when waiting and wake on changes; validate policy changes during backoff. |
| R05 | Implemented | Monotonic command deadline and atomic cancellation before dispatch; after dispatch distinguish unknown result. |
| R06 | Implemented | Relay objection tracking per attachment; mark only after successful write. |
| R07 | Partial | Cancel peers before waiting for TAP; cooperative cancellation and bounded PowerShell child/job. Sleeping-process regression. External scheduled-task reconciliation and actual driver cancellation remain unqualified. |
| R08 | Implemented, Linux validation pending | Capture generation/VIP/interface together, verify before queueing and adoption, expiry, validate descriptor address. Test real sudo/TAP and generation changes on Linux. |
| R09 | Open | Explicit per-resource cleanup result, ownership-aware metric restoration, and partial cleanup qualification still needed. |
| R10 | Partial | CLI stop returns failure if endpoint remains; terminal retry must observe interface readiness. Exercise all stop and retry variants. |
| R11 | Implemented | Common 128 KiB read/write budget, pre-bind validation, atomic persistence. Max-size regression and rejection preserving previous revision. |
| H01 | Mitigated | Separate disconnected action channel from interactive shutdown retry; test failed storage during final sender disappearance. |
| H02 | Mitigated | Persist reset-registration intent before remote login; retain identity on uncertain outcome. End-to-end lost-reply test pending. |
| H03 | Mitigated | Rearm Windows TAP after malformed frames, classify recoverable InvalidData. Native driver test pending. |
| H04 | Open | Slow/stalled filesystem during diagnostic shutdown remains unqualified. |
| S01 | Implemented | Validate source VIP and Ethernet/ARP MAC for gratuitous ARP without removing legitimate fanout; forwarding and engine regressions. |
| S02 | Partial | Check named-pipe server PID, SID and executable path before sensitive transmission. Same-user compromise of writable installations and elevated trust boundary require additional validation; path alone is not publisher authentication. |
| S03 | Open | Effective ACL and reparse-point qualification of elevated task executable. |
| S04 | Partial | rustix 1.1.6, tokio 1.53.3, clipboard-win 5.4.3; local advisory snapshot reports zero vulnerabilities and four warnings (three unmaintained zbus-family dependencies, yanked async-executor). Linux and clipboard qualification pending. |
| S05 | Implemented, CI pending | Rust workflow actions pinned by verified upstream SHA; dedicated dependency audit job. No remote CI run initiated. |
| N01 | Implemented | Serialize private joins until response correlation is established; public join concurrency retained. Loopback tests. Native legacy interoperability pending. |
| N02 | Implemented | IPC ACK uses remaining budget and does not turn a known reply into an unknown command outcome. |
| P01 | Open | Aggregate roster/worker memory budget and bounded incremental snapshots. |
| P02 | Open | Bounded TAP drain and measured cancellation latency under backpressure. |
| P03 | Open | Move favorites/storage writes off render thread with revisioned results. |
| U01 | Mitigated | Prevalidate server modulus before stopping active service. Exercise persistence failure and previous endpoint recovery. |
| U02 | Mitigated | Field patches and merge unsaved desktop edits with current service state. Full GUI/CLI integration qualification pending. |
| U03 | Open | Catalog response generation and search ID validation. |
| U04 | Mitigated | Distinguish unavailable identity from absent identity in storage/backend and UI. Cofre recovery qualification pending. |
| D01 | Partial | Deadline and generation types clarify selected boundaries; engine decomposition remains. |
| D02 | Open | Embedded client and daemon execution paths not consolidated. |
| D03 | Implemented, Linux validation pending | Parent directory sync on atomic save. Fault-injection after rename pending. |
| D04 | Open | Process-global control port remains for embedded multi-session callers. |

## Additional changes

Windows MSVC builds use the SDK resource compiler for the application icon; GNU builds retain windres. Missing resource compiler now fails the build instead of silently shipping an iconless executable. CLI/desktop package versions set to 2.4.5. Changelog marks this version as unreleased.

## Remaining release gates

- Complete release build and executable icon resource verification; run the Rust workflow on a development branch only after authorization.
- Windows/Linux real TAP, official Radmin client and mixed-version interoperability, disconnect/reconnect, route cleanup and concurrent GUI/CLI use, visual/accessibility checks.
- 24–72 hour controlled soak plus roster scaling, memory, handle and latency measurements.
- Implement the remaining findings before claiming the audit complete. Diagnostic bundle, peer-quality history and incremental status are not yet implemented.

## Windows checkpoint validation

- `cargo check --workspace --all-targets --locked --jobs 1`: passed.
- `cargo test --workspace --locked --jobs 1`: 381 passed, zero failed, one ignored. Import and preference regressions, pipe integration, cancellation, protocol fixtures, and desktop localization covered.
- `cargo clippy --workspace --all-targets --locked --jobs 1 -- -D warnings`: passed before the final catalog-only translation additions.
- `cargo fmt --all -- --check` and `git diff --check`: passed.
- `python scripts/check-sync.py`: passed with 962 Rust catalog keys.
- `cargo audit --no-fetch` against the local RustSec snapshot: zero classified vulnerabilities, four warnings.
- MSVC resource compiler generated the icon resource; a release executable icon check is still pending.

The local Windows checks are not a substitute for Linux or real TAP qualification. The current development commit is not a release candidate approval.

# OpenRad 2.4.5 Rust audit implementation

Development checkpoint based on the 2.4.0 audit at `d4314fa`. Not a release qualification. This checkpoint records the original Rust scope. Subsequent development has expanded to Android; see the combined follow-up audit linked below. The published 2.4.0 remains unchanged.

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
| R09 | Partial | Windows TAP cleanup checks resource identity/ownership, restores unchanged settings and records failures. Real driver rollback and durable stop confirmation remain unqualified. |
| R10 | Partial | CLI stop returns failure if endpoint remains; terminal retry must observe interface readiness. Exercise all stop and retry variants. |
| R11 | Implemented | Common 128 KiB read/write budget, pre-bind validation, atomic persistence. Max-size regression and rejection preserving previous revision. |
| H01 | Mitigated | Separate disconnected action channel from interactive shutdown retry; test failed storage during final sender disappearance. |
| H02 | Mitigated | Persist reset-registration intent before remote login; retain identity on uncertain outcome. End-to-end lost-reply test pending. |
| H03 | Mitigated | Rearm Windows TAP after malformed frames, classify recoverable InvalidData. Native driver test pending. |
| H04 | Mitigated | Diagnostics shutdown waits at most one second for the writer and reports a warning if events may be lost. Stalled filesystem qualification remains pending. |
| S01 | Implemented | Validate source VIP and Ethernet/ARP MAC for gratuitous ARP without removing legitimate fanout; forwarding and engine regressions. |
| S02 | Partial | Check named-pipe server PID, SID and executable path before sensitive transmission. Same-user compromise of writable installations and elevated trust boundary require additional validation; path alone is not publisher authentication. |
| S03 | Implemented, qualification pending | Validate administrator-controlled ownership, write ACLs and reparse points for installed executable, sibling CLI, DLLs and ancestors. Ordinary-user writable-path regression passed; real elevated task qualification pending. |
| S04 | Partial | rustix 1.1.6, tokio 1.53.3, clipboard-win 5.4.3, async-executor 1.14.1. Follow-up advisory snapshot reports zero classified vulnerabilities and three unmaintained zbus-family warnings; Linux and clipboard qualification pending. |
| S05 | Implemented | Rust workflow actions pinned by verified upstream SHA; dedicated dependency audit job. The first development-branch Rust CI run passed. |
| N01 | Implemented | Serialize private joins until response correlation is established; public join concurrency retained. Loopback tests. Native legacy interoperability pending. |
| N02 | Implemented | IPC ACK uses remaining budget and does not turn a known reply into an unknown command outcome. |
| P01 | Partial | Transactional roster admission with 1,024 peers, 256 networks, 16,384 links and 1 MiB of text; bounded active and retiring workers and lifecycle queue. Overflow regression passed. Incremental snapshots and resource measurements remain pending. |
| P02 | Implemented, qualification pending | Five-millisecond / 512-frame drain budget and asynchronous Windows overlapped write polling; count transmitted frames after completion. Real driver backpressure measurements pending. |
| P03 | Implemented | Single asynchronous preference writer, coalesced desired state, saved-state rollback on failure, close waits for pending writes. Success and failure regressions passed. |
| U01 | Mitigated | Prevalidate server modulus before stopping active service. Exercise persistence failure and previous endpoint recovery. |
| U02 | Mitigated | Field patches and merge unsaved desktop edits with current service state. Full GUI/CLI integration qualification pending. |
| U03 | Implemented | Search responses carry request ID and session generation; invalidate catalog across server/session changes and reject stale responses. Regression passed. |
| U04 | Mitigated | Distinguish unavailable identity from absent identity in storage/backend and UI. Cofre recovery qualification pending. |
| D01 | Partial | Deadline and generation types clarify selected boundaries; engine decomposition remains. |
| D02 | Open | Embedded client and daemon execution paths not consolidated. |
| D03 | Implemented, Linux validation pending | Parent directory sync on atomic save. Fault-injection after rename pending. |
| D04 | Implemented, qualification pending | Explicit per-session endpoint ports for daemon, CLI, desktop and embedded peer connections. Legacy public wrappers retained for API compatibility. Concurrent real-server qualification pending. |

## Additional changes

Windows MSVC builds use the SDK resource compiler for the application icon; GNU builds retain windres. Missing resource compiler now fails the build instead of silently shipping an iconless executable. CLI/desktop package versions set to 2.4.5. Changelog marks this version as unreleased.

## Remaining release gates

- Run Rust and tooling CI after each relevant development-branch change.
- Windows/Linux real TAP, official Radmin client and mixed-version interoperability, disconnect/reconnect, route cleanup and concurrent GUI/CLI use, visual/accessibility checks.
- 24–72 hour controlled soak plus roster scaling, memory, handle and latency measurements.
- Implement the remaining findings before claiming the audit complete. Bounded peer-quality history is implemented in status JSON by the follow-up; desktop visualization, incremental status and engine-path consolidation remain open.
- Local Linux qualification blocked: registered Ubuntu WSL distribution fails to start because its virtual disk is missing (ERROR_FILE_NOT_FOUND). No native TAP or official Radmin interoperability run was performed.

The support bundle is available through `openrad diagnostics` (also with `--json`) and the desktop diagnostic preview. Export is allowlisted: event categories and typed counters only, with bounded input and retention; raw log messages, credentials and identifiers are excluded. A privacy regression passed.

## Windows checkpoint validation

- `cargo check --workspace --all-targets --locked --jobs 1`: passed.
- `cargo test --workspace --locked --jobs 1`: 386 passed, zero failed, one ignored. Import and preference regressions, pipe integration, cancellation, protocol fixtures, and desktop localization covered.
- `cargo clippy --workspace --all-targets --locked --jobs 1 -- -D warnings`: passed with warnings denied.
- `cargo fmt --all -- --check` and `git diff --check`: passed.
- `python scripts/check-sync.py`: passed with 987 Rust catalog keys.
- `cargo audit --no-fetch` against the local RustSec snapshot: zero classified vulnerabilities, four warnings.
- `cargo build --workspace --release --locked --jobs 1`: passed in 6m 26s. All three Windows executables rebuilt. Windows ExtractIconEx confirmed one embedded icon group in the desktop binary. Release CLI reported 2.4.5 and returned a valid privacy-filtered JSON diagnostic bundle using an isolated temporary profile.

The local Windows checks are not a substitute for Linux or real TAP qualification. The current development commit is not a release candidate approval.

## Tooling follow-up

The initial development-branch Rust CI and sync-check passed (commit `42eb099`). The release tooling workflow failed because its startup fixture loaded only the registration script, without the ACL validator, and the current-version release documents were absent. The fixture now composes both production scripts and tests ACL, ownership, reparse points and missing ACLs without changing OS tasks. Added the two required 2.4.5 development release documents.

## Independent report follow-up

See [the independent-report follow-up](openrad-2.4.5-independent-followup.md) for the subsequent Rust fixes, release inventory, Dependabot, four fuzz targets, maintenance policy and new local-coordinator integration coverage. Earlier release gates remain open unless explicitly qualified there.

## Rust and Android development follow-up

See [the combined audit](openrad-2.4.5-android-review.md) for Android findings, peer quality history and the updated qualification gates.

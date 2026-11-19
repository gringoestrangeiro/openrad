# OpenRad 2.4.5 - independent report follow-up

Scope: Rust 2.4.5 development branch, following the independent November 19
2026 analysis of 2.4.0. Starting checkpoint 33a93d9. Existing uncommitted
work from the interrupted implementation was reviewed and preserved where
justified. Other client versions and the published release are unchanged.

## Findings and disposition

| Report finding | Current disposition and evidence |
| --- | --- |
| Shared engine, headless tests, documentation and anti-drift | Retained. The earlier audit already added bounded roster admission, session correlation, IPC and TAP lifecycle changes. Sync check remains green. |
| No fuzzing | Implemented four in-process targets: identity_import, control_records, tunnel_records, handshake_records. Synthetic corpus comes from canonical fixtures; shared dependency versions/checksums match the application lockfile. Linux workflow has bounded push/PR and weekly runs. All targets passed cargo check on Windows; instrumented execution failed at the MSVC linker. Linux campaigns remain unexecuted. |
| Unsafe and unwrap/expect counts | Counts in the report include a static 2.4.0 scan and are not vulnerability counts. Complete recount and reachability review were not finished. Selected concrete Windows I/O fixes remove unchecked indexing and assertion-based pending-operation handling, and validate completion lengths. Kernel buffer ownership and completion requirements remain intact. Native driver cancellation remains unqualified. |
| Legacy cryptography | Compatibility constraint. AES zeroize feature, transient RSA encoding buffer cleanup and consistent malformed-record errors were added, with a regression test. The original algorithm and wire format are unchanged. These changes do not prove side-channel resistance, complete secret erasure (including BigUint allocations), or independent cryptographic assurance. |
| Dependency advisories | async-executor updated from yanked 1.14.0 to 1.14.1. Earlier work updated tokio. Cargo Audit reports zero classified vulnerabilities, three informational unmaintained warnings on the local advisory snapshot 074ea9a (November 19). |
| Supply chain and maintenance | Grouped Dependabot configuration for Cargo, Actions, Gradle, Go and Python; pinned actions in the Rust, tooling and candidate workflows; security reporting and dependency/release policy. Dependabot has not run on GitHub for this commit. |
| No SBOM | CycloneDX 1.6 normal dependency graph based on locked, target-filtered Cargo metadata, binary hashes and explicit bundled components (font, runtime DLLs, TAP driver/source and installer bootstrapper where applicable). Structural graph checks during generation and five regressions. This is not per-binary linker proof or an inventory of every OS/build component. |
| TAP licensing | Existing installer validates driver file hashes, uses pinned 9.27.0 source revision and includes corresponding source and GPL/MIT notices. New SBOM includes driver provenance and source hash. Independent legal review is not complete. |
| Official server dependence | Custom endpoint modulus resolution now uses the selected slot rather than rereading a mutable server selector; missing custom modulus fails rather than falling back. Official default is retained. A real disposable Go coordinator test registers two identities, discovers a network, joins, reconnects and validates membership on localhost. No actual Radmin service was contacted. |
| Platform asymmetry and interoperability | Headless Rust tests pass on Windows. Native TAP/UAC, Linux runtime and official-client interoperability remain separate release gates, and the new commits have not run in remote CI. |
| Single-maintainer risk | SECURITY.md and docs/maintenance.md reduce ambiguity and automate recurring work. They cannot create independent reviewers, guarantee response times or remove the single-maintainer dependency. |
| Legal/service continuity risk | No legal conclusion is made. The official service remains outside project control. Own-coordinator deployment is supported; proprietary protocol changes remain a compatibility risk. |

## Correctness fixes

- src/file_io.rs: atomic creation failures no longer delete a temporary file
  owned by another writer. Existing contents survive the collision regression.
- src/daemon.rs: read the modulus from the captured endpoint's slot; the
  official override is read from the official profile only. Missing custom
  modulus regression and server selection test. Unix selection removal syncs
  its parent directory, with the existing warning-on-post-commit failure rule.
- src/runtime.rs: capture the legacy configured control port when an embedded
  caller did not pass an explicit port, preserving compatibility while the
  running session keeps its own port.
- src/platform/windows_io.rs, windows_pipe.rs, windows.rs: check capacity,
  completed lengths and pending state; errors replace unchecked access.
- src/crypto.rs: preserve wire format and channel chaining while improving
  cleanup of allocated buffers and error handling. Synthetic fixture tests
  still pass. No independent assurance claim.
- scripts/package-*.py: include release inventory and security/maintenance
  documents before manifests/checksums; preserve the previous release's
  detailed history.

## Dependency warnings

RUSTSEC-2026-0370 (zvariant 5.15.0), RUSTSEC-2026-0371 (zbus_names 4.3.4) and
RUSTSEC-2026-0372 (zvariant_derive 5.15.0) are informational unmaintained
advisories. The advisory recommends the zbus 6 consolidation. The normal
Linux tree includes zbus 5.19.0 through accesskit/eframe and secret-service/
keyring. The MSVC normal target tree does not include zbus_names. Do not
remove accessibility or system credential storage merely to clear warnings.
Track upstream migrations; test credential-store compatibility and accessibility
before changing these dependencies. No advisory was suppressed.

## Executed checks

Windows target, locked workspace:

- cargo fmt --all -- --check: passed; fuzz workspace formatting passed.
- cargo test --workspace --locked --jobs 1: 387 passed, zero failed, two
  ignored (the separate coordinator test and existing ignored test).
- cargo clippy --workspace --all-targets --locked --jobs 1 -- -D warnings:
  passed.
- cargo check --workspace --all-targets --locked --jobs 1: passed.
- python scripts/test-self-hosted.py: one separate real coordinator test
  passed on Windows.
- python scripts/check-sync.py: passed, 989 Rust locale keys; existing
  fixture and other-client mirrors unchanged.
- python scripts/test-release-sbom.py: five tests passed.
- python scripts/test-package-release-history.py: three tests passed.
- scripts/test-windows-startup.ps1: passed in Windows PowerShell; no OS tasks
  created or removed by the regression harness.
- actionlint: four edited workflows passed. Dependabot YAML parsed and all
  five ecosystems' configured manifest paths checked.
- CycloneDX official 1.6 JSON schema (specification tag 1.6.1, pinned commit
  8a27bfd1be5be0dcb2c208a34d2f4fa0b6d75bd7): passed for generated inventories
  using actual locked Cargo metadata for Windows MSVC, Windows GNU and Linux
  GNU, with synthetic staging files. Counts were 218, 218 and 306 components
  respectively in the schema test; these are not counts of linked binaries.
- cargo +nightly check --manifest-path fuzz/Cargo.toml --locked --all-targets:
  passed. Instrumented cargo-fuzz run failed with MSVC unresolved sancov
  symbols, and an uninstrumented smoke build also failed at the linker.
  No successful fuzz campaign or coverage results are claimed.
- cargo build --workspace --release --locked --jobs 1: passed in 7m 40s. All three Windows release executables rebuilt.
- cargo audit --no-fetch: zero classified vulnerabilities, three warnings,
  using the local RustSec snapshot above.

## Release gates still open

Linux fuzz execution, Miri, full stateful handshake/UDP fuzz coverage,
independent cryptography and legal reviews, native TAP/UAC and application
interoperability with authorized official clients, a controlled long-run
soak, and remaining findings in openrad-2.4.5-status.md. In particular,
quality history, incremental status, and engine-path consolidation are not
implemented by this checkpoint. No external target was tested. New remote
CI and full platform packaging remain to be qualified; no release was
published.

Implementation commits: c77efaf (Rust fixes), 4ce189e (tooling and policy).

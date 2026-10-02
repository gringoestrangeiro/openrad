# Repository Guidelines

## Project Structure & Module Organization

OpenRad is a Rust VPN client workspace for Linux and experimental Windows x64:

- `src/`: reusable `openrad` library, CLI (`main.rs`), setup worker (`setup_main.rs`), protocol, cryptography, transports, and runtime.
- `src/daemon.rs`: shared per-user service, profile resolution, saved identity/preferences, and bounded local command protocol. Desktop and CLI must use this same engine.
- `src/platform/`: Linux TAP helper, Unix resource limits, Windows TAP/overlapped I/O/named pipes/security/setup/recovery, and the release HTTP adapter.
- `desktop/src/`: `eframe` UI, asynchronous service client, settings, credential storage, network favorites/join configurations, and graphics fallback.
- `locales/messages.json`: English, Portuguese, Russian, and Vietnamese UI/CLI messages. Maintain complete translations and format placeholders.
- `tests/`: headless integration tests; `tests/fixtures/` contains synthetic protocol and cryptographic vectors.
- `docs/`: platform and usage guides, architecture, performance, screenshots, release notes, and dependency notices.
- `scripts/`, `packaging/windows/`: audited Linux/Windows archives, offline NSIS installer, and synthetic packaging regressions.

Keep shared VPN logic independent of the GUI. Closing a frontend leaves the service running; Disconnect/Stop explicitly stops the shared session. Default profiles are shared, with existing desktop profiles retained when appropriate. Preserve an identity's credential-store or private-file storage rather than registering a second device.

## Build, Test, and Development Commands

Use Rust 1.95+ from the repository root. See `docs/linux.md` and `docs/windows.md` for native dependencies, credential stores, and driver setup.

| Command | Purpose |
| --- | --- |
| `cargo build --workspace --release --locked` | Build CLI, desktop, and setup helper. |
| `./target/release/openrad-desktop` | Launch the Linux desktop; allow the system Polkit permission dialog when connecting. |
| `./target/release/openrad --help` | Explore the CLI, including `ping`, `rename`, and `force-relay`. |
| `./target/release/openrad stop` | Stop the shared service before replacing binaries. |
| `cargo test --workspace --locked` | Run default headless, unprivileged workspace tests. |
| `cargo fmt --all -- --check` | Check Rust formatting. |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | Lint with warnings denied. |
| `cargo build --workspace --release --target x86_64-pc-windows-gnu --locked` | Cross-build Windows using MinGW-w64. |
| `cargo test --workspace --target x86_64-pc-windows-gnu --no-run --locked` | Compile Windows test executables; this does not execute them. |
| `cargo clippy --workspace --all-targets --target x86_64-pc-windows-gnu --locked -- -D warnings` | Lint Windows-only code. |

Run Linux clients as the normal user; only the short-lived TAP helper uses sudo. Windows requires the installed dedicated TAP-Windows6 adapter and elevation. Keep the CLI and desktop binaries together; use the same `--data-dir PATH` with both frontends. Do not stop a user's real VPN during automated validation.

## Coding Style & Behavioral Contracts

Follow Rust 2021 conventions: four-space indentation, `snake_case` modules/functions, `PascalCase` types, and `SCREAMING_SNAKE_CASE` constants. Apply `cargo fmt --all`. Match existing `anyhow::Result` handling, retain useful error chains, and validate untrusted protocol data before use.

- Keep UI work responsive: use the backend for IPC, provisioning, and release requests. Preserve operation/phase barriers when coalescing snapshots, and invalidate presentation caches on all relevant metadata, language, sorting, and layout changes.
- Keep local commands, queues, DNS/connection waits, retries, and record sizes bounded. Profile/startup/service locks must prevent duplicate engines and protect identity replacement. Remove stale Unix endpoints only after proving that no service owns the profile; never delete unrelated files.
- RTT probes use correlated authenticated keepalives with a 3000 ms timeout. Relay-only mode suppresses direct transport setup in both directions. Join requests may overlap at intervals of at least 50 ms; ambiguous private-password replies must never advance multiple operations.
- Retry transient provisioning failures only before registration may have reached the server. Preserve an issued replacement for storage retries and keep the previous identity until saving succeeds.
- Preserve encryption chaining, wire formats, fragment coverage/order, ACK admission, and buffer lifetimes when optimizing. Validate an entire incoming tunnel record before delivering frames. Windows overlapped buffers remain owned until cancellation/completion finishes.
- Favorites and named join configurations persist separately from credentials. Private passwords use zeroized memory and are never serialized into saved lists. Corrupt preferences must remain available for repair.

## Testing Guidelines

Use Rust's built-in harness, inline unit modules, and `tests/*.rs`. Name tests after observable behavior. Add regressions for changed behavior using synthetic fixtures, local sockets, disposable child processes, or in-memory credential stores. Default tests must remain headless and unprivileged; no numeric coverage threshold is configured.

The ignored `linux_interface` test creates a TAP interface; follow `docs/linux.md` before explicitly running it. Software-renderer and screenshot tests require a Vulkan CPU driver and render synthetic UI without a real profile. Run the relevant ignored tests explicitly for graphics/layout changes and inspect their images; retain public screenshots under `docs/screenshots/`.

PowerShell setup/recovery tests mock Windows cmdlets. Wine installer-flow tests use dummy executables and an isolated prefix. Selected Windows Rust tests can run in Wine, but cross-compilation, Wine, static import inspection, and synthetic screenshots do not establish real Windows TAP/UAC/interoperability behavior. Report the scope of each check accurately.

## Commits, Changelogs & Releases

Use focused imperative subjects with `fix:`, `docs:`, or `release:` prefixes. PRs describe the problem and resulting behavior, link issues, report validation, and include screenshots for UI changes. Update affected guides and preserve the user's authorized scope.

For a release, compare against a freshly fetched remote, account for every changed/new file, synchronize both Cargo package versions and `Cargo.lock`, and update `README.md`, `CHANGELOG.md`, and `docs/releases/VERSION.md`. Keep a file-by-file inventory for broad releases. Record Windows support limitations even when the release uses a stable version tag.

Build fresh binaries for both platforms from the release source. Prefer the established Debian 12/Rust 1.95 Linux builder to retain its glibc baseline. Use `scripts/package-linux.py` with truthful build provenance and the builder's standard-library notice; use `scripts/package-windows-installer.py` with NSIS/7-Zip and pinned driver inputs. Include release guides, screenshots, dependency notices, build/import metadata, corresponding driver source, and SHA-256 checksums. Verify binary versions, archive checksums, and extracted installer payloads before publishing. Never label unexecuted native-platform checks as passed.

## Security & Local Configuration

Keep identities, credentials, captures, operational reports, and runtime settings out of commits. Use ignored `profiles/`, `reports/`, `captures/`, and `dist/` directories for local data. Fixtures and screenshots contain synthetic names, addresses, and secrets only. Logs may include diagnostic identifiers and addresses but must exclude passwords, issued credentials, session keys, and packet payloads. Release archives use explicit allowlists; review staged files before pushing.

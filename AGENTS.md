# Repository Guidelines

## Project Structure & Module Organization

OpenRad is a Rust VPN client workspace:

- `src/`: reusable `openrad` library and CLI (`main.rs`); protocol, cryptography, transports, runtime, and Linux TAP support.
- `desktop/src/`: `eframe` desktop UI, background backend, settings, and credential storage.
- `tests/`: integration tests; `tests/fixtures/` holds synthetic JSON protocol and cryptographic vectors.
- `docs/`: Linux setup, CLI/desktop usage, and architecture.

Keep shared VPN logic independent of the GUI.

## Build, Test, and Development Commands

Use Rust 1.95+ and run commands from the repository root. See `docs/linux.md` for native dependencies and credential-store setup.

| Command | Purpose |
| --- | --- |
| `cargo build --workspace --release --locked` | Build both CLI and desktop binaries. |
| `./target/release/openrad-desktop` | Launch desktop; run `sudo -v` before connecting. |
| `./target/release/openrad --help` | Explore headless CLI commands. |
| `cargo test --workspace --locked` | Run default workspace tests. |
| `cargo fmt --all -- --check` | Check Rust formatting. |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | Lint with warnings treated as errors. |

Run the application as your normal user; only the TAP helper uses `sudo`. Keep both binaries together.

## Coding Style & Naming Conventions

Follow Rust 2021 conventions: four-space indentation, `snake_case` modules/functions, `PascalCase` types, and `SCREAMING_SNAKE_CASE` constants. Apply formatting with `cargo fmt --all`. Match existing `anyhow::Result` error handling and validate untrusted protocol data before use. Keep platform-specific interface code under `src/platform/`.

## Testing Guidelines

Use Rust's built-in `#[test]` harness, with inline `#[cfg(test)]` modules for unit tests and `tests/*.rs` for integration tests. Name tests after observable behavior, such as `rendezvous_checksum_matches_fixed_vector`.

Add regression tests for changed behavior using synthetic fixtures, local sockets, or in-memory credential stores. Default tests must remain headless and unprivileged. No numeric coverage threshold is configured. The ignored `linux_interface` test creates a TAP interface; follow `docs/linux.md` before running it explicitly.

## Commit & Pull Request Guidelines

Recent commits use `fix:`, `docs:`, and `release:` prefixes alongside imperative subjects. Write focused subjects. PRs should describe the problem and resulting behavior, link relevant issues, and report validation results. Include screenshots for UI changes and update affected documentation.

## Security & Local Configuration

Keep identities, credentials, captures, and operational reports out of commits. Use ignored `profiles/`, `reports/`, and `captures/` directories for local data. Fixtures must contain synthetic secrets; logs must exclude passwords, session keys, and packet payloads.

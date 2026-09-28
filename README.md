# OpenRad

OpenRad is an open-source Rust reimplementation of Radmin VPN, with a native Linux desktop application and a headless CLI. It provides a virtual Ethernet interface for applications on joined networks and shows each peer's transport as **Direct TCP**, **Direct UDP**, or **Relay**.

The project is experimental. Linux is the supported data-plane platform; Windows and macOS interface backends are not implemented. This is an unofficial community reimplementation and is not affiliated with Radmin or Famatech.

## Features

- Native desktop interface for connection status, public-network discovery, membership management, and per-peer transport and traffic counters.
- Create and join password-protected private networks; administrators can remove members, grant or revoke admin permissions, and delete networks from the desktop or CLI.
- Incoming and outgoing authenticated peer channels, bounded concurrent connection setup, direct TCP and reliable UDP, and relay fallback.
- Linux TAP interface with Ethernet, ARP, IPv4 unicast, broadcast, and multicast forwarding.
- Desktop credentials stored in the operating system's credential store, with a confirmed identity-reset flow.
- A headless CLI for provisioning, listing networks and peers, private-network administration, and bounded connection sessions.

A successful service login does not establish a direct connection to every peer. Direct connectivity depends on both peers and their network conditions. The peer table reports the selected transport; data counters provide a separate indication of traffic.

## Build and run

Use Rust **1.95 or newer** with Cargo. Build both workspace binaries so the desktop can locate its TAP setup helper:

```sh
cargo build --workspace --release --locked
sudo -v
./target/release/openrad-desktop
```

Linux requires a C build toolchain, `pkg-config`, the X11/Wayland and OpenGL libraries used by `eframe`, `iproute2`, `sudo`, and `/dev/net/tun`. The desktop also needs a running, unlocked Secret Service provider such as GNOME Keyring or KWallet. See [the Linux setup guide](docs/linux.md) for package examples and troubleshooting.

Run OpenRad as your normal user. Only the short-lived TAP setup helper uses `sudo`; the GUI, service connection, and peer connections stay unprivileged. Keep `openrad` and `openrad-desktop` in the same directory if you copy the binaries elsewhere.

The desktop provisions an identity on first connection. Subsequent launches reuse the saved identity. It can also import an existing identity file:

```sh
./target/release/openrad-desktop --identity /path/to/identity.json
```

An existing profile will not silently replace a different saved identity. Use an isolated profile when importing another identity:

```sh
./target/release/openrad-desktop --data-dir ./profiles/alternate --identity /path/to/identity.json
```

## Prebuilt release

The [OpenRad v0.6.0 Linux x86-64 release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.6.0) includes the desktop app, CLI, setup and usage guides, and SHA-256 checksums. It fixes authentication when joining private networks created by the official client and adds desktop preferences for reconnect behavior, peer display, traffic graphs, and diagnostics. See the [changelog](CHANGELOG.md), [desktop guide](docs/desktop.md), and [CLI guide](docs/cli.md) for details.

[Desktop and identity guide](docs/desktop.md) · [CLI guide](docs/cli.md) · [Architecture](docs/architecture.md)

## Development

The default tests run headlessly, using synthetic vectors, in-memory credential stores, and local sockets. They do not connect to public peers or modify your desktop identity.

```sh
cargo fmt --all -- --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
```

An optional Linux interface test is ignored by default because it creates a TAP interface and needs `sudo`; see the Linux guide. Passing local tests does not establish live interoperability across arbitrary peers or NAT configurations.

## Project layout

| Location | Purpose |
| --- | --- |
| `src/` | Reusable VPN engine, protocol, peer transports, Linux TAP adapter, and CLI |
| `desktop/` | Native desktop UI, background service, settings, and credential storage |
| `tests/` | Headless integration tests and synthetic protocol vectors |
| `docs/` | Setup, usage, and development guides |

The public service endpoint and public RSA modulus are bundled in `src/config.rs`; no external files or vendor runtime are needed. CLI provisioning supports an endpoint override, and CLI commands accept an optional public-modulus override.

## Local data

The desktop stores settings and bounded, rotating connection diagnostics in the platform's application-data directory, and credentials in the OS credential store. Settings shows the connection log location; see [diagnosing connection drops](docs/desktop.md#diagnosing-connection-drops). CLI provisioning explicitly exports a reusable identity to a private output directory; keep that file private. Operational reports can include peer IDs, addresses, and network names, but do not record session keys, authentication passwords, or packet contents.

The repository ignores local profiles, reports, credentials, logs, captures, and build products. Use the documented output locations, and review any files before sharing them. Test fixtures contain only synthetic credentials and key material.

## Credits

asimplestray contributed approximately 95% of the cryptography work behind OpenRad, including the work in `shelper.dll` and the RSA session setup, secure handshake, and encrypted-channel implementation. The MIT license also credits him as a copyright holder.

[Baptiste Rajaut (@baptisterajaut)](https://github.com/baptisterajaut) contributed a robustness audit and fixes for reliable UDP flow control, full-size Ethernet frames, display-name decoding, identity provisioning, reconnection, and TAP helper cleanup in [PR #1](https://github.com/gringoestrangeiro/openrad/pull/1).

## License

[MIT](LICENSE).

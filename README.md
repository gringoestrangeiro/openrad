# OpenRad

OpenRad is an open-source Rust reimplementation of Radmin VPN, with a native Linux desktop application and a headless CLI. It provides a virtual Ethernet interface for applications on joined networks and shows each peer's transport as **Direct TCP**, **Direct UDP**, or **Relay**.

OpenRad is now in its **stable phase on Linux**. Extensive interoperability testing against the official Radmin VPN client has shown **100% compatibility in the scenarios tested**. OpenRad is **practically 100% compatible with regular Radmin VPN** for its supported functionality.

Linux is the supported data-plane platform; Windows and macOS interface backends are not implemented. This is an unofficial community reimplementation and is not affiliated with Radmin or Famatech.

## Features

- Native desktop interface for connection status, public-network discovery, membership management, and per-peer transport and traffic counters.
- Create and join password-protected private networks; administrators can remove members, grant or revoke admin permissions, and delete networks from the desktop or CLI.
- Incoming and outgoing authenticated peer channels, bounded concurrent connection setup, direct TCP and reliable UDP, and relay fallback.
- Linux TAP interface with Ethernet, ARP, IPv4 unicast, broadcast, and multicast forwarding.
- Desktop credentials stored in the operating system's credential store, with a confirmed identity-reset flow.
- A headless CLI backed by a persistent per-user service: networks and peers stay connected after the terminal closes, with automatic service reconnection and live membership updates.

A successful service login does not establish a direct connection to every peer. Direct connectivity depends on both peers and their network conditions. The peer table reports the selected transport; data counters provide a separate indication of traffic.

## Performance in 0.8.0

Version 0.8.0 reduces CPU use and temporary allocations with event-driven peer waits, indexed unicast forwarding, reusable packet buffers, and lighter desktop peer lists. Protocol formats, Ethernet payloads, and cryptographic results are preserved.

| Local benchmark, 120 peers | Before | After | Improvement |
| --- | ---: | ---: | ---: |
| Summed worker CPU time over 2 seconds of idle UDP waiting | 391.6 ms | 1.2 ms | 99.7% less CPU time |
| Recipient selection for 30,000 directed ARP frames | 60.8 ms | 1.2 ms | 50.6× faster |
| Prepare 2,000 desktop peer lists sorted by name | 381.9 ms | 29.1 ms | 13.1× faster |
| Peak temporary heap for one desktop peer list | 70.2 KiB | 7.3 KiB | 89.6% less memory |

These measurements cover isolated operations, not whole-application CPU, process RSS, or VPN throughput. See [performance details](docs/performance.md) for methodology and regression coverage.

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

For headless use, initialize a private CLI profile once and start the background service:

```sh
./target/release/openrad init --node-name my-device
sudo -v
./target/release/openrad start
./target/release/openrad status
./target/release/openrad create 'Friends LAN' --password-file /path/to/password.txt
```

See the [CLI guide](docs/cli.md) for importing an identity, joining later, and managing the service.

## Releases

The [release page](https://github.com/gringoestrangeiro/openrad/releases) provides Linux x86-64 archives with the desktop app, CLI, guides, and SHA-256 checksums. See the [0.8.0 changelog](CHANGELOG.md#080--2026-09-29), [desktop guide](docs/desktop.md), and [CLI guide](docs/cli.md) for this source revision.

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

The public service endpoint and public RSA modulus are bundled in `src/config.rs`; no external files or vendor runtime are needed. CLI initialization supports endpoint and public-modulus overrides. The chosen modulus is stored in the CLI profile for later service connections.

## Local data

The desktop stores settings and bounded, rotating connection diagnostics in the platform's application-data directory, and credentials in the OS credential store. Settings shows the connection log location; see [diagnosing connection drops](docs/desktop.md#diagnosing-connection-drops). The CLI saves a reusable identity and service state in its private profile directory; keep the identity private. Diagnostics can include peer IDs, addresses, and network names, but do not record session keys, authentication passwords, or packet contents.

The repository ignores local profiles, reports, credentials, logs, captures, and build products. Use the documented output locations, and review any files before sharing them. Test fixtures contain only synthetic credentials and key material.

## Credits

asimplestray contributed approximately 95% of the cryptography work behind OpenRad, including the RSA session setup, secure handshake, and encrypted-channel implementation. The MIT license also credits him as a copyright holder.

[Baptiste Rajaut (@baptisterajaut)](https://github.com/baptisterajaut) contributed a robustness audit and fixes for reliable UDP flow control, full-size Ethernet frames, display-name decoding, identity provisioning, reconnection, and TAP helper cleanup in [PR #1](https://github.com/gringoestrangeiro/openrad/pull/1).

## License

[MIT](LICENSE).

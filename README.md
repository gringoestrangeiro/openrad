# OpenRad

OpenRad is an open-source Rust reimplementation of Radmin VPN, with a native desktop application and a headless CLI. It provides a virtual Ethernet interface for applications on joined networks and shows each peer's transport as **Direct TCP**, **Direct UDP**, or **Relay**.

OpenRad is now in its **stable phase on Linux**. Extensive interoperability testing against the official Radmin VPN client has shown **100% compatibility in the scenarios tested**. OpenRad is **practically 100% compatible with regular Radmin VPN** for its supported functionality.

Version **0.9.0** adds **experimental Windows x64 support** and a complete four-language desktop/CLI: English, Portuguese, Russian, and Vietnamese. Windows has been tested only on **Windows 10** so far, where the tester reports it working perfectly in their setup. Windows 11 and other Windows configurations still need validation. Linux remains the established platform.

**macOS support is planned, but work on it has not started yet.** This is an unofficial community reimplementation and is not affiliated with Radmin or Famatech.

## Features

- Native desktop interface for connection status, public-network discovery, membership management, and per-peer transport and traffic counters.
- English, Portuguese, Russian, and Vietnamese throughout the desktop and CLI, with automatic system-language detection and a saved desktop language preference.
- Create and join password-protected private networks; administrators can remove members, grant or revoke admin permissions, and delete networks from the desktop or CLI.
- Incoming and outgoing authenticated peer channels, bounded concurrent connection setup, direct TCP and reliable UDP, and relay fallback.
- Linux TAP and Windows TAP-Windows6 interfaces with Ethernet, ARP, IPv4 unicast, broadcast, and multicast forwarding; Windows support remains experimental.
- Desktop credentials stored in the operating system's credential store, with a confirmed identity-reset flow.
- A headless CLI backed by a persistent per-user service: networks and peers stay connected after the terminal closes, with automatic service reconnection and live membership updates.

A successful service login does not establish a direct connection to every peer. Direct connectivity depends on both peers and their network conditions. The peer table reports the selected transport; data counters provide a separate indication of traffic.

## Windows performance compared with the official client

User-reported Windows 10 observations with both clients on the **same network, 76 peers, one joined network**:

| Idle measurement | Official Radmin VPN | OpenRad |
| --- | ---: | ---: |
| Memory | 176 MB GUI + 23 MB service = **199 MB** | **41 MB client** |
| CPU usage | **1–4%** | **0–3%** |

Idle CPU usage is broadly similar in this observation, while OpenRad uses substantially less reported memory. We plan further CPU and memory improvements. These are observations from one Windows 10 setup; the percentages depend on hardware, timing and workload, and do not measure VPN throughput.

## Performance in 0.8.0

Version 0.8.0 reduces CPU use and temporary allocations with event-driven peer waits, indexed unicast forwarding, reusable packet buffers, and lighter desktop peer lists. Protocol formats, Ethernet payloads, and cryptographic results are preserved.

| Local benchmark, 120 peers | Before | After | Improvement |
| --- | ---: | ---: | ---: |
| Summed worker CPU time over 2 seconds of idle UDP waiting | 391.6 ms | 1.2 ms | 99.7% less CPU time |
| Recipient selection for 30,000 directed ARP frames | 60.8 ms | 1.2 ms | 50.6× faster |
| Prepare 2,000 desktop peer lists sorted by name | 381.9 ms | 29.1 ms | 13.1× faster |
| Peak temporary heap for one desktop peer list | 70.2 KiB | 7.3 KiB | 89.6% less memory |

These measurements cover isolated operations, not whole-application CPU, process RSS, or VPN throughput. See [performance details](docs/performance.md) for methodology and regression coverage.

## Download and run

The [0.9.0 release](https://github.com/gringoestrangeiro/openrad/releases/tag/v0.9.0) provides separate **Linux x86-64** and **Windows x64** packages with SHA-256 checksums.

On Linux, extract the `.tar.gz`, keep `openrad` and `openrad-desktop` together, and run:

```sh
sudo -v
./openrad-desktop
```

Run OpenRad as your normal user; only the short-lived TAP helper uses `sudo`. The desktop needs an unlocked Secret Service provider such as GNOME Keyring or KWallet. See [Linux setup](docs/linux.md).

On Windows, extract the Windows ZIP and run **OpenRad-Setup.exe**, or use the standalone setup EXE attached to the release. The offline installer includes both applications, the official signed TAP-Windows6 driver, driver source/licenses and diagnostic tools. It creates the dedicated adapter, adds shortcuts, and opens the desktop. Repeat setup checks the installation and opens it; `--no-launch` suppresses desktop startup for CLI use. Normal Windows GUI launches request administrator approval.

Windows automatically tries native Direct3D 12 hardware rendering, then Windows WARP software rendering, then OpenGL. This supports the tested VirtualBox setup without requiring OpenGL. See [Windows installation, diagnostics and tests](docs/windows.md).

## Build on Linux

Use Rust **1.95 or newer** with Cargo. A Debian/Ubuntu development setup needs:

```sh
sudo apt install build-essential pkg-config libx11-dev libxkbcommon-dev libwayland-dev libgl1-mesa-dev iproute2 sudo gnome-keyring
```

Clone the repository and build the workspace:

```sh
git clone https://github.com/gringoestrangeiro/openrad.git
cd openrad
cargo build --workspace --release --locked
sudo -v
./target/release/openrad-desktop
```

The GUI needs an X11/Wayland desktop and OpenGL, or a suitable Vulkan driver for explicit WGPU rendering. `/dev/net/tun`, `iproute2` and `sudo` provide Linux TAP setup. Both application binaries must stay together. See [Linux setup](docs/linux.md) for permissions and troubleshooting.

## Build on Windows

Use Windows x64, Git and Rust **1.95 or newer**. Install the MSVC Rust toolchain through [rustup](https://rust-lang.org/tools/install/), plus Visual Studio Build Tools with **Desktop development with C++** and a Windows SDK. These are the [official Rust/MSVC prerequisites](https://rust-lang.github.io/rustup/installation/windows-msvc.html).

From PowerShell or Developer PowerShell for Visual Studio:

```powershell
git clone https://github.com/gringoestrangeiro/openrad.git
Set-Location openrad
rustup toolchain install stable-x86_64-pc-windows-msvc
rustup run stable-x86_64-pc-windows-msvc cargo build --workspace --release --locked --target x86_64-pc-windows-msvc
```

Compilation produces the desktop, CLI and setup worker; it does not install a kernel driver. Configure the TAP-Windows6 adapter once using the released `OpenRad-Setup.exe --no-launch`, and see the [driver/setup details](docs/windows.md#install-and-open-openrad). Then launch your newly built desktop:

```powershell
.\target\x86_64-pc-windows-msvc\release\openrad-desktop.exe
```

Use an elevated PowerShell for the built CLI when configuring networking. The published Windows package is cross-built with MinGW-w64 on Linux; the native MSVC instructions above have not been executed on this Linux host. Linux-to-Windows cross-build and offline-installer packaging commands are in [the Windows build guide](docs/windows.md#implementation-sources-and-linux-cross-build).

## Help stabilize Windows for 1.0.0

If you use Windows, please [open an issue](https://github.com/gringoestrangeiro/openrad/issues/new) with your successes, crashes or networking problems. We need more real Windows data to make support stable for **1.0.0**, especially outside the tested Windows 10 setup.

Include your OpenRad version, Windows version/build, hardware or VM details, graphics renderer, network/peer counts, reproduction steps and relevant startup/connection logs. Desktop and CLI startup logs are in `%LOCALAPPDATA%\OpenRad\logs`; connection-log locations are documented in [Windows diagnostics](docs/windows.md#startup-and-crash-logs). Review logs before sharing and keep identities, passwords and keys private.

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

The [release page](https://github.com/gringoestrangeiro/openrad/releases) provides separate Linux and experimental Windows builds, guides, licenses and SHA-256 checksums. See the [0.9.0 changelog](CHANGELOG.md#090--2026-09-30), [release details](docs/releases/0.9.0.md), [desktop guide](docs/desktop.md), and [CLI guide](docs/cli.md).

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
| `src/` | Reusable VPN engine, protocol, peer transports, Linux/Windows TAP adapters, and CLI |
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

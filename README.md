# OpenRad

OpenRad is an open-source Rust reimplementation of Radmin VPN. It provides a
virtual Ethernet interface, a native desktop client, and a headless command-line
client for joining networks and communicating with peers.

OpenRad is an unofficial community project and is not affiliated with Radmin or
Famatech. Linux is the established platform. Windows x64 support is experimental
and has so far been tested on Windows 10. macOS support is planned, but
development has not started.

## Features

- Desktop and headless CLI clients backed by the same VPN engine.
- One persistent per-user VPN session shared by the desktop and CLI.
- Public-network discovery and password-protected private networks.
- Network favorites and saved public/private network selections with paced batch joins.
- Peer connections over direct TCP, reliable UDP, or relay transport.
- Authenticated peer RTT tests, optional relay-only mode, and editable device names.
- Saved outgoing broadcast recipient selection shared between desktop and CLI.
- Linux TAP and Windows TAP-Windows6 virtual Ethernet interfaces.
- English, Portuguese, Russian, and Vietnamese interfaces.
- OS-backed credential storage for desktop identities.
- Live network search, background release notices, and bounded identity-reset recovery.

![OpenRad 1.0.0 networks and peer RTT tests](docs/screenshots/1.0.0-networks.png)

The screenshot uses synthetic networks and peers rendered on Linux.

## Memory use on Windows

In one user-reported Windows 10 idle comparison on the same network (76 peers,
one joined network), the official client used about **199 MB** (GUI and service
combined), while OpenRad used about **41 MB**. Results vary by system and workload.

OpenRad interoperates with regular Radmin VPN for its supported functionality.
Whether peers can establish a direct connection depends on their
network conditions, including NAT or firewall restrictions; OpenRad can use a
relay when a direct path is unavailable. See the [architecture guide](docs/architecture.md)
for protocol and transport details.

## Download

Download the latest packages and SHA-256 checksums from the
[GitHub releases page](https://github.com/gringoestrangeiro/openrad/releases).
Version **1.2.0** includes freshly built Linux x86-64 and Windows x64 packages,
an offline Windows installer, build metadata, dependency notices, and checksums.
Read the [1.2.0 release notes](docs/releases/1.2.0.md) for upgrade behavior,
validation, and platform limitations, and the
[complete change inventory](docs/releases/1.2.0-changes.md) for every changed file.

- **Linux:** Extract the archive, keep `openrad` and `openrad-desktop` together,
  then follow the [Linux setup guide](docs/linux.md).
- **Windows:** Run `OpenRad-Setup.exe` from the release package. It installs the
  application and its TAP-Windows6 driver. See the [Windows guide](docs/windows.md)
  for installation, diagnostics, and current limitations.

## Quick start

On Linux, after extracting the release archive:

```sh
./openrad-desktop
```

Run the application as your normal user. The desktop stores credentials in the
OS credential store, which must be available and unlocked. The short-lived TAP
helper requests authorization in the system permission dialog using Polkit,
after trying existing noninteractive sudo authorization. Allow that dialog when
connecting; the detached service cannot reliably reuse a terminal's `sudo -v`.
Polkit and a session authentication agent must be running.

Closing the desktop window leaves the VPN service running. Use **Disconnect**
or `./openrad stop` to stop the shared session and remove its session interface
configuration. Open the desktop and CLI in either order; they use the same
identity, networks, and peer connections for the selected profile. When upgrading,
stop the old service and close the old desktop before replacing both binaries.

For headless use, initialize a CLI profile and start its background service:

```sh
./openrad init --node-name my-device
./openrad start
./openrad status
```

The CLI service persists after the terminal closes. Read the [CLI guide](docs/cli.md)
for identity imports, network management, private-network passwords, and service
commands. On Windows, use the installed **OpenRad CLI** shortcut; see the
[Windows guide](docs/windows.md#install-and-open-openrad).

Use `--data-dir PATH` with both frontends for a separate profile. New default
profiles use `$XDG_STATE_HOME/openrad` (usually `~/.local/state/openrad`) on Linux
and `%LOCALAPPDATA%\openrad` on Windows; existing desktop profiles are retained
when the default CLI profile has not been initialized.

Useful additions in 1.0.0:

```sh
./openrad ping PEER_RID
./openrad rename my-new-device-name
./openrad force-relay true
```

In the desktop, favorite networks with **☆**, select networks on **Auto join**,
and save a named configuration to reuse the selection. Enter private passwords
again when loading a list; configurations never store them. Batch joining starts
only when you choose **Join selected networks**.

Version 1.2.0 adds **Settings → Outgoing broadcasts** and
`openrad broadcast-peer [RID|NAME|VPN_IP]`. Choose one outgoing recipient or
restore all eligible peers with `openrad broadcast-peer --all`. The preference
is saved immediately and applies without reconnecting. Incoming broadcasts
continue arriving from all authorized peers. See the [CLI guide](docs/cli.md#outgoing-broadcast-recipient)
for eligibility and routing details.

![Outgoing broadcast settings in Portuguese](docs/screenshots/1.2.0-broadcast-settings-pt.png)

## Build from source

OpenRad requires Rust 1.95 or newer. On Debian or Ubuntu, install the native
dependencies listed below, then build the workspace:

```sh
sudo apt install build-essential pkg-config libx11-dev libxkbcommon-dev \
  libwayland-dev libgl1-mesa-dev iproute2 sudo pkexec gnome-keyring curl
git clone https://github.com/gringoestrangeiro/openrad.git
cd openrad
cargo build --workspace --release --locked
```

The build produces both the CLI and desktop application. See [Linux setup](docs/linux.md)
for GUI, credential-store, TAP, and troubleshooting requirements. Windows build
prerequisites and packaging instructions are in the [Windows guide](docs/windows.md).

### Build on Windows

Install Rust 1.95+ and the C++ build tools required by the
[Windows MSVC toolchain](https://rust-lang.github.io/rustup/installation/windows.html),
then build from the repository root:

```powershell
cargo build --workspace --release --locked
cargo test --workspace --locked
```

Keep `target\release\openrad.exe` and `openrad-desktop.exe` together. Install the
dedicated TAP adapter using the released setup before connecting. The published
Windows package is cross-built on Linux with MinGW-w64; native MSVC builds remain
unverified by the release builder. See [Windows build and packaging](docs/windows.md#linux-cross-build-and-offline-installer).

## Documentation

| Guide | Contents |
| --- | --- |
| [Linux setup](docs/linux.md) | Dependencies, permissions, TAP setup, and troubleshooting |
| [Windows setup](docs/windows.md) | Installer, TAP driver, diagnostics, and experimental support status |
| [Desktop usage](docs/desktop.md) | Desktop operation, profiles, identities, and connection logs |
| [CLI em português](docs/cli-pt-BR.md) | Guia completo do CLI, serviço e destino de broadcast |
| [CLI usage](docs/cli.md) | Commands, persistent service, profiles, and network administration |
| [Architecture](docs/architecture.md) | Components, protocol, transports, and platform interfaces |
| [Performance](docs/performance.md) | Benchmark methodology and results |
| [Changelog](CHANGELOG.md) | Version history |
| [1.2.0 release](docs/releases/1.2.0.md) | Packages, upgrading, verification, and limitations |
| [1.2.0 change inventory](docs/releases/1.2.0-changes.md) | File-by-file account of all changes since v1.1.0 |

## Development

The default workspace tests are headless and unprivileged. To check formatting,
run the tests, and lint all targets:

```sh
cargo fmt --all -- --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
```

The optional Linux interface test is ignored by default because it creates a TAP
interface and requires elevated privileges. See [Linux setup](docs/linux.md#optional-interface-test)
before running it.

The repository is organized as follows:

| Path | Purpose |
| --- | --- |
| `src/` | Shared VPN library, protocol, transports, platform interfaces, and CLI |
| `desktop/` | Native desktop application and background engine |
| `tests/` | Integration tests and synthetic protocol fixtures |
| `docs/` | Platform guides, architecture, and performance notes |
| `locales/` | Complete four-language message catalog |
| `scripts/`, `packaging/` | Release archives, Windows installer, and packaging regressions |

## Security and local data

Desktop credentials are stored in the OS credential store. The CLI saves its
identity and service state in a private profile; treat identity files as
credentials and never commit or share them. Connection diagnostics may contain
peer IDs, addresses, and network names, but should not contain passwords,
session keys, or packet contents. Review local logs before sharing them.

Test fixtures use synthetic credentials and key material. See the platform and
[CLI guides](docs/cli.md) for profile locations and diagnostic details.

## Credits

asimplestray contributed approximately 95% of OpenRad's cryptography work,
including RSA session setup, the secure handshake, and encrypted channels. The
MIT license also credits him as a copyright holder.

[Baptiste Rajaut (@baptisterajaut)](https://github.com/baptisterajaut)
contributed a robustness audit and fixes in [PR #1](https://github.com/gringoestrangeiro/openrad/pull/1).

## License

OpenRad is distributed under the [MIT License](LICENSE).

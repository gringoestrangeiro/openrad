# OpenRad

[![OpenRad animated logo](docs/assets/openrad-intro.gif)](docs/assets/openrad-intro.mp4)

OpenRad is an open-source Rust reimplementation of Radmin VPN. It provides a
virtual Ethernet interface, a native desktop client, a headless command-line
client, and a Paper server plugin for joining networks and communicating with
peers, plus a self-hosted coordination server that can replace the official one.

OpenRad is an unofficial community project and is not affiliated with Radmin or
Famatech. Linux is the established platform. Windows x64 support is experimental
and has so far been tested on Windows 10. macOS support is planned, but
development has not started.

## Clients

This repository is a monorepo with five clients and a coordination server
sharing one wire format:

| Client | Path | Status | Version |
| --- | --- | --- | --- |
| Rust (desktop + CLI) | `src/`, `desktop/` | stable, canonical | 2.3.0 |
| Android | [`clients/android/`](clients/android/README.md) | stable | 2.3.0 |
| Python (research) | [`clients/python/`](clients/python/README.md) | beta | 2.3.0 |
| Paper plugin (Minecraft servers) | [`clients/plugin/`](clients/plugin/README.md) | alpha | 2.3.2 |
| Fabric mod (Minecraft clients) | [`clients/mod/`](clients/mod/README.md) | alpha | 2.3.2 |
| Coordination server (Go, self-hosted) | [`server/`](server/README.md) | beta | 2.3.0 |

The Rust client is the source of truth for the wire format, the
`tests/fixtures/` vectors, and `locales/messages.json`. The Android and
Python clients mirror those files; `python3 scripts/check-sync.py` fails
when the mirrors diverge.

Quick build per client (run inside each directory):

```sh
# Rust (from repo root, requires Rust 1.95+)
cargo build --workspace --release --locked

# Android (from clients/android/, requires JDK 17)
./scripts/setup-android-sdk.sh  # first time only
./scripts/build.sh              # produces app/build/outputs/apk/debug/app-debug.apk

# Python (from clients/python/, requires Python 3.10+)
python3 -m venv .venv && .venv/bin/python -m pip install .
.venv/bin/python run_tests.py

# Paper plugin (from clients/plugin/, requires JDK 21)
./gradlew clean check
# Windows: .\gradlew.bat clean check
# produces build/libs/OpenRadPlugin.jar

# Coordination server (from server/, requires the Go version in go.mod)
go build -o openrad-server . && go test ./...
```

## Features

- Desktop and headless CLI clients backed by the same VPN engine.
- One persistent per-user VPN session shared by the desktop and CLI.
- Public-network discovery and password-protected private networks.
- Network favorites and saved public/private network selections with paced batch joins.
- Peer connections over direct TCP, reliable UDP, or relay transport.
- Authenticated peer RTT tests, optional relay-only mode, and editable device names.
- Saved outgoing broadcast inclusion/exclusion lists shared between desktop and CLI.
- Linux TAP and Windows TAP-Windows6 virtual Ethernet interfaces.
- English, Portuguese, Russian, and Vietnamese interfaces.
- OS-backed credential storage for desktop identities.
- Live network search, background release notices, and bounded identity-reset recovery.
- Low memory use: about 41 MB idle on Windows 10 versus about 199 MB for the official
  client in one user-reported comparison (76 peers); results vary.

![OpenRad 1.0.0 networks and peer RTT tests](docs/screenshots/1.0.0-networks.png)

The screenshot uses synthetic networks and peers rendered on Linux.

OpenRad interoperates with regular Radmin VPN for its supported functionality.
Whether peers can establish a direct connection depends on their
network conditions, including NAT or firewall restrictions; OpenRad can use a
relay when a direct path is unavailable. See the [architecture guide](docs/architecture.md)
for protocol and transport details.

## Download

Download the latest packages and SHA-256 checksums from the
[GitHub releases page](https://github.com/gringoestrangeiro/openrad/releases).
The current release is **[2.3.2](docs/releases/2.3.2.md)**; see the
[changelog](CHANGELOG.md) for earlier versions. It updates only the Minecraft
plugin and mod; the other packages are the 2.3.0 files and keep their names.
Windows is experimental and the Minecraft builds alpha.

- **Linux:** Extract the archive, keep `openrad` and `openrad-desktop` together,
  then follow the [Linux setup guide](docs/linux.md).
- **Windows:** Run `OpenRad-Setup-2.3.0.exe` from the release package. It installs the
  application and its TAP-Windows6 driver. See the [Windows guide](docs/windows.md)
  for installation, diagnostics, and current limitations.
- **Android:** Install `openrad-2.3.0-android.apk` (Android 8.0 or newer).
  See the [Android client guide](clients/android/README.md).
- **Minecraft server (Paper 1.17–1.21.x or 26.x, alpha):** Put the
  `OpenRadPlugin-2.3.2-paper-<versions>.jar` whose name includes your Paper version (for
  example `-1.21` or `-26.1.1-26.3`) in the server's `plugins/` folder; see
  [Minecraft server plugin](#minecraft-server-plugin-paper).
- **Minecraft client (Fabric 1.17–1.21.11 or 26.1–26.3, alpha):** Put exactly one
  `OpenRadClientMod-2.3.2-fabric-<versions>.jar`, the one whose range includes your game
  version (for example `-1.21-1.21.5` for 1.21.4), in the Fabric profile's `mods/` folder
  with the Fabric API; see the [mod guide](clients/mod/README.md).
- **Self-hosted server (beta):** Extract `openrad-server-v2.3.0-linux-x86_64.tar.gz`
  (or the experimental `openrad-server-v2.3.0-windows-x86_64.zip`, not yet validated on
  native Windows) and run `openrad-server serve`. Point the desktop (**Settings → Server**)
  or CLI (`openrad server set`) at it with its `modulus.bin`; see the [server guide](server/README.md).

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

Other useful commands:

```sh
./openrad ping PEER_RID
./openrad rename my-new-device-name
./openrad force-relay true
```

In the desktop, favorite networks with **☆**, select networks on **Auto join**,
and save a named configuration to reuse the selection. Enter private passwords
again when loading a list; configurations never store them. Batch joining starts
only when you choose **Join selected networks**.

Use **Settings → Outgoing broadcasts** to select several peers and send only
to them, or exclude them from normal distribution. The CLI supports
`openrad broadcast-peer RID [RID ...]` and `openrad broadcast-peer --exclude RID [RID ...]`.
Restore every eligible recipient with `openrad broadcast-peer --all`. The saved
selection applies without reconnecting; incoming broadcasts retain their current
behavior. See the [CLI guide](docs/cli.md#outgoing-broadcast-recipient) for routing
and eligibility details.

![Broadcast inclusion and exclusion in Portuguese](docs/screenshots/broadcast-multiple-peers-pt.png)

## Minecraft server plugin (Paper)

`OpenRadPlugin.jar` puts a **Paper 1.17–1.21.x or 26.x** server directly on a
Radmin VPN/OpenRad network. Players using the official Radmin VPN (or OpenRad)
join the server at the plugin's `26.x.x.x` address; the host needs no VPN
client, TAP/TUN driver, root access or native libraries, so it also works on
shared hosting. The plugin is **alpha**.

1. Copy the JAR for your Paper version into the server's `plugins/` folder and
   start the server.
2. In the **server console**, join your network (quote names with spaces):

   ```text
   orb join "My Network" networkPassword     # private network
   orb join "My Public Network"              # public network, no password
   ```

3. Run `orb status` and give players the printed `26.x.x.x` address.

Passwords are kept in the owner-only `secrets.properties`, never in the YAML,
and are refused when typed by a player. See the
[plugin guide (Portuguese)](clients/plugin/README.md) for building, commands,
configuration, ACLs and limits.

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
| [Paper plugin](clients/plugin/README.md) | Minecraft server plugin: install, `/orb join` setup, commands, configuration, and validation scope |
| [Coordination server](server/README.md) | Self-hosted server: run, ports, pointing clients at it, administration, and protocol coverage |
| [Architecture](docs/architecture.md) | Components, protocol, transports, and platform interfaces |
| [Performance](docs/performance.md) | Benchmark methodology and results |
| [Changelog](CHANGELOG.md) | Version history and older release notes |
| [Latest release (2.3.2)](docs/releases/2.3.2.md) | Changes, packages, upgrading, validation, and limitations |

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
| `server/` | Self-hosted Go coordination server and its end-to-end test |
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

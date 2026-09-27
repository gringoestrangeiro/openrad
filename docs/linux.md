# Linux setup

Use Rust 1.95 or newer. Build both binaries with:

```sh
cargo build --workspace --release --locked
```

A typical Debian/Ubuntu development setup needs these packages:

```sh
sudo apt install build-essential pkg-config libx11-dev libxkbcommon-dev libwayland-dev libgl1-mesa-dev iproute2 sudo gnome-keyring
```

Equivalent distribution packages are fine. X11 or Wayland, a usable OpenGL driver, and a desktop D-Bus session are required for the GUI. GNOME Keyring or a compatible unlocked Secret Service provider must be running. The headless CLI does not need a display or a credential-store service.

## TAP permissions

Confirm that `/dev/net/tun` is available. If the kernel module is not loaded, the system administrator can load it with `sudo modprobe tun`.

```sh
sudo -v
./target/release/openrad-desktop
```

The application invokes `sudo -n` for its short-lived helper. A valid sudo credential is needed when connecting. On systems where sudo credentials are tied to a terminal or parent process, `sudo -v` may not be sufficient for a graphical launch; arrange a narrowly scoped, administrator-managed helper authorization. Do not run the whole GUI as root or grant blanket passwordless access to a user-writable executable.

The helper currently expects the standard Linux locations `/usr/bin/sudo` and `/usr/bin/ip`. These are operating-system paths, not per-user installation paths. Distributions with a different layout must adapt `src/platform/linux.rs`. Both binaries can otherwise be built or installed in any directory.

The interface is named `radminvpn0`, has MTU 1400, and uses the assigned `26.x.x.x` address. OpenRad refuses to replace an interface that already uses that name. Desktop mode adds a connected `/8` LAN and broadcast/multicast routes; CLI test mode uses explicit peer host routes. Other applications using overlapping routes may affect traffic selection.

## Troubleshooting

- **Credential store unavailable:** start and unlock Secret Service in the same user session, then restart OpenRad. Existing identities are not silently replaced.
- **TAP helper executable missing:** build the whole workspace and keep `openrad` next to `openrad-desktop`.
- **TAP helper failed:** verify `/dev/net/tun`, the `ip` command, and sudo authorization. Launch from a terminal to see helper errors.
- **Interface already exists:** disconnect the other OpenRad instance or identify the interface's owner. The client deliberately leaves existing interfaces alone.
- **Connected but no peer traffic:** inspect each peer's path and counters, membership, application interface selection, and firewall rules. The service connection indicator does not prove a peer channel or a working direct path.

## Optional interface test

Ordinary workspace tests are headless and unprivileged. The following ignored test creates an ephemeral TAP interface, checks routes, and verifies cleanup. Run it only on a host where changing temporary network interfaces is appropriate and no OpenRad interface is active:

```sh
cargo build -p openrad-client --locked
sudo -v
cargo test -p openrad-client --test linux_interface --locked -- --ignored
```

It does not contact public peers. No GUI is required.

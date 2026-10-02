# Headless CLI

Build with `cargo build -p openrad-client --release --locked`. Run `openrad` as your normal user. The CLI now controls a persistent per-user service over a private Unix socket. Once started, the service keeps its server session, peer channels, and Linux TAP interface open when you close the terminal. It refreshes membership when the server reports a change and retries a lost server connection with bounded backoff. Peers that appear later are connected automatically; there is no 90-second run window.

On Windows 10/11 x64, run `OpenRad-Setup.exe --no-launch` to install the CLI and configure its dedicated TAP-Windows6 adapter without opening the desktop. Use the **OpenRad CLI** Start menu shortcut or an elevated PowerShell in `C:\Program Files\OpenRad`; see [Windows installation and testing](windows.md). The same commands control a detached per-user process over a local named pipe. The default profile is `%LOCALAPPDATA%\openrad`; the Windows process configures TAP directly, so its whole process requires elevation. Normal stopping removes session IP configuration while leaving the installed adapter. Windows support is experimental; successful Windows 10 operation has been reported by the tester. Other Windows versions and more networking configurations need validation.

## Language

The CLI uses the same automatic language detection as the desktop and supports English, Portuguese, Russian, and Vietnamese. Select a language for a command with the global `--language` option, including for `--help`:

```sh
openrad --language pt status
openrad --language ru --help
openrad --language vi search
openrad --language en status
```

The default `system` follows `LC_ALL`, `LC_MESSAGES`, and `LANG`, with GNU `LANGUAGE` preference lists when the message locale is not C/POSIX. Unsupported locales fall back to English. Display language remains a frontend preference; the CLI selects its own language for each invocation. The desktop and CLI share the same VPN profile and session. Human-readable output and argument errors are translated, while command names, flags, user-provided names and paths, `--json` replies, and diagnostic records keep their stable values.

## First use

```sh
./target/release/openrad init --node-name my-device
./target/release/openrad start
./target/release/openrad status
```

`init` registers one identity and saves it under `$XDG_STATE_HOME/openrad/profile/identity.json`, or `~/.local/state/openrad/profile/identity.json` when `XDG_STATE_HOME` is unset. The profile directory is private (`0700`), and the identity file is private (`0600`). Keep a backup of the identity and do not share it. If you already have a CLI identity, import it without registering again:

```sh
./target/release/openrad init --identity profiles/main/identity.json
```

The shared registration flow retries transient connection and handshake failures before registration up to three times per server, with one- and two-second delays and a 90-second aggregate network budget. It never automatically resends a registration whose response was lost. Stages and full error chains are recorded in `cli-startup.log` in the OpenRad logs directory; `OPENRAD_LOG_DIR` overrides that directory. On Linux it is normally `~/.local/share/OpenRad/logs`. These diagnostics exclude reusable credentials and packet contents.

Use `--data-dir PATH` with **every command** for a separate profile. This includes the daemon process started by `start`. Both frontends use this same path. When no CLI profile has been initialized, an existing desktop profile with saved settings is reused to retain its credential-store identity. `init` refuses to overwrite an existing profile or a running session. A failed or interrupted registration can leave an incomplete profile; check whether an identity was issued before deciding to retry with a new profile.

Before registering a device, `init` must confirm that this profile has no saved
credential-store identity. Unlock/start the credential store if that check fails;
an unreadable store does not authorize registering another device. An explicit
`init --identity PATH` import still works without a credential store and does
not register a device. Password and identity files must be regular files;
password files are limited to 4 KiB and identity files to 64 KiB.
An incomplete private `profile/` directory or broken identity link is retained
for recovery and does not cause the desktop to register another identity.

`start` returns when the local service is ready to accept commands. Authentication can still be in progress; `status` shows `connecting`, `connected`, or `reconnecting` and the last connection error. The service continues reconnecting without a terminal, using the profile’s saved retry settings. Starting twice is safe, including while the desktop is open. Opening the desktop after `start` attaches to this same session; starting the CLI after the desktop connects reuses its session. Closing the desktop window leaves the service running. **Disconnect** in the desktop or `stop` affects both frontends. A service whose retry budget is exhausted can be restarted with `start`. `stop` closes the session and removes the nonpersistent TAP interface. `start --no-tap` keeps only the service and peer connections, useful when interface setup is unavailable.

The service runs as your user. Its short-lived TAP helper first tries `sudo -n`, then requests authorization through the desktop session's Polkit agent. Allow the system permission dialog. If interface setup fails or authorization is cancelled, run `openrad retry-interface`. A terminal-bound `sudo -v` timestamp does not reliably authorize the detached service. Headless systems without an authentication agent need administrator-managed helper authorization; see [Linux setup](linux.md#tap-permissions). Do not run the whole service as root.

## Networks and peers

```sh
./target/release/openrad networks
./target/release/openrad peers
./target/release/openrad ping PEER_RID
./target/release/openrad rename my-new-device-name
./target/release/openrad force-relay true
./target/release/openrad search minecraft
./target/release/openrad join 'Example Public Network'
```

`networks` shows the exact name, network ID, and your role. A role marked **pending approval** cannot forward traffic until an administrator approves it. `peers` shows live states such as online, connecting, connected, offline, and failed, including the chosen transport when connected. `search` supports `--cursor NUMBER` for later pages. Commands return after the server acknowledges their operation; a refusal exits nonzero. If an operation times out, check `status` and `networks` after reconnection before retrying, since the remote outcome may be unknown.

`ping` tests a connected peer’s authenticated tunnel path with a 3000 ms deadline. `rename` persists the node name while preserving identity, address, credentials, and memberships; an active session reconnects to advertise it. `force-relay true` persists relay-only transport policy and reconnects active channels; `force-relay false` restores normal direct transport selection. These changes are visible in the desktop.

The service keeps watching for presence and membership changes. One person can create a private network, then another can join it later by its exact name and password. Both devices must be online at the same time **to exchange traffic**, but neither needs to race a short CLI session just to manage membership. Server-side administrator approval, if required by that network, still applies.

## Private networks

Passwords are read from UTF-8 files, never command arguments. One trailing LF or CRLF is removed. The password must contain 6–256 characters. For example, in Bash:

```sh
umask 077
read -r -s -p 'Network password: ' network_password
printf '\n'
printf '%s\n' "$network_password" > network-password.txt
unset network_password

./target/release/openrad create 'Friends LAN' --password-file network-password.txt
```

The other device can later run:

```sh
./target/release/openrad join 'Friends LAN' --password-file network-password.txt
```

Share the password file securely and remove extra copies when they are no longer needed. Network names and passwords are used exactly as entered. The file is read by the CLI and sent only through its owner-only local socket to the service; the service does not save network passwords.

## Administration and lifecycle

```sh
./target/release/openrad grant-admin 'Friends LAN' MEMBER_RID
./target/release/openrad revoke-admin 'Friends LAN' MEMBER_RID
./target/release/openrad kick 'Friends LAN' MEMBER_RID
./target/release/openrad leave 'Friends LAN'
./target/release/openrad delete 'Friends LAN' --yes
./target/release/openrad retry-peers
./target/release/openrad stop
```

Names must match exactly; use the ID from `networks` when a name is ambiguous. The server enforces administrator permissions. Kicking a member does not permanently ban them if they still know the password. Deletion removes the network for everyone and requires `--yes`.

Use `--json` for scripts, for example `openrad --json status`. This includes a full connection snapshot. The local socket, lock, service log, and bounded rotating diagnostics are under the private data directory. `service.log` is useful if the service fails to start; connection diagnostics are under `diagnostics/`. These files can contain peer IDs, addresses, and network names, but should never contain passwords, session keys, or packet payloads.

`init --host IPV4_ADDRESS --modulus FILE` remains available for controlled deployments with a different registration endpoint and raw public RSA modulus. An imported identity can also use `--modulus FILE`. The modulus is saved with the profile so later `start` commands use the same key.

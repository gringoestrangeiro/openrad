# Headless CLI

[Guia em português](cli-pt-BR.md).

This guide describes the current source tree, including `broadcast-peer`.
Use the CLI and desktop binaries built from the same source. If a command is
missing from an installed binary's `--help`, use the newly built binaries and
restart an older running service once to load the new command protocol.

Build with `cargo build -p openrad-client --release --locked`. Run `openrad` as your normal user. The CLI now controls a persistent per-user service over a private Unix socket. Once started, the service keeps its server session, peer channels, and Linux TAP interface open when you close the terminal. It refreshes membership when the server reports a change and retries a lost server connection with bounded backoff. Peers that appear later are connected automatically; there is no 90-second run window.

On Windows 10/11 x64, run `OpenRad-Setup.exe --no-launch` to install the CLI and configure its dedicated TAP-Windows6 adapter without opening the desktop. Use the **OpenRad CLI** Start menu shortcut or an elevated PowerShell in `C:\Program Files\OpenRad`; see [Windows installation and testing](windows.md). The same commands control a detached per-user process over a local named pipe. The default profile is `%LOCALAPPDATA%\openrad`; the Windows process configures TAP directly, so its whole process requires elevation. Normal stopping removes session IP configuration while leaving the installed adapter. Windows support is experimental; successful Windows 10 operation has been reported by the tester. Other Windows versions and more networking configurations need validation.

## Syntax and global options

```text
openrad [OPTIONS] COMMAND [ARGUMENTS] [COMMAND_OPTIONS]
```

The examples below use `openrad` on `PATH`. In a Linux source checkout, use
`./target/release/openrad`; from an extracted archive, use `./openrad`. In
PowerShell, use `.\openrad.exe` from the binary's directory.

| Option | Purpose |
| --- | --- |
| `--data-dir PATH` | Select the profile shared by the CLI and desktop. Use it on every command for that profile. |
| `--language system\|en\|pt\|ru\|vi` | Select the display language for this invocation; default: `system`. |
| `--json` | Print a machine-readable reply. |
| `-h`, `--help` | Show general or command-specific help. |
| `-V`, `--version` | Show the binary version. |

```sh
openrad --help
openrad join --help
openrad broadcast-peer --help
openrad --version
```

## Command reference

Arguments in uppercase below are placeholders. Put names containing spaces in
quotes. Administration commands use a member RID, not an IP address.

| Command | Purpose |
| --- | --- |
| `init --node-name NAME` | Register and save a new identity once. |
| `init --identity FILE` | Import an existing identity without registering another device. |
| `start [--no-tap]` | Start or reuse the shared background service. |
| `status` | Show service, interface, peers, and traffic state. |
| `stop` | Stop the shared VPN session. |
| `networks` | List joined networks, IDs, and roles. |
| `peers` | List peers, their RIDs, VPN addresses, states, and transports. |
| `search [QUERY] [--cursor NUMBER]` | Search public networks or fetch a later page. |
| `join NETWORK [--password-file FILE]` | Join a public network or a private network using a password file. |
| `create NETWORK --password-file FILE` | Create a private network. |
| `leave NETWORK` | Leave a network by exact name or ID. |
| `delete NETWORK --yes` | Delete an administered network for all members. |
| `kick NETWORK MEMBER_RID` | Remove a member from an administered network. |
| `grant-admin NETWORK MEMBER_RID` | Grant administration to a member. |
| `revoke-admin NETWORK MEMBER_RID` | Revoke a member's administration. |
| `retry-peers` | Queue recovery attempts for failed peer connections. |
| `retry-interface` | Retry TAP setup after resolving interface/permission problems. |
| `ping PEER_RID` | Measure RTT through an authenticated peer channel. |
| `rename NAME` | Save the device name while preserving its identity. |
| `broadcast-peer [TARGET] [--all]` | Query or select the sole outgoing broadcast recipient, or restore all recipients. |
| `force-relay true\|false` | Save or clear relay-only transport policy. |

Aliases: `provision` for `init`, `public-networks` for `search`,
`create-network` for `create`, and `delete-network` for `delete`.

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

## Outgoing broadcast recipient

```text
openrad broadcast-peer [TARGET]
openrad broadcast-peer --all
```

Use `broadcast-peer` to send all outgoing Ethernet broadcasts, including IPv4
broadcasts and ARP announcements, to a single peer. Incoming broadcasts still
arrive from every authorized peer. The setting is shared with the desktop and
saved by RID; selecting by exact name or VPN IP requires a loaded peer roster.
An unavailable or ineligible target causes these outgoing frames to be dropped,
with no fallback to other peers. Unicast and multicast keep their normal routing.
The original frame addresses and payload remain unchanged. A broadcast ARP
request for another device is sent only to the selected peer too; its normal
receive validation may discard it, so selecting a single recipient can prevent
ARP resolution of other peers.

```sh
openrad broadcast-peer                    # Show the current setting.
openrad broadcast-peer 123456             # Select a RID, even while disconnected.
openrad broadcast-peer 26.1.2.3           # Select a unique peer by VPN IP.
openrad broadcast-peer 'Synthetic friend' # Select a unique peer by exact name.
openrad broadcast-peer 0.0.0.0            # Restore broadcasts to all eligible peers.
openrad broadcast-peer --all              # The same restoration.
```

Changes apply to new outgoing frames without reconnecting the active VPN.

| Target | Behavior |
| --- | --- |
| No argument | Query the current setting without changing it. |
| Nonzero RID | Save that exact identity as the recipient; the service may be stopped. A RID need not already be present in the roster, but it receives traffic only when eligible and connected. |
| VPN IPv4 address | Resolve a unique roster member and save its RID. |
| Exact device name | Resolve a unique roster member and save its RID. Numeric names are interpreted as RIDs; use the actual RID to avoid ambiguity. |
| `0.0.0.0` | Clear the restriction and restore normal distribution. |
| `--all` | Clear the restriction; cannot be combined with a target argument. |

`0` is not a valid recipient RID. Names and IPs with multiple matches are
rejected; choose the RID reported by `openrad peers`. A selected name or IP is
resolved once, so later renames/address changes do not retarget the preference.
Querying, selecting a RID, and restoring all peers work while disconnected and
do not start the service.

Normal IPv4 broadcast distribution includes `26.255.255.255` and
`255.255.255.255` when the frame reaches the VPN interface. The client sends
separately through each eligible peer channel, using direct TCP/UDP or relay
as appropriate. Restricting the recipient changes that local selection; it
does not turn the packet into IP unicast or ask the server to distribute it.
Received group frames are delivered to the local interface, not flooded to
other peers. With the restriction cleared, recipients can span all networks
shared with this profile; the broadcast frame does not select a named network.

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

## JSON and exit status

Service replies have `ok`, `message`, and `data` fields. For example, querying a
saved synthetic recipient with `openrad --json broadcast-peer` returns:

```json
{"ok":true,"message":"Outgoing broadcasts: RID 123456","data":{"broadcast_peer":123456}}
```

`data.broadcast_peer` is `null` when normal distribution is enabled. JSON keys
and messages retain their stable values regardless of `--language`. A
successful invocation exits with `0`; a failure exits nonzero. Errors before a
reply can be produced are written to stderr and may produce no JSON on stdout,
so scripts must check the process exit status too.

## Troubleshooting

| Symptom | Check or action |
| --- | --- |
| Service is stopped | Run `start`, then inspect `status`. |
| Interface is unavailable | Resolve the Linux helper authorization or Windows TAP/elevation requirement, then run `retry-interface`. |
| Broadcast target name/IP cannot be selected | Wait for the roster to load, inspect `peers`, or select a RID. |
| Broadcast target is ambiguous | Use its RID instead of a repeated name/IP. |
| Broadcasts no longer reach other peers | Inspect `broadcast-peer`; use `broadcast-peer --all` to restore distribution. |
| Configured recipient does not receive broadcasts | Confirm it is connected, authorized, and permitted by the traffic policy; frames are not held for later delivery. |
| Other peers' ARP resolution fails in restricted mode | Restore all recipients or account for directing ARP broadcasts to the selected peer. |
| New CLI command is rejected by the running service | Stop the older service and start the CLI/desktop binaries from the same new build. |

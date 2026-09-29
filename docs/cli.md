# Headless CLI

Build with `cargo build -p openrad-client --release --locked`. Run `openrad` as your normal user. The CLI now controls a persistent per-user service over a private Unix socket. Once started, the service keeps its server session, peer channels, and Linux TAP interface open when you close the terminal. It refreshes membership when the server reports a change and retries a lost server connection with bounded backoff. Peers that appear later are connected automatically; there is no 90-second run window.

## First use

```sh
./target/release/openrad init --node-name my-device
sudo -v
./target/release/openrad start
./target/release/openrad status
```

`init` registers one identity and saves it under `$XDG_STATE_HOME/openrad/profile/identity.json`, or `~/.local/state/openrad/profile/identity.json` when `XDG_STATE_HOME` is unset. The profile directory is private (`0700`), and the identity file is private (`0600`). Keep a backup of the identity and do not share it. If you already have a CLI identity, import it without registering again:

```sh
./target/release/openrad init --identity profiles/main/identity.json
```

Use `--data-dir PATH` with **every command** for a separate profile. This includes the daemon process started by `start`. `init` refuses to overwrite an existing profile. A failed or interrupted registration can leave an incomplete profile; check whether an identity was issued before deciding to retry with a new profile.

`start` returns when the local service is ready to accept commands. Authentication can still be in progress; `status` shows `connecting`, `connected`, or `reconnecting` and the last connection error. The service continues reconnecting without a terminal. Starting twice is safe. `stop` closes the session and removes the nonpersistent TAP interface. `start --no-tap` keeps only the service and peer connections, useful when interface setup is unavailable.

The service runs as your user. Only its short-lived TAP helper invokes `sudo -n`. Run `sudo -v` before `start`; if interface setup fails, refresh sudo authorization and run `openrad retry-interface`. A sudo configuration tied to one terminal may require an administrator-managed helper authorization for detached use. Do not run the whole service as root.

## Networks and peers

```sh
./target/release/openrad networks
./target/release/openrad peers
./target/release/openrad search minecraft
./target/release/openrad join 'Example Public Network'
```

`networks` shows the exact name, network ID, and your role. A role marked **pending approval** cannot forward traffic until an administrator approves it. `peers` shows live states such as online, connecting, connected, offline, and failed, including the chosen transport when connected. `search` supports `--cursor NUMBER` for later pages. Commands return after the server acknowledges their operation; a refusal exits nonzero. If an operation times out, check `status` and `networks` after reconnection before retrying, since the remote outcome may be unknown.

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

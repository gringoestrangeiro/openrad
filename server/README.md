# openrad-server

A self-hosted coordination server, written in Go, that speaks the same wire
protocol as the OpenRad clients (the Radmin VPN control protocol). OpenRad
clients register, log in, manage networks and set up peer channels through it
unchanged. State is persisted in SQLite (pure-Go driver, no cgo), so the binary
is a single static executable.

Status: **alpha**. Linux x86_64 is the tested platform. The Windows x64 build
is **experimental**: it is cross-built, imports only `kernel32.dll` and passes
the end-to-end suite under Wine, but has not been validated on native Windows.

## Download

Release packages ship separately from the VPN client:

- `openrad-server-vVERSION-linux-x86_64.tar.gz`: static `openrad-server`
- `openrad-server-vVERSION-windows-x86_64.zip`: `openrad-server.exe` (experimental)

Each archive includes this guide, license notices for Go and every linked
module, `BUILD-INFO.json` and `SHA256SUMS`.

## Build

Requires the Go version in [`go.mod`](go.mod). From this directory:

```sh
go build -o openrad-server .
go test ./...
# Windows x64 (cross-build from any host)
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o openrad-server.exe .
```

Release archives are built by `python3 scripts/package-server.py --build-date
YYYY-MM-DD` from the repository root. It stamps the OpenRad release version
(`-X main.version=...`), so `openrad-server --version` matches the clients.

## Run

```sh
./openrad-server serve --public "My Public Network"
```

The database holds the server's RSA private key and every identity's
credential verifier. Treat it like a private key: it is created owner-only
(`0600`, in a `0700` directory) and an existing Unix file readable by others is
tightened on open. Its location, in order of precedence:

1. `--db FILE` (a global flag, before the command)
2. the `OPENRAD_SERVER_DB` environment variable
3. `openrad-server/openrad-server.db` under the user state directory:
   `$XDG_STATE_HOME` or `~/.local/state` on Linux, `%LOCALAPPDATA%` on Windows

On first start the server generates a 2048-bit RSA key (`--rsa-bits`), stores
it in the database and writes the public modulus to `modulus.bin` next to the
database (`--modulus-out`). It listens on:

| Port | Proto | Purpose |
| --- | --- | --- |
| 17301 | TCP | control sessions (`--listen`) |
| 17302 | TCP | relay (`--relay-listen`, `--relay-port`) |
| 17301–17399 | UDP | endpoint discovery (UES), only with `--ues-host` |

Open these in the firewall. Only Rust clients can reach a control port other
than 17301 (see below); the UES range is fixed by every client.

A minimal systemd unit:

```ini
[Service]
ExecStart=/usr/local/bin/openrad-server --db /var/lib/openrad-server/openrad-server.db serve --public-ip 203.0.113.10
StateDirectory=openrad-server
DynamicUser=yes
Restart=on-failure
```

### Pointing clients at it

Clients encrypt their session secret to the server's RSA public key, so each
client needs this server's `modulus.bin` and IPv4 address. Identities are per
server: a client registers a new device the first time it uses this server.

| Client | How |
| --- | --- |
| Rust desktop | **Settings → Server**: IPv4, control port and the `modulus.bin` file |
| Rust CLI | `openrad server set --host SERVER_IP [--port PORT] --modulus modulus.bin`, then `openrad init --node-name NAME` and `openrad start`; `openrad server reset` returns to the official server |
| Python | `python3 openrad.py --data-dir ./profile run --host SERVER_IP --modulus modulus.bin --mode server` (port 17301 only) |
| Android | debuggable builds only: `files/debug-endpoint.json` with `host`, `port` and `modulus` |
| Paper plugin / Fabric mod | not supported: the modulus is compiled in |

* `--host` must be an IPv4 address reachable by every client: the server hands
  it out as the identity's home server and as peers' coordinator.
* By default the server advertises whichever local address each client
  connected to. Behind NAT or port forwarding, set `--public-ip`.
* The Rust client never advertises loopback addresses as direct candidates.
  Use a LAN or public IP for Rust clients; `127.0.0.1` only suits the Python
  client (Rust peers then fall back to the relay).

### serve flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--listen` | `0.0.0.0:17301` | control address |
| `--relay-listen` / `--relay-port` | `0.0.0.0:17302` / listener port | relay address / advertised port |
| `--public-ip` | per-connection local address | IPv4 advertised to clients |
| `--ues-host IP` | none | UES host; repeat for two distinct routable IPs |
| `--public NAME` | none | ensure a public network exists (repeatable) |
| `--auto-public` | off | a public join of an unknown name creates it |
| `--allow-public-create` | off | create requests with a nonzero type make public networks |
| `--registration-limit` | 30 | new identities per source IP per hour (0 = unlimited) |
| `--modulus-out` | `<db dir>/modulus.bin` | modulus file path |
| `--rsa-bits` | 2048 | key size, only when the key is first generated |
| `-v` | off | verbose protocol logging |

### Administration

```sh
openrad-server [--db DB] modulus -o modulus.bin
openrad-server [--db DB] network list
openrad-server [--db DB] network add-public "Name" [--capacity 150]
openrad-server [--db DB] network set "Name" --approval on|off [--capacity N]
openrad-server [--db DB] network members "Name"
openrad-server [--db DB] network set-role "Name" RID pending|member|admin
openrad-server [--db DB] network remove-member "Name" RID
openrad-server [--db DB] network delete "Name"
openrad-server [--db DB] user list | user delete RID
```

These commands edit the database directly. The running server reads network
settings live (approval, capacity, new public networks), but clients that are
online see membership edits only after they reconnect.

## Implemented protocol

* **Session bootstrap:** framed `03000000` marker, RSA PKCS#1 v1.5 RSession
  (purposes 3/4/5), CChannel (AES-256-CBC with the MD4-based auth trailer,
  chained IVs kept across rekey), SH (SRP-style) server proof, rekey to the SH key.
  Credentials are stored only as salt + verifier.
* **Registration (purpose 3):** bootstrap selectors 1–1000 with the protocol's
  fixed bootstrap password; LoginComplete (21) then Registered (8): RID,
  26.0.0.0/8 VIP, fdfd:: companion address, credential and home server.
* **Attachment (purpose 4):** LoginComplete with server ID and UES hosts,
  then a Networks snapshot (38) with own address, networks, roles, members and
  presence. Heartbeats (4). A second login of the same identity replaces the
  first, which receives op 16.
* **Presence and renames:** member upserts (41/0x131e) when someone comes
  online or changes name, presence changes (0x1366) when they go offline.
* **Public networks:** search (43 → 45, paged, case-insensitive), join (39 →
  37 + JoinApproved 42), optional capacity.
* **Private networks:** create with a client-supplied verifier (52/1), join
  with the SH password exchange inside op 39/40 (correct, wrong and missing
  passwords), optional admin approval (role 0 = pending).
* **Management (52):** leave (3), delete (7), kick (4), grant admin (8),
  revoke admin (9). Admin-only actions are enforced (error 5), as is the
  last-admin rule (error 19). Kicks, role changes, leaves and deletions are
  broadcast to members (0x131d, 0x1318, 0x132e). Revoking admin from a pending
  member approves them, which is how OpenRad admins approve applicants.
* **Peer brokering (purpose 5):** connect login → LoginComplete with server
  ID → NewConnection (11) to both peers with a shared secret. Forwarded:
  TCP candidate requests and advertisements (2 → 6, plus the advertiser's
  observed public address), local UDP candidates and nonce (28 → 29), mapped
  UDP endpoints (3 → 7), relay requests (22 → 23). Refusal is 44.
* **Relay:** 256-byte per-side tickets. The ticket hello is answered with
  `0x010001df = 2` once both sides arrive, then bytes are spliced.
* **UES:** two-host UDP endpoint discovery on ports 17301–17399.

Not implemented: server-to-server redirects (op 12; a single server has
nowhere to redirect) and server text messages (op 9; format unknown). Clients
treat both as optional.

## Tests

* `go test ./...` checks the crypto against the Rust client's synthetic
  vectors in [`../tests/fixtures/`](../tests/fixtures/) (SH server transcript,
  CChannel records and rekey, network identity and verifier). The fixtures are
  read in place; there is no server-side mirror to keep in sync.
* `python3 scripts/e2e_python.py [--server PATH]` builds the server (unless
  `--server` is given) and drives two of this repository's Python clients
  through registration, public join, direct TCP, ping, UDP echo, search,
  private networks (wrong, missing and correct passwords), leave, delete, the
  last-admin rule, relay-only transport and offline presence. TCP port 17301
  on `127.0.0.1` must be free.

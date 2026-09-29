# Desktop and identities

Build the workspace and keep both binaries together. Start `openrad-desktop` from an ordinary user session with the credential store unlocked. The desktop's network operations run in a background engine so handshakes and packet forwarding do not block the interface.

## Connection and network management

On first connection, OpenRad provisions a new identity and saves it to the OS credential store. Later connections reuse that identity. **Settings** offers:

- **Connection:** connect on launch, reconnect after failures, maximum retry attempts (1–10), and the initial retry delay (1–30 seconds). Later retries double the delay, capped at five minutes. The device name is editable until the identity is created.
- **Workspace:** the page shown at startup, interface scale, traffic overview and graphs, decimal or binary traffic units, visibility of offline peers, peer sorting, and the number of recent activity events shown.
- **Developer view:** inline peer connection details, internal IDs, and live session, interface, peer, and frame counters. **Copy diagnostic summary** copies aggregate counters without credentials or packet contents.

Workspace display changes preview immediately. Use **Save preferences** to keep them after restart, **Discard changes** to return to the last saved values, or **Restore defaults** to prepare the defaults for saving. The created identity's name is preserved when restoring defaults. Settings remain separate from credentials and are saved per profile.

In **Your networks**, choose **Create private network** or **Join private network**. Enter the exact network name and password; creation also asks you to confirm the password. Passwords are masked by default and are not saved in your profile. **Browse public** opens the existing public-network catalog.

Select a network to see your role and each member's role. Administrators have a **Manage** menu beside each member with **Remove member**, **Grant admin**, or **Revoke admin**. Each change shows a confirmation naming the member and network. The menu works for offline members too, and is disabled while a command is running. Role changes and removals reported by the service update the view.

**Leave network** removes your own membership. The service may prevent the last administrator from leaving: grant admin to another member first, or use **Delete network**. Deletion requires confirmation and removes the network for everyone. Removing a member is not a permanent ban; someone who still knows the password may rejoin.

Server refusals and password failures appear in the notification and Recent activity. If an operation times out, reconnect to load the service's current membership before retrying. A network that requires administrator approval is shown as **Pending approval** and is excluded from forwarding until approved.

The peer table distinguishes Direct TCP, Direct UDP, and Relay. These labels represent authenticated peer channels. They are separate from the overall service connection status. Incoming channels and outgoing channels use the same authentication and Ethernet forwarding checks.

Transient peer failures and refused connections retry automatically: 1.5–4.5 seconds
after the first failure, 3–9 after the second, 6–18 after the third, then
12–36 seconds. Recovery runs alongside initial setup and prioritizes peers that
were previously connected. Depending on the recovery demand, it allows 16, 32,
or 48 outgoing retries in progress, with 200, 100, or 50 milliseconds between
starts. If at least three quarters of eight or more recent retries fail, the
limit falls to at most 32 and starts are spaced by at least 150 milliseconds.
Due retries reserve outgoing setup slots while new peers are also connecting.
**Retry** uses the same adaptive queue, retaining failure history and already
queued earlier deadlines.
The network page shows failed/refused, offline, active retry, and queued retry
counts so recovery can be monitored separately from the total roster.
Incoming offers can bypass the wait to preserve their rendezvous window.
Roster-only members without a server endpoint are
not scheduled for connections. Both online presence states preserve established
channels; offline, address/server changes, and revoked membership still cancel
the affected connection.

Public-network search timeouts and a full command queue report an operation
error while keeping the connection running. A timed-out membership mutation
still requires reconnection to resolve its uncertain outcome.

The Linux desktop uses a `26.0.0.0/8` virtual LAN and group routes for broadcast and multicast. Disconnecting closes the TAP descriptor and removes the temporary interface and its routes.

IPv4 broadcast, multicast, and gratuitous ARP requests/replies are forwarded to
eligible authenticated peers. The TAP can be ready before peer handshakes finish,
so OpenRad sends a broadcast gratuitous ARP reply announcing its TAP IP/MAC to each
newly authenticated channel. Recreating the TAP announces it to existing channels
as well. These 42-byte frames use the TAP's current MAC, which can differ from the
old Wine adapter's MAC. Forwarded frames retain their original bytes; source
IP/MAC validation and membership restrictions still apply.

## Diagnosing connection drops

The desktop automatically records connection diagnostics in the profile's
`diagnostics` directory. **Settings → Copy connection log path** copies its exact
location, including for profiles selected with `--data-dir`. On a standard Linux
profile this is normally `~/.local/share/openrad/diagnostics`.

The current log is `connection.jsonl`. Three older files, `connection.1.jsonl`
through `connection.3.jsonl`, retain earlier events, with `.1` the most recent
archive. Each file is limited to 4 MiB, for at most 16 MiB total. Logging uses a
bounded background queue so disk writes cannot hold up network heartbeats. Log
queue overflows and write failures are counted in subsequent records. A log-open
failure is shown in Recent activity; a later write failure is also printed to
the launch terminal.

Each JSON line has a wall-clock timestamp in milliseconds, process uptime,
process ID, session number, event name, and `details`. Session numbers distinguish
automatic reconnects within one process. Events include:

- `session_end` and `session_reconnect`: the full error chain, uptime, number of
  connected peers at shutdown, and whether another session is scheduled.
- `control_closed` and `server_disconnect`: the last service operation, last
  receive age, heartbeat count, and any explicit disconnect reason code. Unknown
  disconnect fields are described by tag and size without recording their data.
- `peer_connecting`, `peer_transport_attempts`, `peer_connected`, and `peer_closed`:
  peer/attempt IDs, candidate transport results, selected endpoint, channel
  lifetime, last receive age, keepalive counts, and the error that closed it.
- `peer_cancelled` and `membership_updated`: distinguish roster/binding changes
  from transport failures. `peer_retry_scheduled` records individual retry delays.
- `peer_address_announcement`: records whether the local gratuitous ARP was queued
  for an authenticated peer; the frame itself is not logged.
- `session_health` and `control_health`, every 30 seconds: roster, eligible and
  connected counts, handshakes, traffic/drop counters, engine delays, and control
  heartbeat timing. `peer_drop_burst` flags a fall of more than half the connected
  peers between UI snapshots, starting from at least ten connected peers.
- `control_event_backpressure`, `control_event_queue_recovered`, and
  `control_heartbeat_delayed`: identify scheduling or queue pressure.

After another mass disconnect, preserve all four available log files and note
the approximate time and network. The logs include peer IDs and endpoint
addresses, but exclude reusable identities, passwords, session keys, and packet
contents. Files are created with owner-only permissions on Unix. Recent activity
also includes the peer failure detail instead of only the status label.

## Reset identity

The **Reset identity** action opens a confirmation dialog explaining that the device identity and network memberships will change. Confirming it:

1. Disconnects the running session and closes the TAP interface.
2. Provisions a replacement through the normal registration flow.
3. Saves the new identity in the credential store only after provisioning succeeds.
4. Updates the active identity only after the credential-store write succeeds.

If provisioning fails, the existing saved identity remains intact. If provisioning succeeds but the credential store cannot save it, the replacement remains in memory for a storage retry; retrying does not provision another identity. Keep the window open until the credential store is unlocked and the save succeeds. The confirmation UI and close handling warn about an unsaved replacement.

New identities do not inherit the previous identity's memberships. Resetting is not a way to recover access to a lost identity.

## Profiles and imports

The default profile directory comes from the platform's application-data location. `--data-dir PATH` selects an independent profile. The canonical profile path identifies the corresponding credential-store entry, so moving a profile directory does not automatically move its saved identity.

```sh
./target/release/openrad-desktop --data-dir ./profiles/alternate
```

`--identity FILE` imports a saved CLI identity into that profile's credential store. An import cannot overwrite a different identity already saved in the same profile. Identity files contain reusable credentials; store them outside source control and delete redundant copies only after verifying the import.

Settings are written separately from credentials. A profile lock prevents two desktop instances from using the same profile concurrently. Do not run two clients with the same identity at the same time.

For controlled traffic checks, `--traffic-peer RID` can be repeated to limit desktop application traffic to selected peers. Ordinary desktop mode allows traffic to eligible joined-network members.

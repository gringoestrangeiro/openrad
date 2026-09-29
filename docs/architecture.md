# Architecture

OpenRad is a Cargo workspace with a reusable library (`openrad`), a CLI (`openrad`), and a desktop executable (`openrad-desktop`). The engine has no GUI dependency.

| Module | Responsibility |
| --- | --- |
| `config` | Shared public bootstrap endpoint and RSA modulus |
| `crypto` | Registration/session cryptography and mutual peer authentication |
| `protocol` | Bounded message parsing, memberships, and candidate validation |
| `network` | Shared public/private join authentication and correlated network administration commands |
| `session` | Framed service connection and session lifecycle |
| `peer` | Outgoing transport selection, authentication, and relay fallback |
| `incoming` | Bounded incoming offers, candidate setup, and acceptor authentication |
| `udp` | UDP endpoint discovery, rendezvous, and reliable datagrams |
| `tunnel` | Ethernet envelopes, address checks, and forwarding rules |
| `tap` / `platform` | Linux interface setup and descriptor lifecycle |
| `runtime` | Long-lived engine shared by the desktop and CLI service, with live snapshots |
| `client` | Bounded diagnostic sessions retained in the library |
| `output` | Private JSON reports and explicit identity persistence |
| `src/daemon.rs` | Per-user CLI service, Unix-socket commands, reconnection supervisor, and profile isolation |

## Peer transport lifecycle

The authenticated service connection supplies membership, peer identities, connection credentials, and direct candidates. Candidates are validated and correlated with the intended peer/connection before use. Incoming offers are restricted to eligible joined-network members, with pending-offer and worker limits.

Direct TCP and UDP candidates start as they arrive, within time budgets. UDP endpoint discovery runs independently, queries two service-provided hosts concurrently, and requires consistent mapped addresses before advertising the mapping. There is no arbitrary host scanning or predicted-port search. A service-issued relay ticket starts a competing attempt after a short direct-path head start. The first path to complete peer authentication and the Ethernet service handshake wins; losing workers are cancelled and joined. See [performance](performance.md) for limits and timing.

Both incoming and outgoing channels perform mutual authentication and the tunnel service handshake. A socket that merely connects does not become an authenticated peer channel. Transport reports separate path, authentication, service-handshake completion, and transferred traffic. Simultaneous incoming/outgoing attempts are resolved deterministically while retaining a working channel.

A path reported as Direct TCP or Direct UDP identifies the selected peer socket. Measuring useful direct connectivity also requires observing traffic on that channel; a login indicator or empty socket is insufficient. Local tests cannot predict direct success rates across live NATs and firewalls.

## Privileges and persistence

The CLI and desktop use the same `NetworkOperation` state machine for creation, joining, leaving, deleting, kicking, and changing admin roles. The CLI service tags each operation so its result reaches the requesting command. Private joins verify the network's SH server proof before accepting membership. Administration acknowledgements must match the request, action, context, network, and member. Role and removal notifications update both clients' snapshots and forwarding membership. A kick only removes the affected network relationship; peers sharing another approved network remain eligible. Pending applicants are not eligible for traffic through that network.

Network passwords use masked desktop fields or a CLI password file, are redacted from command debugging, and are not stored in settings or operational reports. A timeout leaves a mutation's outcome unknown; the desktop disconnects and reloads service state before accepting further operations.

The application runs unprivileged. The Linux adapter invokes a short-lived helper through `sudo` to create a nonpersistent TAP interface, configure it, and pass its file descriptor back over a Unix socket. Closing the last descriptor removes the interface and associated routes. Existing interfaces are not replaced.

The desktop stores identities in the platform credential store and settings in its profile directory. Provisioning and reset logic are isolated from the GUI; tests use an in-memory vault. The CLI saves a provisioned or imported identity under its private data directory. A per-user Unix socket accepts bounded local commands; a lock prevents a second service for the same profile. The service reuses the desktop engine to keep peer channels and TAP alive between commands and reconnects to the server after failures.

Operational reporting does not log session keys, authentication passwords, handshake secrets, or Ethernet payloads. Synthetic fixtures exercise protocol and cryptographic behavior without real accounts or live recordings.

## Current scope

Linux is the implemented TAP platform. Relay is a valid outcome for peers that cannot establish a direct channel. Broad interoperability, difficult NAT combinations, long-running recovery behavior, and other operating-system data planes need further work. Protocol compatibility is not a claim of a completed security audit.

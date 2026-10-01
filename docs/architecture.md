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
| `tap` / `platform` | Linux TAP descriptor lifecycle and Windows TAP-Windows6 overlapped I/O, IP configuration and cleanup |
| `runtime` | Long-lived engine shared by the desktop and CLI service, with live snapshots |
| `wake` | Private, coalesced queue/cancellation notifications for socket and TAP waits |
| `client` | Bounded diagnostic sessions retained in the library |
| `output` | Private JSON reports and explicit identity persistence |
| `daemon` | Shared per-user service, profile/identity/preferences, Unix-socket/Windows named-pipe commands, startup locks, and reconnection supervisor |
| `releases` / `platform/release_http` | Optional bounded HTTPS release checks and semantic version comparison |
| `platform/resource_limits` | Unprivileged Unix descriptor-budget setup inherited by the service |

## Peer transport lifecycle

The authenticated service connection supplies membership, peer identities, connection credentials, and direct candidates. Candidates are validated and correlated with the intended peer/connection before use. Incoming offers are restricted to eligible joined-network members, with pending-offer and worker limits.

Direct TCP and UDP candidates start as they arrive, within time budgets. UDP endpoint discovery runs independently, queries two service-provided hosts concurrently, and requires consistent mapped addresses before advertising the mapping. There is no arbitrary host scanning or predicted-port search. A service-issued relay ticket starts a competing attempt after a short direct-path head start. The first path to complete peer authentication and the Ethernet service handshake wins; losing workers are cancelled and joined. See [performance](performance.md) for limits and timing.

Both incoming and outgoing channels perform mutual authentication and the tunnel service handshake. A socket that merely connects does not become an authenticated peer channel. Transport reports separate path, authentication, service-handshake completion, and transferred traffic. Simultaneous incoming/outgoing attempts are resolved deterministically while retaining a working channel.

A path reported as Direct TCP or Direct UDP identifies the selected peer socket. Measuring useful direct connectivity also requires observing traffic on that channel; a login indicator or empty socket is insufficient. Local tests cannot predict direct success rates across live NATs and firewalls.

## Traffic processing and local notifications

The engine validates each outbound Ethernet frame once. A virtual-IP index selects unicast and directed ARP recipients; group traffic visits eligible members in RID order. Workers still check membership, traffic policy, authentication, and the destination MAC before queueing. Immutable frames are shared across bounded peer queues. Each peer reuses its own envelope/encryption buffer, and received encrypted records are decrypted in their owned transport buffer. Incoming Ethernet envelopes are all validated before any frame is delivered. Queued frames retain that buffer instead of copying each payload; multiple frames share the same immutable allocation. Traffic counters are published once per send batch, including successful sends before an error.

Reliable UDP caches the oldest unacknowledged data sequence and next retry deadline. A due retry pass captures the clock once. In-order payloads bypass the reorder map, while fragmented messages use one bounded payload allocation and fixed-size receipt tracking. Sequence wraparound, acknowledgement admission, fragment coverage checks, command ordering, and the existing retransmission limits remain enforced.

On Linux, established workers wait on their socket and a coalesced `eventfd` notification. Queued frames and cancellation wake the same wait; reliable UDP retransmission and keepalive deadlines bound it. The engine similarly waits for TAP readiness and peer/control events. Windows uses manual-reset events for local notifications and TAP overlapped-read completion, with `WaitForMultipleObjects` in the engine; established socket workers retain bounded portable polling. External command and cancellation checks remain bounded by 50 ms outside interface setup. Other control-only platforms use a condition-variable notification fallback. These notifications are local and add no network bytes.

Desktop peer lists borrow the current snapshot. The daemon shares immutable snapshots and serializes status after releasing the state lock; the desktop moves received JSON subtrees into their parsers. Consecutive queued state snapshots are coalesced, preserving phase transitions and operation results as ordered barriers. Peer presentation data and list ordering are cached, and measured offscreen rows reserve their layout space without rebuilding their controls. See [performance](performance.md) for isolated measurements and validation scope.

## Privileges and persistence

The CLI and desktop use the same `NetworkOperation` state machine for creation, joining, leaving, deleting, kicking, and changing admin roles. The CLI service tags each operation so its result reaches the requesting command. Private joins verify the network's SH server proof before accepting membership. Administration acknowledgements must match the request, action, context, network, and member. Role and removal notifications update both clients' snapshots and forwarding membership. A kick only removes the affected network relationship; peers sharing another approved network remain eligible. Pending applicants are not eligible for traffic through that network.

Network passwords use masked desktop fields or a CLI password file, are redacted from command debugging, and are not stored in settings or operational reports. A timeout leaves a mutation's outcome unknown; the desktop disconnects and reloads service state before accepting further operations.

On Linux the application runs unprivileged. The Linux adapter invokes a short-lived helper through `sudo` to create a nonpersistent TAP interface, configure it, and pass its file descriptor back over a Unix socket. Closing the last descriptor removes the interface and associated routes. Existing interfaces are not replaced.

Windows requires an elevated application and an installed, dedicated TAP-Windows6 adapter named `OpenRad`. SetupAPI device software keys verify the `tap0901` component and obtain its GUID without scanning protected unrelated registry children; an exclusive overlapped handle opens the documented `.tap` device. The backend reads the driver version/MAC/MTU, uses its media-status IOCTL, and retains Layer 2 mode. Ethernet/IPv4 ARP header MAC translation adapts the installed adapter MAC to the protocol's deterministic VPN MAC. Outbound translation runs directly in the persistent overlapped-write buffer before submission. IP Helper APIs add the session's IPv4 address and on-link routes. Cleanup cancels and completes pending I/O, removes only addresses/routes created by this instance, restores adjusted interface/group-route metrics, and disconnects media. The installed adapter remains. Forced termination can leave ActiveStore configuration and temporary metrics; see [Windows recovery and validation](windows.md).

The offline Windows setup embeds application files and the signed TAP package with its corresponding source and licenses. A short-lived native worker stages the INF through SetupAPI and installs one dedicated root-enumerated device. Adapter selection, payload hashes and ownership records make repeated setup reuse or repair the installation. The NSIS launcher checks registration, opens/focuses the desktop by default and accepts `--no-launch` for CLI use. Uninstall removes only the recorded adapter created by setup. GUI startup requests UAC automatically; this build has no persistent privileged networking service.

The desktop stores identities in the platform credential store (Windows Credential Manager or Linux Secret Service) and settings in its profile directory. Provisioning and reset logic are isolated from the GUI; tests use an in-memory vault. The CLI saves a provisioned or imported identity under its private data directory. A per-user Unix socket or Windows named pipe accepts bounded local commands; a lock prevents a second service for the same profile. Windows profile ACLs and pipe ACLs grant access to the current user and SYSTEM, pipe clients use identification-level SQOS, and the pipe rejects remote clients. Windows control messages are length-prefixed and acknowledge replies; reads/writes have bounded deadlines. The library owns the service, runtime, profile resolution, and local command protocol. The desktop is a client of this same service: it polls snapshots and issues commands off the UI thread. Startup locks serialize simultaneous launches, and the service lock permits only one engine per profile. Closing a frontend keeps peer channels and TAP alive; stopping the service disconnects both frontends. Saved names and transport preferences are applied by the service and retained across restarts. Existing identities keep their original credential-store or private-file storage.

RTT probes reuse authenticated tunnel keepalives with distinct sequence numbers, match only the intended reply, and expire after 3000 ms. They do not change packet forwarding or require ICMP sockets. Relay-only sessions suppress outgoing direct candidate requests, local UDP discovery, and direct attempts; incoming setup uses `Policy::Relay`. Changing this preference cancels and joins existing workers before starting another session. Release checks are separate from the VPN runtime and use bounded HTTPS requests and semantic version comparison.

Operational reporting does not log session keys, authentication passwords, handshake secrets, or Ethernet payloads. Synthetic fixtures exercise protocol and cryptographic behavior without real accounts or live recordings.

## Current scope

Linux TAP is runtime-tested. Windows TAP-Windows6 is experimental and cross-built on Linux. The tester reports successful operation on Windows 10; other Windows versions and broader networking configurations still need validation. Windows dual-stack TCP/UDP listeners explicitly permit IPv4-mapped traffic, and interface inventory uses `GetAdaptersAddresses`. Relay is a valid outcome for peers that cannot establish a direct channel. Difficult NAT combinations, long-running recovery behavior, Windows deployment, and other operating-system data planes remain areas for further testing/work. Protocol compatibility is not a claim of a completed security audit.

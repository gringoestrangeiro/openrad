# OpenRad Plugin 0.2.4-dev (alpha) — engineering handoff

## Summary

Java 21 / Paper 1.21.x plugin, interoperating with the legacy OpenRad/Radmin virtual network protocols. No OS TAP/TUN or extra VPN client is installed by the Paper plugin. MIT license; see LICENSE and third-party acknowledgements.

## Corrected in this build

- **VPN availability:** An automatic Minecraft-public-network join that does not respond is recorded as a warning and skipped without destroying the authenticated attachment or already joined networks. Auto-join deadline is **8 seconds** per network; an explicit configured network retains the 25-second timeout and aborts the session on failure. Regression uses local synthetic RSA/SRP service.
- **Performance:** Peer/ACL roster scanning and candidate scheduling limited to approximately **5 scans/s** while control messages and heartbeat remain responsive (~25ms readiness wait); public status materialization limited to ~2 snapshots/s. No real-world TPS benchmark has been run.
- **Security:** Offline-mode + automatic public-network selection is refused by default. Enabling it requires explicit acknowledgement through `security.allow-insecure-offline-public-networks: true` (not a substitute for Mojang authentication). A warning is emitted when Paper listens on all interfaces; configure firewall/loopback binding manually.
- **SSRF surface:** Reject non-public coordinator addresses in production and remote peer endpoints that are non-global, except same-LAN /24 direct candidates and explicit synthetic-test loopback routes. Private relay fallback is no longer permitted when an interface has disappeared.
- **Peer coordinator:** A 64-record **per-second** budget replaces the old lifetime limit of 64 records.
- **Memory:** Incoming offers limited to 64 pending CIDs with four queued followups per CID, and a maximum of 32 active setup routes. These limits can cause legitimate offers to be retried under extreme load but prevent large unbounded buffering.
- **Lifecycle:** `onDisable()` synchronizes runtime detachment against `apply()`; refuses starting OpenRadPlugin while legacy OpenRadBridge is installed. Existing profile migration retained.
- **Catalogue:** Public Minecraft network selector/maximum bound raised from 512 to 1024 (the authenticated catalogue maximum).

## Important behavior change

This build will **not** start VPN participation on a server with `online-mode=false` AND selected public Minecraft VPN networks unless the administrator explicitly sets the opt-in flag. This is deliberate: unauthenticated Minecraft names can impersonate OPs. Existing offline public-network server setups must be secured before deploying.

Never run the legacy `OpenRadBridge.jar` beside `OpenRadPlugin.jar`. Back up `plugins/OpenRadPlugin` and `plugins/OpenRadBridge` before switching versions.

## Outstanding risks and required future validation

- Official Radmin VPN client + authenticated Minecraft LAN list visual test **not yet completed**. Historical logins from two unknown names are not cryptographic origin proof.
- AES-CBC/legacy integrity construction matches OpenRad's existing protocol and is **not AEAD**; changing it independently in this plugin would break interoperability. Protocol-level coordination required.
- Paper's `server-ip=` empty binds network interfaces independently of this plugin. The plugin warns; host/network firewall remains the operator's responsibility.
- End-to-end WAN regression across all supported clients, high-load 3,000-peer CPU/TPS benchmarks, and full configuration migration failure-injection are still pending.
- A compatible remote public server may reject stricter coordinator/peer endpoint validation in some unusual network environments; validate IPv6 and LAN under authorized local tests.

## Build and verify

On Windows with Java 21 and Gradle wrapper:

`gradlew.bat --offline --no-daemon clean check`

The `verifyJar` task validates metadata, Java 21 bytecode, runtime dependencies and license; JUnit covers deterministic networking/cryptographic fixtures. The additional test `VpnRuntimeTest.stalledAutomaticPublicJoinDoesNotRestartExistingAttachment` proves no reconnect after an automatic public join timeout.

Please report bugs with sanitized logs only; do not share identity.json, secrets.properties or machine-local private profiles.

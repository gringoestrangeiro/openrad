# Implementation and acceptance ledger

Baseline: 2026-10-20, preexisting project had no Git directory. Commit f637d80 preserves its 28 source/build/document files. Initial clean test completed: 9 tests, 0 failures. Reference OpenRad local Cargo version 2.0.2; upstream reference https://github.com/gringoestrangeiro/openrad. Rust sources remain untouched.

Module contracts and exclusive ownership:
- Control integration: protocol, session, network, runtime, configuration and Paper lifecycle (main agent).
- Crypto/identity: crypto package (crypto agent).
- Peer transport: peer, transport and tunnel packages (transport agent).
- In-memory network/loopback backend: link, security, virtual and bridge packages (TCP agent).

Dependency order: wire codecs and crypto -> authenticated control/registration -> memberships and private joins -> peer transport and service handshake -> authenticated Ethernet callback -> virtual TCP -> fixed loopback SocketChannel -> Paper.

Verification gates:
1. Preserve baseline; inspect canonical Rust framing, identity, SRP and memberships.
2. Byte fixtures for crypto, codecs and service envelopes; invalid input rejection.
3. Synthetic local control server; registration, attachment, private/public joins and revocation.
4. Synthetic mutually authenticated peer connections over available paths; bounded cancellation.
5. Real loopback backend through in-memory TCP; loss/reordering/window/close/large binary transfer.
6. Clean build/tests/lint, JAR inspection, Java 21 bytecode, dependency/license inventory.
7. Paper boot/load and real official-client Minecraft E2E only if available. Never substitute synthetic evidence.

The release remains development status until the user's final acceptance list is actually demonstrated. Live credentials and unrelated installed VPN profiles will not be imported or used.

## Integration ledger — final update 2026-10-21

Protocol/crypto, persistent registration, control attachment, memberships/private joins, peer TCP/UDP/relay and UES, in-memory TCP/backend and Paper lifecycle are implemented and integrated. Java 21 and 25 clean checks each passed 101 tests without failures or skips. The JAR inspector verified 108 Java 21 classes, metadata/license and no native or runtime dependency payload.

Canonical Rust transport interop passed four role/path combinations. Real-service registration/private-network control and Java peer traffic passed. The final JAR passed actual Paper startup/commands/reload/shutdown on both JVMs and real-service → runtime → Paper protocol checks. A separate final check ran the runtime inside the Paper-loaded plugin itself.

Manual official-client gameplay acceptance remains outstanding; a friend is currently unavailable for the official Radmin and authenticated Minecraft test. A private acceptance network and stopped local Paper test environment are prepared, with credentials kept outside source/delivery archives. This work is not marked complete/stable/beta.

LAN discovery (0.2.1-dev) captures local Minecraft multicast, rewrites the virtual endpoint, and sends only over current authenticated/authorized peer channels. Real Paper + LANBroadcaster 2.4.0 delivery passed on Java 21; official-client LAN-list/gameplay remains outstanding. See `lan-discovery.md` and the current live report.

0.2.2-dev adds authenticated public Minecraft discovery, language/number/all selectors, paginated admin listing and a UTF-8 catalog. Synthetic tests cover correlation, pagination loops, selector limits and refused public joins while explicit networks remain active. The prepared server uses offline mode at the user request; direct LAN binding was corrected for the address shown in their screenshot.

The real Paper run selected and joined all 114 returned Minecraft networks, reached catalog READY without warnings, and executed numbered/language/paginated listings. TCP status succeeded on loopback, the screenshot Tailscale address and Wi-Fi. The server was then reopened in a visible console for the user.

0.2.3-dev removes the 64-channel cutoff through bounded automatic capacity, fixes discovery starvation with fair retries/incoming priority, aggregates failure diagnostics and exports all configuration selectors to YAML. Synthetic reachability tests do not establish simultaneous real connectivity to every member.

The real server subsequently reported 123 simultaneous authenticated channels and 6124 LAN advertisements sent, demonstrating operation beyond the old 64 cutoff. Only 205 of 2766 eligible members had been attempted at that snapshot; this is progressive discovery, not proof of universal reach or an official-client LAN-list screenshot.

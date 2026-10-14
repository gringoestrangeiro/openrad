# OpenRad Mobile (Android)

Android port of [OpenRad](https://github.com/gringoestrangeiro/openrad) — the open reimplementation of Radmin VPN — in **Kotlin + Jetpack Compose**.

> Current status: **real engine in pure Kotlin** (control session, identity provisioning, public catalog, public and private networks, peer channels over direct TCP or relay, RTT) and an **L3 VpnService** with in-software IP ↔ Ethernet/MAC/RID translation. `MockRepository` is now only used for tests/previews; the app uses `EngineRepository`. Details and limitations in [`docs/android-port.md`](docs/android-port.md).

## Screens (parity with the `eframe` desktop client)

| Destination | Rust origin | Contents |
|---|---|---|
| **My networks** | `../../desktop/src/app.rs` (Page::Networks) + `network_ui.rs` | Connection status, VPN address, device name, traffic, VPN interface state (with "Retry interface"), networks with role (owner/member/awaiting approval), favorites ☆/★, leave/delete network, peer filter and sorting, transport (direct TCP / relay), peer IP, Test RTT (3000 ms), retry counters, diagnostics, recent activity |
| **Discover** | `Page::Discover`, public search with 300 ms debounce | Public catalog search (stale responses discarded), cursor pagination, direct join, favorites |
| **Auto join** | `Page::AutoJoin`, `storage.rs::NetworkPreferences` | Add network by exact name (public/private), select previously accessed networks, batch join (≥ 50 ms between requests, already-member = skip, one refusal does not cancel the rest), private passwords asked once per batch and zeroed, up to 32 configs × 128 networks |
| **Settings** | `storage.rs::Settings` + daemon | Language (system/en/pt/ru/vi), auto-connect, reconnection (1–10 attempts, 1–30 s), force-relay, outbound broadcasts, device name (reconnects with the same identity), home page, sorting, units, recent activity (5–40), scale (0.8–1.5), developer view, identity (RID/VIP, reset) |

Dark theme faithful to the desktop client: background `#0D1219`, surface `#141B25`, mint `#62E2B5`, blue `#84A4FF`, amber `#F4BE64`, red `#F8828F`.

## Simple build (3 commands)

Prerequisites: **JDK 17** and internet access (the script downloads the SDK; Gradle downloads dependencies on the first run). All commands run from this directory (`clients/android/`).

```sh
# 1. Install the Android SDK (first time only, ~1 GB in ~/Android/Sdk)
./scripts/setup-android-sdk.sh

# 2. Build the debug APK
./scripts/build.sh

# APK produced at:
#   app/build/outputs/apk/debug/app-debug.apk
```

Install on a device/emulator:

```sh
./scripts/install-debug.sh
# or manually:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release build (signed only when you supply a key kept outside the repo):

```sh
./gradlew :app:assembleRelease -Popenrad.signingProperties=/path/to/signing.properties
# app/build/outputs/apk/release/app-release.apk (package com.openrad.mobile)
```

The properties file holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. Without it the release APK is unsigned.

## Manual test (real device)

1. Open OpenRad and tap **Connect**. Android asks for VPN consent (once). On the first connection the app **registers this device** with the service and stores the identity encrypted (Keystore); later connections reuse the same identity.
2. When "You are connected" appears with a `26.x.y.z` address, the VPN interface (26.0.0.0/8, MTU 1500) is up. The persistent notification shows the VIP and the peer count; its **Disconnect** button (or the one in the app) ends the session. Closing the app does **not** disconnect.
3. **Discover**: type part of a public network name; tap **Join network**.
4. **My networks → Join private network**: exact name + password (the password is not saved).
5. Online peers on the same network show up with their state; connected ones show "Direct TCP" or "Relay". Use **Test RTT** and, from another device on the network, `ping 26.x.y.z` using the phone's IP (the Android kernel answers ICMP). Apps on the phone (games, `ping` via Termux) can reach the peers' `26.x.y.z` IPs.
6. If VPN consent is denied, the session continues (catalog, joins, peers) but without IP traffic; "Retry interface" asks again after consent is granted.

LAN discovery (e.g. Minecraft's "Open to LAN"): with **Send outbound broadcasts** enabled, broadcast/multicast passes through the VPN in both directions (details and side effects on the local Wi-Fi in `docs/android-port.md`).

Settings → *Developer view* shows peer IPs/endpoints, internal IDs, and counters (frames, drops, latency).

## Layout

```
clients/android/
├── app/src/main/java/com/openrad/mobile/
│   ├── engine/                    # pure-Kotlin engine (no Android): mirrors ../../src/*.rs
│   │   ├── Wire.kt                # TLV, bytes, IPv4
│   │   ├── Crypto.kt              # CChannel (AES-CBC + MD4 trailer), RSession, SH client/server
│   │   ├── Protocol.kt            # login, registration, catalog, Membership, NetworkOperation (private networks)
│   │   ├── Tunnel.kt              # SYN/ACK, Ethernet envelopes, keepalive, ARP, L3Bridge (IP ↔ MAC/RID)
│   │   ├── Transport.kt           # Framed TCP, authentication, provisioning, PeerChannel (direct/relay)
│   │   ├── Engine.kt              # control session, peer workers, inbound offers, commands, RTT
│   │   └── EngineState.kt         # immutable snapshot, config, IdentityStore
│   ├── vpn/OpenRadVpnService.kt   # foreground VpnService: L3 TUN, protect(), engine lifecycle
│   ├── vpn/EngineHost.kt          # single owner of the engine in the process (daemon equivalent)
│   ├── data/EngineRepository.kt   # real VpnRepository + SnapshotMapper
│   ├── data/AndroidStores.kt      # identity in EncryptedSharedPreferences; secret-free preferences
│   ├── data/Codecs.kt             # JSON for identity/settings/favorites/configs
│   ├── data/MockRepository.kt     # synthetic data (tests/previews)
│   ├── model/Models.kt            # Snapshot, Peer, NetworkInfo, AppSettings (mirrors runtime.rs/storage.rs)
│   ├── ui/                        # Compose screens, components, theme
│   └── util/Format.kt             # traffic formatting, peer filter/sort, validations
├── app/src/main/res/values*/      # strings en + pt + ru + vi (mirrors ../../locales/messages.json)
├── app/src/test/                  # headless tests (JUnit) incl. vectors from ../../tests/fixtures (vendored under app/src/test/resources/fixtures)
├── scripts/                       # setup-android-sdk.sh, build.sh, install-debug.sh
├── docs/android-port.md           # Rust→Kotlin map, L3 bridge, limitations, monorepo path table
└── AGENTS.md                      # repo rules
```

Rust sources live at the monorepo root: engine in `../../src`, desktop client in `../../desktop/src`, shared fixtures in `../../tests/fixtures`, i18n source in `../../locales/messages.json`. See the path table in `docs/android-port.md`.

## Tests

```sh
./gradlew :app:testDebugUnitTest   # headless, no emulator, never touches the real service
```

They cover: synthetic Rust vectors (`../../tests/fixtures/*.json` → CChannel, SH client/server, login/catalog/join/SYN builders, private-network verifier), Membership/roles, private-network operations against a synthetic SH server, the L3 bridge, a loopback peer channel, a **full engine against a synthetic local coordinator** (provisioning → login → VIP → search → public join → private join) and **two real engines connected** over direct TCP and over relay exchanging IPv4 both ways with correlated RTT; plus codecs, snapshot mapping, i18n (4 languages, same placeholders), and the 300 ms debounce. Beyond automated tests: 2 user-reported real-device checks — (1) a Minecraft playtest over TCP-only relay, no direct UDP, with good ping; (2) the final optimized pre-2.0 debug build opened on-device with a VPN connection established; systematic validation (multiple devices, Windows/Radmin peers, metrics) is still pending.

## License

MIT — same as OpenRad. See `LICENSE`.

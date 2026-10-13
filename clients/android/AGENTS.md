# AGENTS.md — clients/android (Android / Kotlin)

## Stack

Kotlin + Jetpack Compose (Material3) + Navigation Compose. Min SDK 26, target/compile 34, Java 17 toolchain. One `:app` module; the VPN engine is pure Kotlin in `engine/` (no JNI).

> Source of truth: the monorepo root (`../..`, also at `https://github.com/gringoestrangeiro/openrad`). The desktop client is `eframe/egui` in `../../desktop/src/app.rs` (~6k lines); the shared engine is `../../src/daemon.rs` + `../../src/runtime.rs`. Preserve the contracts below when porting.

All Rust paths in this file are relative to `clients/android/` (i.e. `../../src` = monorepo-root `src`). See the path table in `docs/android-port.md`.

## Structure and where to change things

- `model/Models.kt` — mirrors `runtime.rs::Snapshot/PeerView/PeerState/Traffic` and `protocol.rs::Network/Peer/PublicNetwork`. No Android logic here.
- `engine/` — pure-Kotlin engine, **no Android imports** (JVM-testable): `Wire` (TLV), `Crypto` (CChannel/RSession/SH), `Protocol` (login, Membership, `NetworkOperation`), `Tunnel` (envelopes, ARP, `L3Bridge` IP↔MAC/RID), `Transport` (Framed, `PeerChannel`), `Engine` (control, peers, commands, RTT). Mirrors `../../src/*.rs`; wire-format changes require a vector/test.
- `data/` — `VpnRepository` (interface), `EngineRepository` (real, via `vpn/EngineHost`), `MockRepository` (synthetic, tests/previews), `AndroidStores`/`Codecs` (persistence). UI only talks to the interface.
- `ui/screens/` — one screen per destination: `Networks`, `Discover`, `AutoJoin`, `Settings`. Reusable components in `ui/components/`.
- `ui/theme/Theme.kt` — fixed desktop colors (`BG #0D1219`, `SURFACE #141B25`, `RAISED #1A232F`, `BORDER #273341`, `TEXT #E6EDF4`, `MUTED #8D9DB0`, `MINT #62E2B5`, `BLUE #84A4FF`, `AMBER #F4BE64`, `RED #F8828F`). Do not invent a palette.
- `vpn/OpenRadVpnService.kt` — foreground VpnService (`specialUse`): L3 TUN 26.0.0.0/8 MTU 1500 via `VpnService.Builder` + `ParcelFileDescriptor` read with `poll(2)`; engine sockets go through `protect()`. `vpn/EngineHost.kt` is the single owner of the engine in the process (daemon role). Never open `/dev/net/tun` directly.
- `res/values*/strings.xml` — mirrors `../../locales/messages.json` (en/pt/ru/vi). Every new string goes into all 4 languages.

## Build / Test / Run

Run from `clients/android/`:

```sh
./scripts/setup-android-sdk.sh     # once; installs cmdline-tools + platform-34 + build-tools
./scripts/build.sh                 # ./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # headless unit tests (required before PR)
./scripts/install-debug.sh         # adb install -r app-debug.apk
./gradlew :app:lintDebug           # lint (when available in CI)
```

- Do not commit `local.properties`, `*.jks`, `build/`, `.gradle/`, `.idea/` (see `.gitignore`).
- JDK 17 is required (`org.gradle.java.home` or `JAVA_HOME`). Do not use Java 21 for AGP 8.5.
- Debug APK goes to `app/build/outputs/apk/debug/app-debug.apk`.

## Kotlin/Compose style

- Official Kotlin: 4 spaces, `camelCase` names (fun/val), `PascalCase` (classes/composables), no `!!` without justification; prefer `Result`/`StateFlow` over exceptions for network errors.
- Small, stateless-when-possible composables; state via `ViewModel` + `collectAsStateWithLifecycle()`. No `GlobalScope`.
- Strings **always** from `stringResource()`, never hardcoded in PT/EN. `"%1$s"`-style placeholders with context comments.
- Typed navigation in `navigation/`: route = sealed object, 4 fixed destinations. Do not add a nested NavGraph without reason.
- Performance: lists with `LazyColumn` + `key`, avoid recomposition with `remember`/`derivedStateOf` for peer filtering/sorting (mirrors the Rust `PeerListCache`).

## Behavioral contracts (from Rust, do NOT relax)

- Public search with **300 ms** debounce; discard responses from stale queries.
- Batch join: stagger joins **≥ 50 ms** apart; already-member = skip; one refusal does not cancel the rest; private passwords in zeroed memory, **never persisted**.
- RTT: correlated authenticated keepalive, **3000 ms** timeout; show "no reply — strict firewall" after timeout.
- Adaptive retry and counters (failed/refused, offline, retrying, queued) preserved in the UI even with mocks.
- Favorites and join-configs (max 32 configs × 128 networks) kept separate from credentials; name validation 1–255 chars, password 6–256, no control chars (see `network::validate_name` in `../../src/network.rs`).
- Settings ranges: retry attempts 1–10, base delay 1–30 s, recent count 5–40, scale 0.8–1.5.

## Tests

- Unit tests in `app/src/test/` (JUnit4, real org.json, coroutines-test): vectors from `../../tests/fixtures` (vendored under `app/src/test/resources/fixtures`), network operations with a synthetic SH server, `L3Bridge`, loopback peer channel, engine against a synthetic local coordinator and two engines over direct TCP/relay, codecs, i18n, and debounce.
- Integration tests only use `127.0.0.1` and a test RSA key (`EngineConfig.modulus`); never contact the real service nor register identities in tests.
- No mandatory instrumented tests. Emulator testing only for visual changes (manual screenshot).
- Never touch the user's real VPN in tests; mocks always.

## Security

- Identity/credentials: only in `EncryptedSharedPreferences` (Keystore, `data/AndroidStores.kt`); never in plain preferences or logs; `Identity.toString()` must not leak the credential. Keystore failure fails closed (never writes plaintext). `allowBackup=false`.
- Private-network passwords: zeroed `CharArray` after use, never serialized (configs only store name + private flag).
- Logs without passwords, session keys, or payloads. Review `Log.d` before PR.

## Commits

Prefixes `feat:`, `fix:`, `docs:`, `chore:`. Describe observable behavior + validation (`:app:testDebugUnitTest`, `assembleDebug`). Screenshot for UI changes.

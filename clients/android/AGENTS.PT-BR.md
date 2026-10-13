# AGENTS.md — openrad-mobile (Android / Kotlin)

## Stack

Kotlin + Jetpack Compose (Material3) + Navigation Compose. Min SDK 26, target/compile 34, toolchain Java 17. Um módulo `:app`; o motor VPN é Kotlin puro em `engine/` (sem JNI).

> Origem da verdade: a raiz do monorepo (`../..`, também em `https://github.com/gringoestrangeiro/openrad`). O desktop é `eframe/egui` em `../../desktop/src/app.rs` (~6k linhas); o motor compartilhado é `src/daemon.rs` + `src/runtime.rs`. Preservar os contratos abaixo ao portar.

## Estrutura e onde mexer

- `model/Models.kt` — espelha `runtime.rs::Snapshot/PeerView/PeerState/Traffic` e `protocol.rs::Network/Peer/PublicNetwork`. Não colocar lógica Android aqui.
- `engine/` — motor Kotlin puro, **sem imports Android** (testável na JVM): `Wire` (TLV), `Crypto` (CChannel/RSession/SH), `Protocol` (login, Membership, `NetworkOperation`), `Tunnel` (envelopes, ARP, `L3Bridge` IP↔MAC/RID), `Transport` (Framed, `PeerChannel`), `Engine` (controle, peers, comandos, RTT). Espelha `src/*.rs`; mudanças de wire format exigem vetor/teste.
- `data/` — `VpnRepository` (interface), `EngineRepository` (real, via `vpn/EngineHost`), `MockRepository` (sintético, testes/previews), `AndroidStores`/`Codecs` (persistência). UI só fala com a interface.
- `ui/screens/` — uma tela por destino: `Networks`, `Discover`, `AutoJoin`, `Settings`. Componentes reutilizáveis em `ui/components/`.
- `ui/theme/Theme.kt` — cores fixas do desktop (`BG #0D1219`, `SURFACE #141B25`, `RAISED #1A232F`, `BORDER #273341`, `TEXT #E6EDF4`, `MUTED #8D9DB0`, `MINT #62E2B5`, `BLUE #84A4FF`, `AMBER #F4BE64`, `RED #F8828F`). Não inventar paleta.
- `vpn/OpenRadVpnService.kt` — VpnService foreground (`specialUse`): TUN L3 26.0.0.0/8 MTU 1500 via `VpnService.Builder` + `ParcelFileDescriptor` lido com `poll(2)`; sockets do motor passam por `protect()`. `vpn/EngineHost.kt` é o dono único do motor no processo (papel do daemon). Nunca abrir `/dev/net/tun` direto.
- `res/values*/strings.xml` — espelha `locales/messages.json` (en/pt/ru/vi). Toda string nova entra nos 4 idiomas.

## Build / Test / Run

```sh
./scripts/setup-android-sdk.sh     # uma vez; instala cmdline-tools + platform-34 + build-tools
./scripts/build.sh                 # ./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # testes unitários headless (obrigatório antes de PR)
./scripts/install-debug.sh         # adb install -r app-debug.apk
./gradlew :app:lintDebug           # lint (quando disponível no CI)
```

- Não commitar `local.properties`, `*.jks`, `build/`, `.gradle/`, `.idea/` (ver `.gitignore`).
- JDK 17 obrigatório (`org.gradle.java.home` ou `JAVA_HOME`). Não usar Java 21 para AGP 8.5.
- APK debug vai para `app/build/outputs/apk/debug/app-debug.apk`.

## Estilo Kotlin/Compose

- Kotlin oficial: 4 espaços, nomes `camelCase` (fun/val), `PascalCase` (classes/composables), sem `!!` sem justificativa; preferir `Result`/`StateFlow` a exceções para erros de rede.
- Composables pequenos, stateless quando possível; estado via `ViewModel` + `collectAsStateWithLifecycle()`. Sem `GlobalScope`.
- Strings **sempre** de `stringResource()`, nunca hardcoded em PT/EN. Placeholders no estilo `"%1$s"` com comentários de contexto.
- Navegação tipada em `navigation/`: rota = objeto selado, 4 destinos fixos. Não adicionar NavGraph aninhado sem motivo.
- Performance: listas com `LazyColumn` + `key`, evitar recomposição com `remember`/`derivedStateOf` para filtro/ordenação de peers (espelha `PeerListCache` do Rust).

## Contratos comportamentais (vindos do Rust, NÃO relaxar)

- Busca pública com debounce **300 ms**; descartar respostas de queries antigas.
- Batch join: sobrepor joins com intervalo **≥ 50 ms**; já-membro = skip; um refusal não cancela os demais; senhas privadas em memória zerada, **nunca persistir**.
- RTT: keepalive autenticado correlacionado, timeout **3000 ms**; exibir "sem resposta — firewall estrito" após timeout.
- Retry adaptativo e contadores (failed/refused, offline, retrying, queued) preservados na UI mesmo com mock.
- Favoritos e join-configs (máx 32 configs × 128 redes) separados de credenciais; validação de nomes 1–255 chars, senha 6–256, sem control chars (ver `network::validate_name`).
- Settings range: retry attempts 1–10, base delay 1–30 s, recent count 5–40, scale 0.8–1.5.

## Testes

- Unitários em `app/src/test/` (JUnit4, org.json real, coroutines-test): vetores de `tests/fixtures/` do Rust (copiados em `src/test/resources/fixtures`), operações de rede com servidor SH sintético, `L3Bridge`, canal de peer em loopback, motor contra coordenador local sintético e dois motores via TCP direto/relay, codecs, i18n e debounce.
- Testes de integração usam só `127.0.0.1` e uma chave RSA de teste (`EngineConfig.modulus`); nunca contatar o serviço real nem registrar identidades em teste.
- Sem teste instrumentado obrigatório. Teste com emulador só para mudança visual (screenshot manual).
- Nunca tocar em VPN real do usuário em teste; mock sempre.

## Segurança

- Identidade/credenciais: só em `EncryptedSharedPreferences` (Keystore, `data/AndroidStores.kt`); nunca em preferências comuns nem em log; `Identity.toString()` não expõe a credencial. Falha do Keystore falha fechado (não grava em claro). `allowBackup=false`.
- Senhas de rede privada: `CharArray` zerado após uso, nunca serializado (configs só guardam nome + flag privada).
- Logs sem senha, chave de sessão ou payload. Revisar `Log.d` antes de PR.

## Commits

Prefixos `feat:`, `fix:`, `docs:`, `chore:`. Descrever comportamento observável + validação (`:app:testDebugUnitTest`, `assembleDebug`). Screenshot para mudança de UI.

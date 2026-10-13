# Port Android — Rust → Kotlin

Origem: a raiz do monorepo (`../..` a partir de `clients/android/`, fonte da verdade; também em `https://github.com/gringoestrangeiro/openrad`). Referência auxiliar: o cliente Python em `../python/` (`radproto.py`, `radvpn.py`).
Destino: `clients/android/` neste monorepo (`com.openrad.mobile`, Kotlin + Compose).

## Caminhos no monorepo

O projeto Android foi importado como subdiretório; todos os comandos Gradle/scripts rodam a partir de `clients/android/`. Referências ao Rust usam caminhos relativos à raiz do monorepo:

| Antes (repo standalone `openrad-mobile/`) | Agora (monorepo, relativo a `clients/android/`) | Alvo real |
|---|---|---|
| `src/*.rs` (Rust) | `../../src/*.rs` | motor compartilhado |
| `desktop/src/app.rs`, `network_ui.rs`, `backend.rs`, `storage.rs` | `../../desktop/src/*.rs` | cliente desktop |
| `tests/fixtures/*.json` | `../../tests/fixtures/*.json` | vetores sintéticos (copiados em `app/src/test/resources/fixtures`; diff limpo contra a raiz) |
| `locales/messages.json` | `../../locales/messages.json` | fonte do i18n (`res/values*/strings.xml`) |
| `app/...`, `scripts/...`, `docs/...`, `gradlew` | inalterados | tudo dentro de `clients/android/` |
| Caminhos absolutos da máquina local | **removidos** — nunca usar paths absolutos de máquina local | — |

## Decisão: motor em Kotlin puro (não JNI)

O motor Rust depende de TAP, sockets Unix/daemon, `flock` e de um pipeline UDP grande; expô-lo por JNI exigiria NDK + `cargo-ndk` + um adaptador de plataforma novo e tornaria o build simples (`./scripts/build.sh`) dependente de Rust. A reimplementação em Kotlin (pacote `engine/`, sem dependências Android) segue o protocolo do Rust e é verificada contra os **mesmos vetores sintéticos** de `../../tests/fixtures/` e por testes de integração locais. A interface `VpnRepository` continua isolando a UI caso um backend JNI seja adicionado depois.

## Mapa de arquivos

| Rust | Kotlin | Notas |
|---|---|---|
| `src/protocol.rs` (TLV, login, catálogo, Membership, Identity) | `engine/Wire.kt`, `engine/Protocol.kt` | Mesmos tags, limites (16384 registros, 4 MiB) e validações; vetores `protocol.*` do `reference.json` |
| `src/crypto.rs` (Channel, rsa_session, ShClient/ShServer) | `engine/Crypto.kt` | AES-256-CBC (JCE) com IV encadeado por direção, trailer MD4 de 8 bytes, rekey preservando IVs; vetores `channel`, `rekey`, `sh`, `incoming-sh.json` |
| `src/network.rs` (NetworkOperation, verificador, CRC64) | `engine/Protocol.kt::NetworkOperation` | Join público/privado (SH sobre op 39/40), criar, sair, excluir; resposta de senha ambígua só avança se exatamente uma operação aceitar; `network-verifier.json` |
| `src/tunnel.rs` | `engine/Tunnel.kt` | SYN/SYN-ACK/ACK, envelopes com validação do registro inteiro antes de entregar quadros, keepalive 3/7 |
| `src/session.rs`, `src/peer.rs`, `src/incoming.rs` | `engine/Transport.kt`, `engine/Engine.kt` | `Framed` incremental e `PeerStream` (TCP/UDP), autenticação RSession+SH, provisionamento com redirect, rendezvous TCP (checksum), UDP direto e mapeado nos dois sentidos (op 28 → 29, op 3 → 7), relay com ticket, aceitação de entrada (listener + UDP + relay) e convergência de discagem simultânea |
| `src/udp.rs` | `engine/Udp.kt` | Punch P/A/C com nonce correlacionado e só candidatos autenticados; ENet CONNECT/VERIFY/ACK, CRC por datagrama, janela de envio 256 / recepção 512, reordenação e remontagem de fragmentos limitadas, retransmissão 400 ms (20 tentativas, 35 s de vivacidade/congestionamento); descoberta UES com dois hosts que precisam concordar; formato de fio inalterado |
| `src/runtime.rs` (Snapshot, workers, RTT, retry) | `engine/Engine.kt`, `engine/EngineState.kt` | RTT = keepalive autenticado correlacionado, timeout **3000 ms**; backoff adaptativo por peer; contadores retry ativo/enfileirado |
| `src/scheduling.rs`, `peer.rs::RelayOffer`/`RelayPreparation` | `engine/Scheduling.kt` | Setups de peer em paralelo (96 de saída / 128 no total; ofertas de entrada primeiro), retry por peer 1,5 s → 12 s com jitter e `RetryPacer`, sem desistir; TCP direto em 4 vias e UDP direto/mapeado (8 s de saída, 12 s de entrada), relay pareado assim que oferecido e autenticado após vantagem de 4 s para o direto, renovada por inícios diretos tardios (máx. 8 s após a oferta) |
| `src/daemon.rs` (serviço por usuário) | `vpn/EngineHost.kt` + `vpn/OpenRadVpnService.kt` | Um motor por processo; fechar a UI mantém a sessão; Desconectar a encerra; START_STICKY reinicia o serviço |
| `desktop/src/storage.rs` | `data/AndroidStores.kt`, `data/Codecs.kt` | Identidade em `EncryptedSharedPreferences` (Keystore); settings/favoritos/configs separados, sem senhas; valores corrompidos guardados em `*.corrupt` para reparo |
| `desktop/src/app.rs`, `network_ui.rs`, `backend.rs` | `ui/screens/*`, `data/EngineRepository.kt` | 4 telas; debounce 300 ms; batch join ≥ 50 ms |
| `../../locales/messages.json` | `res/values*/strings.xml` | en/pt/ru/vi, testado por `LocalizationTest` |

## Ponte L3 (Android não tem TAP)

O `VpnService` só entrega pacotes IP. Os peers Radmin trocam **quadros Ethernet** e esperam ARP. `engine/Tunnel.kt::L3Bridge` faz a tradução:

- **Saída (TUN → peer)**: valida o IPv4 (checksum, origem = nosso VIP, tamanho ≤ MTU 1500). Destino unicast → procura o peer conectado cujo VIP é o destino (**IP → RID**) e usa o MAC negociado no SYN/ACK daquele canal (**RID → MAC**); monta `dst MAC | 02:1a:<VIP> | 0x0800 | IP`. Destino `26.255.255.255`, `255.255.255.255` ou multicast → MAC de grupo, enviado a todos os peers conectados (desligável em "broadcasts de saída").
- **Entrada (peer → TUN)**: cada quadro passa por `validFrame` (MAC/IP de origem do canal, destino nosso ou grupo); IPv4 perde o cabeçalho Ethernet e vai para o TUN. **ARP request para o nosso VIP é respondido em software** (o kernel nunca vê Ethernet). Ao conectar, um ARP gratuito anuncia nosso MAC.
- Sem TUN (consentimento negado), o motor responde ICMP echo para o nosso VIP, como a referência Python.

Interface: `addAddress(VIP, 8)`, `addRoute(26.0.0.0, 8)`, MTU 1500, sem DNS e sem rota padrão (o resto do tráfego do aparelho não passa pela VPN). Sockets do motor usam `VpnService.protect()`.

## Limitações conhecidas

- **Somente IPv4**: IPv6 e protocolos não IP (IPX etc.) não são transportados; quadros Ethernet não IPv4/ARP recebidos são descartados.
- **Descoberta de LAN (broadcast/multicast)**: com "broadcasts de saída" ligado (padrão), a TUN recebe as rotas `224.0.0.0/4` e `255.255.255.255/32`, as mesmas do desktop (`linux.rs`, `tap_windows_contract.rs`). Assim os anúncios dos apps (Minecraft Java `224.0.2.60:4445`, Bedrock `255.255.255.255:19132`, `26.255.255.255`) vão para todos os peers conectados. Na entrada, o Linux só entrega multicast numa interface em que o app entrou no grupo, e o app normalmente entra pelo Wi-Fi, não pela `tun0`. Por isso a ponte entrega os pacotes UDP de grupo como **unicast para o nosso VIP**, recalculando os checksums IP/UDP; sockets em `0.0.0.0:<porta>` recebem normalmente. Efeito colateral: enquanto conectado, a descoberta no Wi-Fi local (Chromecast/SSDP/mDNS, jogos no mesmo Wi-Fi) não sai pelo Wi-Fi. Desligue a opção para evitar isso. Validado no emulador: entrada em 4445/19132, saída multicast e broadcast (`SO_BROADCAST`). Além disso, 1 playtest real em aparelho reportado pelo usuário (via relay TCP-only, sem UDP direto): entrou num servidor de Minecraft e jogou de verdade, com ping bom; teste sistemático com o Minecraft (múltiplos aparelhos, métricas) continua pendente.
- **UDP direto** (`src/udp.rs`, ENet/UES) implementado em `engine/Udp.kt`, mas **caminhos diretos (UDP e TCP) só no Wi-Fi**: em dados móveis (ou qualquer rede não Wi-Fi) o motor age como force-relay nos dois sentidos — não pede candidatos, não anuncia endpoints nem dispara UDP. A rede vem de `vpn/WifiNetworkPolicy.kt` (`ConnectivityManager`, redes não VPN; Wi-Fi validado = direto permitido; até a primeira classificação, só relay) injetada como `NetworkPolicy` no motor puro. Ao sair do Wi-Fi, canais diretos e setups em andamento são encerrados e os peers reconectam por relay; ao voltar, só os setups pendentes são refeitos (canais de relay ativos são mantidos).
- Limites do UDP: só candidatos IPv4 (os IPv6 são ignorados, como no TCP); o endpoint mapeado exige dois hosts UES autenticados concordando (sem predição de porta), então NAT simétrico cai no relay. Diferenças em relação ao Rust: ao fechar um canal ENet estabelecido o Android envia um DISCONNECT ENet sem confirmação (comando padrão que o Rust já trata), para o outro lado migrar na hora em vez de esperar 35 s; e loopback só é anunciado quando a rota do coordenador é loopback (coordenador local de teste).
- Validação do UDP: testes headless em loopback (porta dos testes de `udp.rs`, rendezvous, canal de peer sobre UDP, descoberta UES) e dois motores via coordenador sintético (UDP direto, dados móveis = só relay, saída do Wi-Fi → relay). Além disso, 1 sonda manual do motor Kotlin **na JVM de um PC Linux** contra o serviço real (identidade de teste nova, rede pública com 11 peers): 6 peers por UDP direto e 3 por relay, RTT por keepalive com mediana ~28 ms no UDP, eco ICMP respondido por 5/6 peers UDP e 2/3 relay, depois saiu da rede. **Não** foi validado em aparelho Android (NAT de operadora, `protect()` de socket UDP e a classificação Wi-Fi/dados ficam pendentes).
- **Gestão de membros** (remover membro, conceder/revogar admin) e mensagens de release do desktop não foram portadas; aprovação pendente é exibida.
- Troca de rede (Wi-Fi ↔ dados) derruba a sessão; o motor reconecta conforme "Reconectar após falha" / tentativas / atraso. Esgotadas as tentativas, fica em erro até tocar Conectar. A política Wi-Fi/dados acima vale também durante a reconexão.
- Validado por testes headless, integração local e **emulador Android 14 (API 34)** contra o harness sintético (ver abaixo), além de **verificações reais em aparelho reportadas pelo usuário**: (1) Minecraft jogável via relay TCP-only, sem UDP direto, com ping bom; (2) o build debug final otimizado pré-2.0 aberto no aparelho com conexão VPN estabelecida; validação sistemática — múltiplos aparelhos, peers Windows/Radmin reais, métricas — continua pendente. O teste manual está descrito no README.

## Validação no emulador (harness sintético, sem serviço real)

`EmulatorHarnessTest` (ignorado por padrão) sobe um coordenador + relay sintéticos em `127.0.0.1:47301/47302` e um segundo motor (26.0.0.200) na JVM. O app debug lê `files/debug-endpoint.json` **apenas em builds debuggable** (equivalente ao `--host` da CLI) e alcança o host por `10.0.2.2`.

```sh
OPENRAD_EMULATOR=1 ./gradlew :app:testDebugUnitTest --tests '*EmulatorHarnessTest*' --rerun -i &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.openrad.mobile.debug mkdir -p files
adb shell run-as com.openrad.mobile.debug sh -c 'cat > files/debug-endpoint.json' < app/build/emulator-endpoint.json
adb shell appops set com.openrad.mobile.debug ACTIVATE_VPN allow   # só no emulador; num aparelho, aceite o diálogo
# abra o app e toque Conectar; depois:
adb shell ping -c 4 26.0.0.200
```

Resultado observado (API 34 x86_64): provisionamento → online como `26.0.0.30`; `tun0` com `26.0.0.30/8`, MTU 1500, rota `26.0.0.0/8`; peer conectado por relay (o TCP direto falha porque o listener do emulador não é alcançável do host, e o fallback assume); `ping` do Android → peer 4/4 (~3 ms); RTT na UI 3,1 ms; ICMP do host → VIP do Android respondido pelo kernel via TUN. Remova `files/debug-endpoint.json` (ou reinstale) para voltar ao serviço padrão.

Marca: o foreground do launcher (`app/src/main/res/drawable/ic_launcher_foreground.xml`) é a referência canônica da logo, espelhada em `../../assets/logo.svg` (mesma geometria/cores).

## Fases

- [x] **Fase 1 — UI** com mock, tema, i18n, build simples.
- [x] **Fase 2 — Motor** Kotlin (`EngineRepository` substitui o mock).
- [x] **Fase 3 — Data plane**: `VpnService.Builder`, TUN não bloqueante com `poll(2)`, ponte L3, reconexão.
- [x] **Fase 4 — Paridade principal**: favoritos persistidos, 32 configs × 128 redes, force-relay, broadcasts de saída, reset de identidade, diagnóstico.
- [x] **Fase 5 — UDP direto** (ENet/UES) só no Wi-Fi; dados móveis = só relay.
- [ ] Próximos: validar UDP direto em aparelho, gestão de membros, IPv6/companion address, testes instrumentados em aparelho.

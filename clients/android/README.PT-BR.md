# OpenRad Mobile (Android)

Port Android do [OpenRad Rust](https://github.com/gringoestrangeiro/openrad) — reimplementação aberta do Radmin VPN — em **Kotlin + Jetpack Compose**.

> Estado atual: **motor real em Kotlin puro** (sessão de controle, provisionamento de identidade, catálogo público, redes públicas e privadas, canais de peer por TCP direto ou relay, RTT) e **VpnService L3** com tradução em software IP ↔ Ethernet/MAC/RID. O `MockRepository` ficou apenas para testes/previews; o app usa o `EngineRepository`. Detalhes e limitações em [`docs/android-port.md`](docs/android-port.md).

## Telas (paridade com o desktop `eframe`)

| Destino | Origem Rust | Conteúdo |
|---|---|---|
| **Minhas redes** | `desktop/src/app.rs` (Page::Networks) + `network_ui.rs` | Status da conexão, endereço VPN, nome do dispositivo, tráfego, estado da interface VPN (com "Tentar interface novamente"), redes com papel (proprietário/membro/aguardando aprovação), favoritos ☆/★, sair/excluir rede, filtro e ordenação de peers, transporte (TCP direto / Relay), IP do peer, Testar RTT (3000 ms), contadores de retry, diagnóstico, atividade recente |
| **Descobrir** | `Page::Discover`, busca pública com debounce 300 ms | Busca no catálogo público (respostas antigas descartadas), paginação por cursor, entrar direto, favoritos |
| **Auto join** | `Page::AutoJoin`, `storage.rs::NetworkPreferences` | Adicionar rede pelo nome exato (pública/privada), selecionar redes já acessadas, entrar em lote (≥ 50 ms entre requisições, já-membro = pula, uma recusa não cancela as demais), senhas privadas pedidas a cada lote e zeradas, até 32 configurações × 128 redes |
| **Configurações** | `storage.rs::Settings` + daemon | Idioma (sistema/en/pt/ru/vi), auto-conectar, reconexão (1–10 tentativas, 1–30 s), force-relay, broadcasts de saída, nome do dispositivo (reconecta com a mesma identidade), página inicial, ordenação, unidades, atividade recente (5–40), escala (0,8–1,5), visão do desenvolvedor, identidade (RID/VIP, reset) |

Tema escuro fiel ao desktop: fundo `#0D1219`, superfície `#141B25`, menta `#62E2B5`, azul `#84A4FF`, âmbar `#F4BE64`, vermelho `#F8828F`.

## Build simples (3 comandos)

Pré-requisitos: **JDK 17** e internet (o script baixa o SDK; o Gradle baixa as dependências na primeira vez).

```sh
# 1. Instala o Android SDK (só na primeira vez, ~1 GB em ~/Android/Sdk)
./scripts/setup-android-sdk.sh

# 2. Compila o APK de debug
./scripts/build.sh

# APK gerado em:
#   app/build/outputs/apk/debug/app-debug.apk
```

Instalar no aparelho/emulador:

```sh
./scripts/install-debug.sh
# ou manual:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build de release (assinado só quando você fornece uma chave mantida fora do repositório):

```sh
./gradlew :app:assembleRelease -Popenrad.signingProperties=/caminho/para/signing.properties
# app/build/outputs/apk/release/app-release.apk (pacote com.openrad.mobile)
```

O arquivo de propriedades contém `storeFile`, `storePassword`, `keyAlias` e
`keyPassword`. Sem ele, o APK de release não é assinado.

## Teste manual (aparelho real)

1. Abra o OpenRad e toque **Conectar**. O Android pede o consentimento de VPN (uma vez). Na primeira conexão o app **registra este aparelho** no serviço e guarda a identidade criptografada (Keystore); as próximas conexões reutilizam a mesma identidade.
2. Quando aparecer "Você está conectado" e o endereço `26.x.y.z`, a interface VPN (26.0.0.0/8, MTU 1500) está ativa. A notificação persistente mostra o VIP e o número de peers; o botão **Desconectar** dela (ou do app) encerra a sessão. Fechar o app **não** desconecta.
3. **Descobrir**: digite parte do nome de uma rede pública; toque **Entrar na rede**.
4. **Minhas redes → Entrar em rede privada**: nome exato + senha (a senha não é salva).
5. Peers online da mesma rede aparecem com o estado; conectados mostram "TCP direto" ou "Relay". Use **Testar RTT** e, a partir de outro dispositivo da rede, `ping 26.x.y.z` no IP do celular (o kernel do Android responde ao ICMP). Apps no celular (jogos, `ping` via Termux) alcançam os IPs `26.x.y.z` dos peers.
6. Se o consentimento de VPN for negado, a sessão continua (catálogo, joins, peers), mas sem tráfego IP; "Tentar interface novamente" pede de novo após conceder.

Descoberta de LAN (ex.: "Abrir para LAN" do Minecraft): com **Enviar broadcasts de saída** ligado, broadcast/multicast passa pela VPN nos dois sentidos (detalhes e efeito colateral no Wi-Fi local em `docs/android-port.md`).

Configurações → *Visão do desenvolvedor* mostra IP/endpoint dos peers, IDs internos e contadores (quadros, descartes, latência).

## Estrutura

```
openrad-mobile/
├── app/src/main/java/com/openrad/mobile/
│   ├── engine/                    # motor Kotlin puro (sem Android): espelha src/*.rs do Rust
│   │   ├── Wire.kt                # TLV, bytes, IPv4
│   │   ├── Crypto.kt              # CChannel (AES-CBC + MD4 trailer), RSession, SH cliente/servidor
│   │   ├── Protocol.kt            # login, registro, catálogo, Membership, NetworkOperation (redes privadas)
│   │   ├── Tunnel.kt              # SYN/ACK, envelopes Ethernet, keepalive, ARP, L3Bridge (IP ↔ MAC/RID)
│   │   ├── Transport.kt           # Framed TCP, autenticação, provisionamento, PeerChannel (direto/relay)
│   │   ├── Engine.kt              # sessão de controle, workers de peer, ofertas de entrada, comandos, RTT
│   │   └── EngineState.kt         # snapshot imutável, config, IdentityStore
│   ├── vpn/OpenRadVpnService.kt   # VpnService foreground: TUN L3, protect(), ciclo do motor
│   ├── vpn/EngineHost.kt          # dono único do motor no processo (equivalente ao daemon)
│   ├── data/EngineRepository.kt   # VpnRepository real + SnapshotMapper
│   ├── data/AndroidStores.kt      # identidade em EncryptedSharedPreferences; preferências sem segredos
│   ├── data/Codecs.kt             # JSON de identidade/settings/favoritos/configs
│   ├── data/MockRepository.kt     # dados sintéticos (testes/previews)
│   ├── model/Models.kt            # Snapshot, Peer, NetworkInfo, AppSettings (espelha runtime.rs/storage.rs)
│   ├── ui/                        # telas Compose, componentes, tema
│   └── util/Format.kt             # formatação de tráfego, filtro/ordenação de peers, validações
├── app/src/main/res/values*/      # strings en + pt + ru + vi (espelha locales/messages.json)
├── app/src/test/                  # testes headless (JUnit) incl. vetores de tests/fixtures do Rust
├── scripts/                       # setup-android-sdk.sh, build.sh, install-debug.sh
├── docs/android-port.md           # mapa Rust→Kotlin, ponte L3, limitações
└── AGENTS.md                      # regras do repo
```

## Testes

```sh
./gradlew :app:testDebugUnitTest   # headless, sem emulador e sem tocar no serviço real
```

Cobrem: vetores sintéticos do Rust (`tests/fixtures/*.json` → CChannel, SH cliente/servidor, builders de login/catálogo/join/SYN, verificador de rede privada), Membership/papéis, operações de rede privada contra um servidor SH sintético, ponte L3, canal de peer em loopback, **motor completo contra um coordenador local sintético** (provisionamento → login → VIP → busca → join público → join privado) e **dois motores reais conectados** por TCP direto e por relay trocando IPv4 nos dois sentidos com RTT correlacionado; além de codecs, mapeamento de snapshot, i18n (4 idiomas, mesmos placeholders) e debounce de 300 ms. Além dos testes automatizados: 2 verificações reais em aparelho reportadas pelo usuário — (1) um playtest de Minecraft via relay TCP-only, sem UDP direto, com ping bom; (2) o build debug final otimizado pré-2.0 aberto no aparelho com conexão VPN estabelecida; validação sistemática (múltiplos aparelhos, peers Windows/Radmin, métricas) continua pendente.

## Licença

MIT — mesmo do OpenRad. Veja `LICENSE`.

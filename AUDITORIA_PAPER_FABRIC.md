# Auditoria e correção — OpenRad Paper (plugin) e Fabric (mod)

Data: 2026-11-14/15 · Base: `main` em `94833fd` (release 2.3.2) · Estado: **alterações não commitadas** no working tree (nada foi commitado, enviado ou publicado).

Escopo: `clients/plugin/` e `clients/mod/` (`core/`, `fabric/`, `modern/`). Outros componentes (Rust, Go server, Python, Android, CI) foram lidos apenas como referência de protocolo e não foram alterados.

Execução: o coordenador (Fable/Opus) mapeou o repositório, distribuiu seis auditorias somente leitura a subagentes Sonnet (raciocínio High) — Paper, Fabric, networking, segurança, performance, compatibilidade/testes —, verificou os achados no código, distribuiu as correções em grupos de arquivos disjuntos (um deles num worktree separado para não haver colisão de builds), revisou e integrou os diffs, verificou pessoalmente os alvos dos mixins em todas as versões e rodou a matriz final e um teste ponta a ponta Paper↔Fabric. O limite de uso do Sonnet foi atingido três vezes; em cada caso os subagentes foram retomados do ponto em que pararam, sem perda de trabalho.

---

## 1. Resumo

| | Quantidade |
|---|---|
| Itens confirmados e corrigidos | 37 (10 de gravidade média — P1, S1, C13, C2, N1, N2, C1, C3, C4, Q1 —, os demais baixos; nenhum crítico ou alto confirmado) |
| Melhorias de desempenho medidas | 6 |
| Suspeitas de alta gravidade refutadas | 1 (mixins incompatíveis em versões antigas) |
| Problema estrutural encontrado só pelo teste E2E (pré-existente) | 1 — Q1, travamento de transferências em massa (ver §6) |
| Pendências documentadas | ver §7 |

Testes automatizados: plugin 181 → **239**; mod core 98 → **185**; mod fabric 25 → **43** (contagens de `build/test-results`), todos passando. Teste ponta a ponta Paper↔Fabric local: S1–S9 **PASS** em duas execuções sobre o código final. Teste com **Paper 1.21.1 e cliente Minecraft 1.21.1 reais** pela VPN: login, jogo, reconexão e desligamento aprovados (§9.1). Ver §8 e §9.

---

## 2. Arquitetura e versões efetivamente suportadas (pelo código)

- **Plugin Paper** (`clients/plugin`, Gradle 9.1.0): um código-fonte; `-PopenradPaperApi` escolhe bytecode e `api-version`. Para 1.17–1.19.2 o build gera uma cópia legada das fontes por substituição textual (`build.gradle.kts`) e adiciona `src/compat16/java`.
- **Mod Fabric** (`clients/mod`, Gradle 9.1.0, Loom 1.13.6): `core/` (Java puro, inclui cópias byte-idênticas de 42 classes do plugin, conferidas por `scripts/check-sync.py`), `fabric/` (MC 1.17–1.21.11, geração legada por substituição textual para ≤1.19.4) e `modern/` (projeto separado, Gradle 9.7.1, Loom 1.17.20, MC 26.1–26.3, Java 25).

| Alvo | Java (bytecode) | Observação |
|---|---|---|
| Paper 1.17, 1.17.1 | 16 | fontes legadas (HexFormat, sealed, RandomGenerator, parseComments, getPluginMeta) |
| Paper 1.18–1.19.2 | 17 | fontes legadas parciais |
| Paper 1.19.3–1.19.4, **1.20**, 1.20.1–1.20.4 | 17 | `1.20` sem patch corrigido nesta auditoria (antes gerava Java 21 + `api-version 1.20.6`) |
| Paper 1.20.5, 1.20.6, 1.21.x (API 1.20.6) | 21 | |
| Paper 26.1.1–26.3 | 25 | 26.1 sem patch não é suportado |
| Fabric 1.17, 1.17.1 | 16 | |
| Fabric 1.18–1.20.4 | 17 | |
| Fabric 1.20.5–1.21.11 | 21 | 1.21.11 usa `ConnectionRoutingMixin` |
| Fabric 26.1–26.3 (`modern/`) | 25 | |

Os 33 alvos Fabric e 21 alvos Paper declarados pelos scripts e pelo CI conferem entre si. Documentação desatualizada (versões 0.2.x/2.2.0, "JDK 21 obrigatório", tabela de compatibilidade antiga) está listada em §7.

### Verificação dos mixins em todas as versões (feita pelo coordenador)

A suspeita mais grave das auditorias (F8/B1: descritores de injeção inexistentes em 1.17–1.20.x causariam crash na inicialização, pois `required: true`) foi verificada contra os **mapeamentos oficiais da Mojang** de 1.17 a 1.21.11 e contra o bytecode dos JARs 26.1–26.3:

- `Connection.connectToServer(InetSocketAddress, boolean)` existe em 1.17–1.19.4; `connect(InetSocketAddress, boolean, Connection) → ChannelFuture` em 1.20.1–1.21.10; `connect(InetSocketAddress, EventLoopGroupHolder, Connection)` em 1.21.11 e 26.x.
- `ServerStatusPinger.pingLegacyServer(InetSocketAddress, …)`, `JoinMultiplayerScreen.tick()`, `LanServerList.isDirty/getServers` (≤1.19.2) e `takeDirtyServers` (≥1.19.3) e `Screen.addRenderableWidget` (26.x) existem em todas as versões correspondentes.
- No bytecode de 1.21.1, 26.1 e 26.3, `tick()` chama `takeDirtyServers()` (ponto do `@Redirect`).

Resultado: **33/33 versões OK; suspeita refutada** no nível de assinatura. A aplicação real dos mixins dentro do jogo continua testada só em 1.21.1 (smoke opcional existente).

---

## 3. Bugs encontrados e corrigidos

Legenda: **M** = média, **B** = baixa. Todos foram confirmados por leitura do caminho de código e, quando testável, por um teste de regressão escrito antes da correção (falhou antes, passa depois).

### 3.1 Plugin Paper — comandos e configuração

| ID | Grav. | Problema e causa raiz | Correção | Testes |
|---|---|---|---|---|
| P1 | M | `/orb join`, `public`, `enable`… gravavam `config.yml`/`secrets.properties` **antes** da validação completa (política offline-mode + rede pública, segredos); respondiam "rejeitada" mas a mudança ficava no disco e quebrava reloads e o próximo restart. | `ConfigEditor.Validator` roda antes de qualquer escrita; `ConfigEditor.transaction` restaura os dois arquivos byte a byte (mantendo permissões owner-only) se a aplicação falhar; mensagem diz o que foi ou não gravado. | `SetupCommandSafetyTest` (4), `ConfigEditorTest` (6) |
| P2 | B | Com segredo inválido, `/orb disable` e `reload` de config desativada lançavam antes de retirar a geração atual: a VPN continuava ligada. | Config desativada não exige segredos (`NetworkSecrets.loadFor`, `validate` retorna cedo); o template continua sendo criado. | `NetworkSecretsTest` (3) |
| P3 | B | Dentro de aspas, `\x` perdia a barra (`"pa ss\word"` → `pa ssword`). | `\` só escapa `"` e `\`. | `CommandTokensTest` (2) |
| P4 | B | `reply()` capturava `IllegalStateException`, mas o Bukkit lança `IllegalPluginAccessException` para plugin desativado: a thread admin morria no desligamento. | Checa `isEnabled()` e captura as duas exceções. | `SetupCommandSafetyTest` |
| P5 | B | Falha de migração `OpenRadBridge` → `OpenRadPlugin` logava mensagem genérica sem causa. | Log com caminhos, causa e procedimento. | `SetupCommandSafetyTest` |
| P6 | B | `vpn.networks` com item não-mapa era descartado silenciosamente por `join`; limpeza de segredo do `leave` não era best-effort. | Rejeita entrada inválida sem alterar o arquivo; `leave` best-effort como `join`. | `ConfigEditorTest` (2) |
| P7 | B | `/orb reconnect` com VPN desativada dizia "Reconexão iniciada". | Mensagem verdadeira. | `SetupCommandSafetyTest` |
| S5 | B (seg.) | Regra "senha só pelo console" era lista de bloqueio (`instanceof Player`): command blocks, minecarts e `/execute` aceitos (texto do comando fica em NBT/log). | Lista de permissão: `ConsoleCommandSender` e `RemoteConsoleCommandSender`. | `SetupCommandSafetyTest` |
| S6 | B (seg.) | Nomes vindos da rede (peers, redes do catálogo) podiam conter `§`, bidi e caracteres de formato e falsificar linhas no console. | `Wire.displayName` (peers, limite de 64 pontos de código) e `Wire.neutralised` nas saídas de console de `/orb networks`, `/orb minecraft` e logs do runtime. Nomes de rede não foram alterados na origem porque são chaves de comparação. | `WireDisplayTest`, `MembershipTest` |
| B7 | B (build) | `-PopenradPaperApi=1.20-R0.1-SNAPSHOT` gerava Java 21 e `api-version 1.20.6` (Paper 1.20 roda Java 17). | `1.20-` incluído nas listas Java 17 e `api-version '1.20'`. Build real verificado (major 61, `api-version: '1.20'`). | build |

Arquivos: `OpenRadPlugin.java`, `config/ConfigEditor.java`, `config/NetworkSecrets.java`, `build.gradle.kts`, `README.md`, `docs/installation.md`.

### 3.2 Plugin Paper — runtime e ponte TCP virtual

| ID | Grav. | Problema e causa raiz | Correção | Testes |
|---|---|---|---|---|
| S1 | M (seg., redes públicas) | Qualquer membro (rede pública = qualquer um) segurava 8 slots com SYNs nunca completados; cada SYN abria conexão ao backend; 4 membros esgotavam os 30 slots padrão. | Cota por peer `min(max, 8, max(2, max/4))` (7 com 30 jogadores), no máximo 2 handshakes incompletos por peer, prazo de handshake `min(timeout, 8 s)`. Adiar o connect do backend até o ACK foi avaliado e descartado (mudaria o contrato SYN-ACK/RST com clientes bem-comportados). | `VirtualTcpBridgeTest` (3), `PeerPollingTest` |
| C13 | M | `RuntimeException` em `process/tick/emit` matava a thread do seletor da ponte silenciosamente; o runtime continuava publicando CONNECTED e alimentando uma ponte morta. | Falhas contidas por frame/segmento/IO/timer (RST só no fluxo afetado, log com limite de taxa); `Error` encerra visivelmente e o runtime detecta (`isRunning`) e reconecta. | `VirtualTcpBridgeTest` (3), `VpnRuntimeTest` |
| C2 | M | `OutOfMemoryError` ao criar thread de peer (Java 17 + limite de threads da hospedagem) matava a thread de controle; status ficava CONNECTED parado. | Tratado como falha do peer (backoff, aviso); `Error` inesperado na thread de controle vira RETRY/FAILED com motivo. | `VpnRuntimeTest` (2) |
| C1 | M (perf.) | Cada worker de peer acordava a cada 20 ms (2 ms ativo) mesmo ocioso. | Espera de até 200 ms sem fluxos nem frames na fila (limitada pelos próximos keepalive/anúncio LAN). | `PeerPollingTest`, medição §5 |
| C8 | B (perf.) | Seletor da ponte acordava a cada 10 ms mesmo só com TIME_WAIT. | Dorme até a expiração do TIME_WAIT mais próximo, ou 1 s sem conexões. | `VirtualTcpBridgeTest` |
| N2-call | — | Reset do guarda de relay a cada nova anexação. | Na integração do PR #23 substituído por `AcceptorRelayGuard` por runtime (`startAttachment()` em cada anexação); o guarda estático foi removido. | `AcceptorRelayGuardTest` |

Arquivos: `bridge/VirtualTcpBridge.java`, `runtime/VpnRuntime.java`, `docs/virtual-tcp.md`.

### 3.3 Motor de rede compartilhado (plugin + cópia idêntica no mod)

| ID | Grav. | Problema e causa raiz | Correção | Testes |
|---|---|---|---|---|
| N1 | M | Um registro de candidato inválido (op 6/29/7: mais de 32 candidatos, CID errado, endereço mapeado inválido) abortava todo o `connect()`/`accept()` com `IllegalArgumentException` e punha o peer em backoff. O Rust (`src/peer.rs:739-768`, `src/incoming.rs:565-579`) descarta só aquele caminho. | Paridade com o Rust: o registro inválido é descartado e logado (DEBUG); a validação de relay (op 11/23) segue estrita como no Rust. | `PeerConnectorTest` (2) |
| N2 | M | `ACCEPTOR_RELAY_ALLOWED` era estático e, uma vez desligado por uma recusa até 5 s após qualquer pedido de relay, nunca mais voltava (dura semanas num servidor Paper): peers só-relay deixavam de alcançar o host. | Na versão integrada (PR #23), o guarda é uma instância `AcceptorRelayGuard` por runtime: só um pedido de relay realmente enviado por aquela anexação pode ser desligado por uma objeção nos 5 s seguintes, o estado sobrevive à reanexação automática (paridade com o Rust) e é zerado em `/orb reconnect`, reload e reinício do mod. O guarda estático de `PeerConnector` foi removido. | `AcceptorRelayGuardTest` |
| S3 | B (seg.) | O listener TCP direto (bind curinga, até 40 s) contava sockets brutos: 8 conexões de lixo de qualquer host impediam o peer legítimo de entrar. | `RendezvousGate`: só falhas contam no orçamento; limite de sockets não validados total (8) e por IP (4, com despejo do mais antigo); prazo do rendezvous 3 s → 2 s. | `PeerConnectorTest` |
| C3 | M (perf.) | `FramedTcp.writeRaw` agendava e cancelava uma tarefa num timer global a cada frame enviado. | Timestamp por stream + um watchdog daemon único (varredura a cada 250 ms, encerra quando não há streams). Prazo de escrita preservado (5,0–5,25 s). | `TransportTest` (4), benchmark §5 |
| B10 | testes | Testes espelhados do mod estavam defasados (5 classes) e 12 testes de classes espelhadas não existiam no mod (inclusive o da correção IPv6 da 2.3.2). | Testes alinhados/copiados para `clients/mod/core/src/test/.../openradplugin/`. | +12 classes |

Arquivos (nos dois módulos): `peer/PeerConnector.java`, `transport/FramedTcp.java`, `protocol/Wire.java`, `network/Membership.java`.

### 3.4 Mod Fabric — interface, controlador e mixins (`fabric/` e `modern/`)

| ID | Grav. | Problema e causa raiz | Correção | Testes |
|---|---|---|---|---|
| F1 | B-M | Vazamento de memória: o registro de botões era `WeakHashMap<Screen, Button>`, mas o `onPress` do botão capturava a própria tela (chave); nenhuma tela de título/pausa/multijogador era coletada. | `MenuButtonRegistry`: a ação só referencia a tela por `WeakReference`. | `MenuButtonRegistryTest` (GC real) |
| F2 | B | Após redimensionar a janela, o tooltip de status parava de atualizar (o Fabric recria os eventos da tela e o handler não era re-registrado). | Handler re-registrado em todo `AFTER_INIT`. | — (fiação Fabric) |
| F3 | B | Corrida: o menu lia `config()` antes de `initialized()`; aberto no instante da inicialização, podia salvar a configuração padrão por cima da real. | Ordem de leitura invertida (`OpenRadMenuDraft.opening`). | `OpenRadControllerTest` |
| F4 | B-M | O motivo da falha (`Snapshot.detail()`: identidade usada em outro cliente, registro ambíguo, nova tentativa em N s) nunca era mostrado. | Exibido em `/openrad status` e no menu em FAILED/RETRY_WAIT. | `OpenRadControllerTest` |
| F5 | B | Se o runtime antigo não parava em 15 s na troca, o controlador ficava com um runtime fechado instalado e config divergente do disco; "Conectar" mostrava "sem redes" para sempre. Mensagens de erro genéricas ("falha ao salvar" para timeout de desconexão). | Estado recuperável (nova tentativa só depois que o dono antigo da identidade parar), chaves de mensagem distintas, "não iniciado" × "sem redes". | `OpenRadControllerTest` (5) |
| F6 | B | `/openrad connect` com arquivo de config inválido renomeava o arquivo do usuário e gravava o padrão. | O comando recusa; só um Salvar explícito no menu preserva e sobrescreve. | `OpenRadControllerTest` |
| F7 | B | Tela avançada: limpar as listas com auto-connect ligado dava "erro de config" sem saída além de Cancelar. | Auto-connect desliga quando as listas ficam vazias; flag de erro limpa ao editar. | `OpenRadAdvancedScreenTest` (4) |
| F9 | B | `setSoTimeout(60 s)` no socket do jogo derrubava a conexão se o cliente ficasse 60 s sem enviar (ex.: fila de login pré-1.20.2 com tráfego só do servidor). | Ociosidade medida nos dois sentidos, 5 min. | `LoopbackBridgeTest` (3) |

Arquivos (nas duas árvores): `OpenRadClientMod`, `OpenRadController`, `OpenRadMenuScreen`, `OpenRadMenuDraft`, `OpenRadAdvancedScreen`, `LoopbackBridge`, `MenuButtonRegistry` (novo), lang `en_us`/`pt_br`/`ja_jp`; `docs/ARCHITECTURE.md`.

### 3.5 Mod — núcleo (`core/.../openradclient`)

| ID | Grav. | Problema e causa raiz | Correção | Testes |
|---|---|---|---|---|
| N3 | B | Em SYN_SENT a pilha ignorava ACK inaceitável (RFC 793 exige RST); com porta reutilizada que a ponte ainda tinha ESTABLISHED, o connect travava até o timeout. | RST com `seq = SEG.ACK`, handshake continua. | `OutboundTcpStackTest` |
| N4 | B (perf.) | Silly window: cada leitura de 1 byte com janela quase fechada gerava ACK de janela minúscula. | Atualização só quando a janela cresce ≥ 1 MSS (RFC 1122). | `OutboundTcpStackTest` (2) |
| N5 | B | Fechamento abortava com RST 30 s após `close()`/FIN mesmo com progresso (a ponte usa 2 min por progresso). | 120 s contados a partir do último progresso. | `OutboundTcpStackTest` (2) |
| C7 | B-M (perf.) | Cópias redundantes por segmento (parse em camadas, `payload()` clonando, codificação em 3 camadas). | Admissão in-place (`admitTcp`) e `encodeIpv4Frame`; equivalência byte a byte testada. | `OutboundTcpStackTest` (3), medição §5 |
| C8 | B (perf.) | Timer de 20 ms e poll de 100 ms rodavam com o cliente ocioso (~60 despertares/s). | Timer só com conexões; sender com `take()` bloqueante. | `OutboundTcpStackTest` |
| C4 | M (perf.) | Sob tráfego de presença, a varredura O(n) do roster rodava até 100×/s na thread de controle do cliente. | `PeerScanSchedule`: no máximo ~10/s, reação a peer novo em ≤100 ms. | `PeerScanScheduleTest` |
| C12 | B | `synchronized` + `wait` em threads virtuais prendia carriers (Java 21). | `ReentrantLock` + `Condition` em `OutboundTcpStack.Connection` e `ClientPeerService`. | suítes existentes |
| R1 | B | `runConnected` esperava workers para sempre engolindo interrupções; a identidade ficava presa e "Conectar" falhava sem explicação. | Espera limitada (15 s) com log; um "keeper" segura o lock da identidade até o worker travado terminar (nunca dois donos). | `PeerShutdownTest` (4) |
| N2-call | — | Reset do guarda de relay por anexação. | `AcceptorRelayGuard` por runtime, recriado a cada `ClientVpnRuntime`. | `AcceptorRelayGuardTest` |

---

## 4. Suspeitas investigadas e descartadas

| ID | Suspeita | Resultado |
|---|---|---|
| F8/B1 | Mixins com descritores inexistentes em 1.17–1.20.x → crash ao iniciar | Refutada para as 33 versões (ver §2). |
| N7 | `Membership.ownVip` exige 26/8 (o Rust aceita qualquer IP) | Divergência real mas **mantida**: `OutboundTcpStack` e o roteamento do mod dependem de 26/8; o serviço Radmin sempre atribui 26/8. Teste `ownVipMustBeInTheServiceRange` documenta. |
| F9 | Timeout de 60 s necessário para teardown | Não era; substituído (ver F9). |
| P8 | `/orb status` mostra offline enquanto a geração anterior ainda para | Real só se o stop passar de 12 s; não corrigido (§7). |

---

## 5. Desempenho (medições)

| Item | Antes | Depois | Como foi medido |
|---|---|---|---|
| C1 – despertares do worker de peer ocioso (plugin) | ~32,7/s por peer (3266/s com 100 peers) | ~4,7/s por peer (467/s) | `PeerPollingTest`, 100 pares `FramedTcp` reais, 3 s |
| C3 – `FramedTcp.send` (128 B, 200k registros) | 288–312 B alocados/registro; 42,5–44,6 µs CPU/registro | 152–176 B; 33,8–38,5 µs | `FramedTcpSendBenchmark` (test tree), ThreadMXBean; máquina carregada |
| C7 – alocação por segmento de 1460 B (mod) | entrada 15 872 B; saída 12 320 B | 4 832 B (−70%); 6 176 B (−50%) | `OutboundTcpStackTest`, ThreadMXBean |
| C8 – despertares da pilha TCP do mod ociosa | ~60/s | 0 | contadores em `OutboundTcpStackTest` |
| C4 – varreduras do roster sob 1 registro/ms (mod) | >300 em 5 s | ≤51 em 5 s | `PeerScanScheduleTest` |
| C8 – seletor da ponte só com TIME_WAIT | ~50 passadas/500 ms | 0 | `VirtualTcpBridgeTest` |
| Q1 – transferência em massa Paper↔Fabric (8 MiB em cada sentido, uma conexão) | 0,02–0,79 MiB/s, nunca completa em 88 s (código original e intermediário) | 6,9–7,7 MiB/s, SHA-256 íntegro | E2E S5, loopback |

---

## 6. Q1 — travamento de transferências em massa (encontrado pelo teste E2E)

**Gravidade: média** (afeta carregamento de chunks e qualquer tráfego intenso, sobretudo em hosts Windows). **Pré-existente**: reproduz no código original da 2.3.2.

- **Sintoma.** No cenário S5 (8 MiB ecoados nos dois sentidos numa conexão virtual após o login), a vazão caía para 0,02–0,09 MiB/s e a transferência não terminava. Como consequência, o controle do mod ficava 90 s sem tráfego e caía (o servidor Go local não responde heartbeats; o oficial responde).
- **Investigação.** A primeira bisseção (coordenador) sugeriu regressão nas correções da ponte e da pilha do mod, porque a variante com as duas revertidas passou uma vez a 4,58 MiB/s. A bisseção fina (subagente, com thread dumps e um modelo em processo `OutboundTcpStack` + `VirtualTcpBridge` com fila limitada drenada a cada 15 ms) mostrou que a mesma variante falha 6/6 no estado posterior da máquina: o resultado dependia da resolução do timer do Windows (15,6 ms, ou 1 ms quando outro programa a eleva).
- **Causa raiz.** Os frames que a pilha gera para um peer passam por uma fila limitada (`ArrayBlockingQueue` de 128 no mod, `ClientPeerService`; 256 no plugin, `VpnRuntime`) drenada só entre esperas `ready(2 ms)`, que no Windows duram ~15 ms. Um fluxo bidirecional saturado produz mais frames por período do que a fila comporta (pico medido 123–157); o `offer` falhava e o frame era perdido em silêncio. A recuperação era só por RTO: um ACK cumulativo que avança até o próximo buraco não reiniciava o timer nem retransmitia esse buraco (cada buraco esperava seu próprio timeout, com RTO dobrando e cwnd voltando a 1 MSS). A pilha também enviava fragmentos menores que um MSS sempre que poucos bytes de janela abriam, multiplicando o número de frames.
- **Por que os testes não pegavam.** Os testes unitários usam relógio manual, enlaces sem latência e filas ilimitadas; nada verificava frames descartados.
- **Correção.**
  - Mod: `PeerFrameQueue` (512 posições; com fila cheia o produtor espera até 25 ms e só então descarta, com 1 s de penalidade para que um peer travado não segure a thread que serve todos; descartes contados). O worker continua sem esperar quando a drenagem foi cortada pelo limite de 256.
  - Plugin: `VpnRuntime.FrameQueue` (512 posições, não bloqueante porque o produtor é a thread do seletor; descartes contados e logados no máximo a cada 10 s).
  - As duas pilhas TCP: retransmissão do próximo buraco em ACK parcial (RFC 6582, sem reduzir cwnd nem dobrar o RTO) e prevenção de *silly window* no remetente. No mod, reinício do timer de retransmissão em ACK novo (RFC 6298 §5.3), que a ponte já fazia.
  - Memória: a ocupação real das filas é limitada pelo controle de fluxo do TCP (≈ uma janela de 64 KiB por fluxo), não pelas 512 posições.
- **Testes de regressão.** `BulkTransferUnderLossTest` (mod, 3 testes: perda em rajada de 30 frames, fila de 48 posições drenada a cada 15 ms, fila de produção sem descartes) — os três estouram 90 s no código original e passam no novo; `OutboundTcpStackTest` (+2), `PeerFrameQueueTest` (5), `VirtualTcpBridgeTest` (+2: retransmissão parcial em < 300 ms; janela de 3000 B gera exatamente [1460, 1460]), `VpnRuntimeTest` (+1).
- **Arquivos.** `clients/mod/core/.../openradclient/PeerFrameQueue.java` (novo), `ClientPeerService.java`, `virtual/OutboundTcpStack.java`; `clients/plugin/.../runtime/VpnRuntime.java`, `bridge/VirtualTcpBridge.java`.
- **Risco residual.** Ainda pode haver descarte sob carga extrema (muitos peers ou fluxos) — agora limitado, contado (e logado no plugin) e recuperado rapidamente; no mod o contador ainda não aparece na interface. O worker de peer continua drenando a partir de um laço com timer grosso; uma thread escritora por peer eliminaria isso, mas o estado da cifra tem dono único. Os testes de massa dependem de tempo real (limites de 20–40 s) e podem ser sensíveis em CI muito carregado.

---

## 7. Pendências (não corrigidas) e recomendações

Segurança / robustez
- **S2 (média-baixa):** o mod guarda a identidade (RID + credencial) em `config/openrad-client/state/`, que costuma ir junto em exportações de modpack. Recomenda-se mover `state/` para um diretório de dados do usuário com migração por *move* (nunca cópia) preservando `registration.pending`. Não feito por ser mudança de layout com risco de registrar um segundo dispositivo.
- **C5 (média):** no plugin, `/orb reload` ou um segundo comando de setup durante o primeiro registro interrompe o registro e deixa `REGISTRATION_RECOVERY_REQUIRED` (recuperação manual). O mod já adia o fechamento durante o registro; o teste `stoppingAmbiguousRegistrationPreservesIntentAndCannotCreateLateIdentity` fixa o comportamento atual do plugin, então a mudança é uma decisão de design para o mantenedor.
- **S4 (baixa):** candidatos de peer em `169.254.0.0/16` (inclui metadados de nuvem) são aceitos; payload é binário e cego. Não alterado para não quebrar LANs APIPA e manter paridade com o Rust.
- **S7 (baixa):** o listener loopback do mod (5 s, porta efêmera) pode ser disputado por outro processo local; mitigação sugerida: endereço aleatório em 127/8 (exceto macOS).
- **S3 residual:** inundação a partir de ≥8 IPs distintos, ou do mesmo NAT do peer legítimo, ainda pode atrasar o TCP direto (há fallback UDP/relay).
- **S6 residual:** nomes do catálogo no seletor do mod não são neutralizados (a linha do tooltip é reescrita literalmente pelo build legado); MOTD de LAN com `§` é comportamento normal do Minecraft.
- **C9/C10/C11 (baixa, suspeitas):** janela de microssegundos na revogação por época; buffer de remontagem UDP dimensionado pelo total declarado pelo peer (~1 MiB por link); escrita TCP/relay sem backpressure com tráfego bidirecional intenso.
- **P1 residual:** se `apply` falhar depois de já ter retirado a geração anterior, os arquivos voltam mas o runtime não. **P9:** em Paper 1.17/1.18 os comentários do `config.yml` são movidos para o topo a cada gravação. **P8:** status durante parada lenta.

- **Resiliência à queda do coordenador (média, design):** quando a sessão de controle cai (coordenador reiniciado, rede instável, timeout), `VpnRuntime` e `ClientVpnRuntime` reiniciam a geração inteira e todos os jogadores conectados pela VPN são desconectados, mesmo com o canal direto entre os peers ainda válido. Observado no teste real (§9.1). Recomenda-se manter os canais de peer e os fluxos TCP virtuais vivos durante a reanexação (só revogando peers que deixarem de ser membros depois dela), avaliando a paridade com o cliente Rust.

Desempenho
- **C1 completo:** o worker de peer ainda é baseado em polling com exceção de timeout; um modelo orientado a eventos (Selector) eliminaria o restante. **C2:** `max-peers: 0` (padrão) significa até 4096 peers; recomenda-se um padrão limitado. **C4 no plugin:** a varredura O(n) a cada 200 ms não foi otimizada (sem contador de versão em `Membership`). **C6:** `ReliableUdp` alterna o modo bloqueante duas vezes por datagrama.

Testes, build e documentação
- **N6:** `MinecraftTcpInteropTest` usa uma cópia congelada antiga da ponte (`ReferenceServerTcpBridge`, 0.2.4-dev). O teste E2E (§9) cobre a ponte atual contra a pilha atual, mas o fixture deveria ser atualizado.
- `modern/` não tem testes próprios (as fontes são cópias do `fabric/` sem verificação de deriva); `PaperAddressForwarder` e o ciclo de vida do `OpenRadPlugin` não têm teste automatizado; JDK 16 nunca é executado (alvos 1.17).
- `build-matrix.ps1` aceita só 4 versões e o teste `tests/test_minecraft_build_matrix.py` (fora do escopo) espera JARs 2.3.0; o smoke Fabric só existe no workflow 2.2.0 e está fixo em 1.21.1; ferramentas `package-full-range.py`/`report-full-range.py` referenciam caminhos e versões antigos.
- Documentos com versões antigas: `clients/mod/docs/COMPATIBILITY-*.md`, `BUILD-STATUS-1.21-26.3.json`, `TESTING.md`, partes de `clients/mod/README.md`, `clients/plugin/docs/host-compatibility.md`, `installation.md` (cabeçalho 2.2.0), `AGENTS.md` (2.3.0).
- Teste `LoopbackBridgeTest.transfersBothWays…` falhou uma vez sob carga extrema (build 1.19.2 com o E2E rodando em paralelo); 3 + 5 reexecuções passaram. Usa `PipedInputStream`, sensível a carga.

Fora do escopo (encontrado durante a validação)
- Servidor Go: não responde a heartbeats (op 4) — `server/internal/server/control.go:145` — então clientes Java caem após 90 s sem tráfego de controle quando usam o servidor local; o relay não detecta o fechamento do lado que espera (`relay.go:112-124`), segurando o socket até 75 s; `server/scripts/e2e_python.py` não roda no Windows nativo (`fcntl`, SIGINT).
- `PeerConnector.java:492` usa `ServiceConfig.modulus()` direto em vez do módulo passado ao runtime (sem efeito hoje).

---

## 8. Testes e builds

Linha de base (antes de qualquer mudança): `clients/plugin clean check` e `clients/mod -PopenradMinecraftVersion=1.21.1 :core:test :fabric:build :fabric:test` passavam.

Código final (todas as correções, incluindo Q1):

| Comando | Resultado |
|---|---|
| `clients/plugin: gradlew clean check` (API 1.20.6, Java 21) | OK — 239 testes, 0 falhas, 1 ignorado (teste de symlink POSIX no Windows); `verifyJar`: 133 classes Java 21 |
| `clients/plugin: -PopenradPaperApi=1.17.1-R0.1-SNAPSHOT clean check` (Java 16, fontes legadas) | OK — 239 testes; 135 classes Java 16 |
| `clients/mod: -PopenradMinecraftVersion=1.21.1 :core:test :fabric:build :fabric:test --rerun-tasks` | OK — core 185 testes (1 ignorado), fabric 43; `verify-version-jar.py` OK |
| `clients/mod: :fabric:build` 1.17.1 (Java 16), 1.19.2 (Java 17), 1.21.11 (`ConnectionRoutingMixin`) | OK |
| `clients/mod/modern: -PopenradMinecraftVersion=26.3 build` (Java 25) | OK (sem testes próprios) |
| `python scripts/check-sync.py` | OK (42 classes espelhadas idênticas) |
| Teste E2E (§9), duas execuções | S1–S9 PASS nas duas |

Antes da correção Q1 (todas as demais correções já aplicadas) também passaram: Paper 1.20 (Java 17, `api-version 1.20`), 1.20.4 (Java 17) e 26.3 (Java 25); Fabric 1.20.1; modern 26.1; `python -m unittest discover -s clients/mod/tools` (10 testes). Q1 só alterou classes Java comuns a todos os alvos, e a geração legada foi revalidada em 1.17.1 e 1.19.2.

Ocorrência intermitente: `LoopbackBridgeTest.transfersBothWaysWithFixedDestinationAndConsumesEndpoint` falhou uma vez (timeout) no build 1.19.2 com o E2E rodando em paralelo; 3 + 5 reexecuções, inclusive sob carga, passaram.

---

## 9. Teste ponta a ponta Paper ↔ Fabric (local)

O processo A executa o `VpnRuntime` real do plugin, configurado como em `OpenRadPlugin.apply()` (YAML → `VpnSettings`, `NetworkSecrets`, `GenerationGate`), com um backend Minecraft falso (status, ping, login offline, eco). O processo B executa o `ClientVpnRuntime` real do mod e abre conexões por `openTcp()`, o mesmo caminho da `LoopbackBridge` do Fabric. Coordenador: `openrad-server` (Go, deste repositório) em `127.0.0.1:17301/17302`. A cópia das fontes usada no teste tem `ServiceConfig` apontando para `127.0.0.1` e para o módulo RSA do servidor local; o harness aborta se encontrar o endereço ou o módulo oficiais em qualquer classe compilada, e a amostragem de sockets mostrou **nenhuma conexão fora do loopback**. O log do servidor mostra exatamente dois registros, ambos de `127.0.0.1`.

Última execução sobre o código final:

| Cenário | Resultado |
|---|---|
| S1 coordenador | PASS — registro + anexação em ~0,53 s; ambos membros de "OpenRad E2E" |
| S2 descoberta/handshake | PASS — canal DIRECT_TCP em ~1,5 s; `openTcp` ~80–100 ms |
| S3 status Minecraft | PASS — JSON íntegro; RTT ping/pong ~30 ms |
| S4 login Minecraft | PASS — Login Success íntegro (UUID offline) |
| S5 tráfego em jogo | PASS — 8 MiB em cada sentido em 1,07–1,15 s (6,9–7,5 MiB/s por sentido), SHA-256 igual; 20 + 100 conexões curtas sequenciais (reuso de porta e TIME_WAIT) |
| S6 anúncio LAN | PASS — o mod lista o servidor com o MOTD reescrito para o VIP |
| S7 relay forçado | PASS — no mod, no plugin e nos dois; caminho RELAY confirmado; status e login OK (4–4,7 s para obter canal com relay forçado de um lado só; 0,5 s com os dois) |
| S8 falhas e reconexão | PASS — (a) reinício do coordenador: reanexação em ~3,3 s; (b) plugin fechado com conexão aberta: o mod percebe em ≤ 0,2 s; (c) plugin reiniciado: alcançado de novo em ~0,7 s |
| S9 encerramento | PASS — nenhuma thread `openrad`/`orb` nem socket sobrando (sockets de relay sem par podem ficar até 75 s no servidor Go) |

### 9.1 Teste com Paper e Minecraft reais (autorizado pelo usuário)

Executado pelo coordenador em 2026-11-15, com o código final, numa cópia isolada das fontes (fora do repositório) em que só `ServiceConfig` (as duas cópias, idênticas) e o modelo `config.yml` apontam para `127.0.0.1` e para o módulo RSA do servidor local. Varredura dos JARs gerados: nenhum arquivo com o endereço do coordenador oficial. EULA do Minecraft aceita apenas na pasta do servidor de teste, com autorização explícita do usuário.

- Servidor: **Paper 1.21.1 build 133** (oficial, SHA-256 `39bd8c00…d287b9` conferido na API v3 do PaperMC), JDK 21.0.11, `online-mode=false`, ligado só em `127.0.0.1`, comandos de console por RCON local. Plugin com `bridge.enabled: true`, rede pública "OpenRad Real", `security.allow-insecure-offline-public-networks: true` (necessário em modo offline com rede pública; o plugin emite o aviso correspondente).
- Cliente: **Minecraft 1.21.1 + Fabric Loader 0.17.3 + Fabric API 0.116.17 + mod OpenRad**, executado pelo `runClient` do Loom, usuário offline `OpenRadTester`, `auto-connect=true`, entrada automática por `--quickPlayMultiplayer 26.218.108.201:25565` (IP virtual do plugin).
- Coordenador: `openrad-server` local. Todos os registros e anexações no log vieram de `127.0.0.1`.

| Verificação | Resultado |
|---|---|
| V1 plugin no Paper real | PASS — carrega, habilita, CONNECTED, VIP `26.218.108.201`, entra na rede; nenhum erro do plugin no log |
| V2 mod no cliente real | PASS — mod inicializa (`OpenRad Client Mod initialized`), anexa e abre canal DIRECT_TCP com o plugin (`orb peers`) |
| V4 login real pela VPN | PASS — `OpenRadTester[/26.0.116.198:…] logged in`: a conexão passou pela ponte loopback do mod e o Paper registra o **IP virtual do mod** como origem, ou seja, o `PaperAddressForwarder` funciona no Paper real |
| V5 comunicação em jogo | PASS — mensagens do console (`say`) chegaram ao chat do cliente; 7 teleportes de milhares de blocos com carregamento de chunks por mais de 150 s, sem queda e com **0 retransmissões** TCP na ponte |
| V6 reconexão | PASS — `orb reconnect` derruba o jogador (comportamento documentado) e a nova geração volta a CONNECTED com o peer reautenticado em segundos; nova entrada do cliente funciona. O reinício do coordenador também foi coberto: plugin e mod reanexam sozinhos (~11 s) |
| V7 desligamento | PASS — `stop` com o jogador conectado: `Disabling OpenRadPlugin` sem erro, processo encerrado em 5,5 s (incluindo salvar os mundos), nenhuma thread pendurada; cliente recebeu a desconexão; nenhum processo do teste restou |
| V3 ping da lista de servidores | não exercitado no cliente real (o quick play vai direto ao login); coberto pelo S3 do E2E headless |

Achado do teste real: com o servidor Go local, o jogador caiu aos 70 s porque a sessão de controle do plugin estourou o timeout de 90 s. O servidor Go não responde heartbeats (`server/internal/server/control.go:145`); o serviço oficial responde op 4 com op 17. Uma cópia de teste do servidor que responde op 17 eliminou a queda. Isso expôs um comportamento de projeto que vale registrar: **uma queda da sessão de controle (coordenador) derruba todos os jogadores**, porque o runtime reinicia a geração inteira, embora o canal direto entre os peers continue funcionando. Ver §7.

### 9.2 Revalidação da árvore integrada do PR #23 (2.4.0-dev)

A branch `audit/minecraft-stability` combinou esta auditoria com mudanças anteriores (coordenadores personalizados, `AcceptorRelayGuard` por runtime, `ServerNameResolverMixin`, Linux/Windows/Rust/Go). Revalidação feita num clone limpo do PR:

- As 37 correções foram conferidas mecanismo a mecanismo; todas estão presentes. A N2 foi substituída pelo `AcceptorRelayGuard` por runtime; o guarda estático que sobrou em `PeerConnector` foi removido.
- **Regressão de integração corrigida:** a releitura estrita de `secrets.properties` (senha fora de 6..256 em qualquer chave rejeitava o arquivo inteiro) quebrava `ConfigEditorTest.aNetworkWithAnInvalidPasswordCanStillBeRemoved`. A leitura voltou a ser tolerante e só as senhas de redes ativadas são validadas.
- **CI:** o workflow de compatibilidade Minecraft não disparava para PRs em `2.4.0-dev`; o gatilho foi incluído.
- Alvos de `ServerNameResolverMixin` conferidos em 33/33 versões (mapeamentos oficiais; bytecode em 1.21.1, 26.1 e 26.3).
- Testes locais: plugin 258 (1.20.6, 1.17.1 e 26.3), mod core 190, Fabric 50, builds Fabric 1.17.1/1.19.2/1.20.1/1.21.1/1.21.11 e modern 26.1/26.3, `check-sync`, Python 54, Go (`vet`, `test`, `gofmt`), Rust (`fmt`, 378 testes, `clippy -D warnings`), todos sem falhas.
- **Teste real** com os JARs do PR **sem nenhum patch**: Paper 1.21.1 build 133 e cliente Minecraft 1.21.1, apontados para o servidor Go do PR por `coordinator.properties` e `modulus.bin` (plugin e mod). O plugin usou o coordenador personalizado apesar de `bootstrap-host` ainda apontar para o serviço oficial, guardando a identidade em `coordinators/127.0.0.1-17301-<fingerprint>`. O jogador entrou pelo IP virtual (`/26.221.175.139`), ficou 173 s com 7 teleportes longos, chat entregue, 0 retransmissões e sem queda (o servidor do PR responde heartbeats), voltou após `orb reconnect` e o Paper parou em 6,3 s sem erro. Um monitor de conexões não registrou nenhum tráfego dos processos de teste para fora do loopback nas portas da VPN nem para o coordenador oficial.

---

## 10. Limitações da validação

- O teste real (§9.1) cobriu Paper 1.21.1 e Minecraft 1.21.1 apenas, um jogador, em loopback, com o coordenador Go local; outras versões só foram compiladas. Os cenários de volume e falha (S5–S9) usam o E2E headless com backend Minecraft falso.
- Rede só por loopback, contra o servidor Go local (marcado como experimental no Windows); nenhum contato com o serviço oficial Radmin (asserções de segurança e checagem de sockets no harness).
- Interoperabilidade com clientes Radmin oficiais e com o serviço oficial não foi testada.
- Fiação de UI (tooltip após resize, linha de detalhe no menu) só foi compilada, não renderizada.
- Alvos legados foram compilados por amostragem (Paper 1.17.1/1.20/1.20.4/26.3; Fabric 1.17.1/1.19.2/1.20.1/1.21.1/1.21.11; modern 26.1/26.3), não todos os 54.

---

## 11. Como continuar

```
# Plugin (JDK 21; alvos ≤1.20.4 usam toolchain JDK 25)
cd clients/plugin && ./gradlew clean check
./gradlew -PopenradPaperApi=1.17.1-R0.1-SNAPSHOT clean check
# Mod
cd clients/mod && ./gradlew -PopenradMinecraftVersion=1.21.1 :core:test :fabric:build :fabric:test
cd clients/mod/modern && ./gradlew -PopenradMinecraftVersion=26.3 build
# Sincronia plugin↔mod
python scripts/check-sync.py
```

O harness E2E (fora do repositório, na pasta temporária da sessão) copia a árvore atual, troca `ServiceConfig` só na cópia (coordenador `127.0.0.1` e módulo do servidor local), compila com `javac`, sobe `openrad-server` e roda os cenários S1–S9. Para reproduzi-lo em outra máquina: compilar `server/` com `go build`, rodar `openrad-server --db <tmp> serve --public "OpenRad E2E" --public-ip 127.0.0.1`, e construir `VpnRuntime` e `ClientVpnRuntime` com `ServiceConfig` corrigido numa cópia — **nunca** rodar os scripts `clients/plugin/scripts/Live*.java` sem esse ajuste, pois eles registram identidades no serviço oficial.

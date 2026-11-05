# OpenRad — auditoria técnica para 2.3.0

Data: 5 de novembro de 2026, America/Sao_Paulo. Branch analisada: `2.3.0-dev`. Este relatório descreve patches locais e a evidência disponível; não autoriza nem declara uma release estável.

## 1. Resumo executivo

Foram confirmados **19 defeitos**, com **19 correções locais** distribuídas pelos oito escopos A–H. A contagem agrupa entradas que compartilham a mesma causa: envio/recebimento UDP cancelado formam um defeito; persistência/concorrência/rollback de preferências Android formam uma transação defeituosa. Melhorias adicionais de cobertura/documentação não aumentam a contagem. Classificação consolidada: **0 críticos, 1 alto, 15 médios, 3 baixos**. Não foi demonstrada execução remota de código, quebra remota de autenticação ou exploração criptográfica.

As correções atingem cancelamento, identidade concorrente, precisão de listas, instalação/desinstalação, descritores Linux, TCP virtual e forwarding Paper, build legacy Fabric, lifecycle/persistência/busca Android, correlação e prazo da API Python e seleção de CI. Nenhuma funcionalidade ou formato de protocolo foi removido; fixtures canônicos e 41 classes Java espelhadas permanecem sincronizados.

Validação principal: workspace Windows com 361 testes aprovados; core/CLI Linux com 294 aprovados; Clippy Windows; Python em Linux nos dois backends; Paper e targets Fabric selecionados; harness JVM Android parcial; NSIS compilado e mutex testado sem executar setup real. Há limitações materiais: APK/Gradle Android completo, GUI Linux e rasterização, driver/TAP/UAC real, matriz Minecraft completa, gameplay e interoperabilidade com serviço/cliente oficial não foram qualificados. **A versão 2.3.0 ainda não está pronta para publicação.**

## 2. Arquitetura identificada

| Componente | Implementação e fluxo |
| --- | --- |
| Rust canônico | `src/crypto.rs`, `protocol.rs`, `session.rs`, `network.rs`: registro, autenticação SH/RSA/CBC, framing, memberships e operações correlacionadas. |
| Engine e transportes | `runtime.rs`, `peer.rs`, `incoming.rs`, `udp.rs`, `tunnel.rs`, `wake.rs`, `scheduling.rs`: setup TCP/UDP/relay, worker vencedor, encaminhamento Ethernet, filas limitadas, cancelamento e snapshots. |
| Daemon e identidade | `daemon.rs`, `file_io.rs`, `output.rs`: uma engine por perfil, locks, IPC Unix/named pipe, persistência e reconexão. Frontends compartilham identidade e sessão. |
| Desktop | egui/eframe em `desktop/src/`: UI, backend IPC fora da thread UI, vault, caches e listas virtualizadas. Fechar janela mantém serviço; Stop/Disconnect o encerra. |
| Plataformas | Linux: helper curto autorizado cria TAP efêmero e transfere FD. Windows: TAP-Windows6, I/O overlapped com buffers próprios, endereços/rotas de sessão, worker de setup e launcher NSIS. |
| Android | Kotlin/Compose: engine JVM, `EngineHost`, foreground `VpnService`, TUN L3, `protect()` para sockets e persistência Keystore/SharedPreferences. |
| Python | `radproto.py`/`radvpn.py`/`openrad.py`: crypto/engine Linux, API asyncio e API UDP ORAD v1 autenticada com dedup. |
| Minecraft | Engine Java Paper, TCP virtual e origem VPN para o backend; classes compartilhadas copiadas byte a byte ao Fabric core; UI/adaptações por versão/JDK. |

Fluxo principal: perfil/identidade → conexão de controle autenticada → memberships/candidatos → corrida limitada de transportes → autenticação mútua do peer → frames validados → encaminhamento/TAP/TUN/bridge. Eventos retornam ao daemon e frontends sem mover a propriedade do cipher por peer. Reset e save devem preservar identidade emitida em falhas de disco/vault.

## 3. Estado e versão do código

- Checkout existente: `<local-user>\Downloads\openrad-audit\openrad`.
- HEAD inicial e final: `ba4fd57bb073e3655caeea18bc0e5f3720c68f3a`; branch `2.3.0-dev`. O índice/worktree inicial estava limpo. Nenhuma branch foi trocada e nenhum commit foi criado.
- `git fetch origin --prune` no início e na consolidação: `HEAD...origin/2.3.0-dev` tem `0 0`; sem commits novos pendentes nesse remoto durante a auditoria. Consulta de PRs abertos retornou zero. Isso não cobre forks desconhecidos ou commits privados.
- Base publicada `v2.2.0`: `5404488e38e896269d4fee07d285b7f665eef528`. Delta inicial: 108 arquivos, 5.687 inserções, 1.081 remoções. O tip da auditoria anterior `a6718d5` já é ancestral do HEAD; não foi reaplicado.
- Mudanças recentes relevantes: DNS limitado em `ef9c814`, hardening/forwarding Minecraft em `3677f14`, logs setup em `dc9e805`, listas/spinners em `ba4fd57`, além do restante do PR #18 incorporado após o corte 2.2.0.
- Os manifests continuam declarando 2.2.0; CHANGELOG abre 2.3.0-dev. Não houve bump artificial de versão, assinatura ou publicação.
- Antes das edições foram lidos AGENTS.md, README, CHANGELOG, arquitetura/performance e configurações Cargo/workflows; cada especialista leu os guias do cliente correspondente.

Ferramentas: Rust/Cargo 1.98.0 Windows MSVC; target GNU Windows instalado; Python 3.11.9 Windows e 3.11.18 Linux portátil; JDKs 8/17/21/25; PowerShell; Git/GitHub CLI; caches Kotlin/Gradle. Ubuntu/AetherBuild WSL falharam por VHD ausente; a distribuição alternativa `salad-enterprise-linux` funcionou. Não havia Android SDK configurado. NSIS 3.11 e Actionlint 1.7.12 foram obtidos em diretórios de trabalho, com hashes registrados, sem instalação global.

## 4. Resultados dos oito agentes e coordenação

Houve delegação real, com no máximo três especialistas mais o coordenador. Foram usados agentes nativos do Codex com contexto limitado e arquivos exclusivos. OpenCode 2.2.5 estava instalado, mas `api GET /agent` retornou HTML; AIWeb falhou por módulo CLI ausente. Essas tentativas não foram apresentadas como delegação bem-sucedida.

| Escopo | Leitura/análise e resultado |
| --- | --- |
| A — Core/VPN | Session, peer/incoming, UDP, tunnel/runtime/scheduling/wake e testes; três defeitos de cancelamento corrigidos, quatro regressões RED/GREEN; session 21 e UDP 38 testes aprovados. |
| B — Desktop/UI | app/backend/virtual_list/graphics/main/shortcut e egui local; dois defeitos corrigidos; 5 testes de listas e 37 de UI passaram. IDs de widgets reais e menu offscreen conferidos. |
| C — Segurança | daemon/crypto/protocol/output/file_io/storage e chamadores; uma correção de identidade com RED/GREEN, storage 16, replacement 6, crypto 4 e protocol 7 testes aprovados. Riscos criptográficos separados de exploits demonstrados. |
| D — Windows/Linux | TAP, I/O, ACLs/pipes, setup/cleanup, NSIS e scripts; duas correções. Trinta testes Rust Windows selecionados e regressões PowerShell passaram. Coordenador completou execução Linux e compilação/fixture NSIS posteriormente. |
| E — Minecraft | Paper bridge/origins/forwarder/config/runtime/peer, Fabric core/UI/gerador; três correções com reproduções. Paper final e Fabric legacy 1.19.2 aprovados; limites de matriz/gameplay explícitos. |
| F — Android | EngineHost/VpnService/Stores/Repository/ViewModel/engine e helpers; quatro defeitos corrigidos. Especialista atingiu limite de uso após patches e harness de 143 testes. O agente C retomou o escopo independentemente, revisou código/logs, completou rollback de cache e repetiu validações. APK completo permanece pendente. |
| G — Python/API | Três módulos, APIs, workers, profiles e crypto; dois defeitos RED/GREEN corrigidos; 28+28 unit tests e 39+39 self-tests Linux passaram. |
| H — Qualidade/releases | CI, seleção/matrizes, scripts, empacotamento/assinatura, lockfile/OSV; dois defeitos de CI corrigidos e workflow tooling acrescentado. Especialista atingiu limite antes do relatório; coordenador assumiu, reproduziu ambos RED/GREEN e executou testes/Actionlint. |

Os relatórios detalhados `agent-a.md` a `agent-h.md`, logs, comandos e hashes acompanham o pacote de evidências. Falhas de agentes foram registradas e recuperadas; não foram transformadas em relatórios fictícios. O mapa cobre todos os principais componentes; não implica leitura linha a linha de todo arquivo nem prova de ausência de outros defeitos.

## 5. Segurança: vulnerabilidades e integridade confirmadas

Os achados C1, D1/D2, E2, F3 e G1 envolvem integridade/isolamento local, recursos ou correlação de operações. E2 permite atribuição incorreta da origem virtual a outra conexão loopback com a mesma porta; não demonstra que um peer VPN remoto escolha livremente outra identidade. C1 preserva um import/reset autorizado concorrente; não é quebra remota do vault. D2 recebe descritores do helper local autorizado, sem vetor remoto demonstrado. G1 exige reuso do ID de API autenticada após expiração; não contorna o token.

Não foi confirmado bypass remoto de ACL IPC, proof SH ou autenticação de pacote. Isso não equivale a certificação criptográfica. A exceção ARP documentada foi preservada e está listada como risco de compatibilidade/isolamento a decidir em conjunto entre clientes.

Consulta OSV de 461 pares Cargo identificou quatro avisos, não quatro exploits OpenRad: Tokio 1.53.1 tem [aviso de I/O safety](https://rustsec.org/advisories/RUSTSEC-2026-0386.html), mas não aparece nas árvores nativas Windows/Linux consultadas; o caminho `--target all` vem de wasm-bindgen-futures/winit. [zbus_names](https://rustsec.org/advisories/RUSTSEC-2026-0371.html), [zvariant](https://rustsec.org/advisories/RUSTSEC-2026-0370.html) e [zvariant_derive](https://rustsec.org/advisories/RUSTSEC-2026-0372.html) têm avisos de manutenção encerrada. Planejar migração coordenada; Cargo.lock foi preservado. Não foi feita varredura completa Maven/Gradle/PyPI ou dos artefatos finais.

## 6. Defeitos confirmados: evidência, causa e correção

Todos os IDs abaixo têm patch aplicado. “Corrigido” descreve a implementação e a validação informada, sem substituir qualificação nativa ausente.

| ID / gravidade | Arquivos e evidência verificável | Causa raiz e ação aplicada |
| --- | --- | --- |
| A1 — médio | `src/session.rs::Framed::receive`; `cancellation_rejects_a_record_already_in_the_read_ahead_buffer`: antes retornava registro com stop=true. | Registro completo evitava loops que checam stop. Checar cancelamento antes de consumir read-ahead; bytes preservados ao cancelar. |
| A2 — baixo | `src/session.rs::resolve_hostname_limited`; `dns_result_completed_during_cancellation_is_rejected`: RED recebia endpoint após cancelamento. | Resultado acordava recv_timeout sem revalidar stop/deadline. Revalidar ambos antes de devolver DNS. O connector já tinha barreira antes de discar, reduzindo impacto. |
| A3 — médio | `src/udp.rs::Enet`; testes `cancellation_rejects_a_record_already_in_the_ready_queue` e `cancellation_prevents_sending_a_new_record`: ambos RED. | Receive pronto evitava pump, send não consultava stop. Check comum em send/receive/pump; nenhuma emissão/consumo novo após cancelamento observado. |
| B1 — médio | `desktop/src/virtual_list.rs`; 50 mil linhas de altura 20.3125, scroll na linha 45 mil: 24 construções/frame e dirty permanente. | Diferença de offsets f32 acima de um milhão perdia mais que tolerância 0.05, reabrindo cálculo O(n) e discard. Acumular/comparar em f64, converter na fronteira egui. Teste assenta em ≤12 linhas sem scan completo. |
| B2 — baixo | `desktop/src/app.rs::apply_theme`; `switching_palettes_preserves_configured_typography_and_control_sizes`. | Light clonava Style padrão, Dark tinha métricas customizadas. Base comum de tipografia/spacing, paleta substituída; alternância Light/Dark/Light preserva dimensões. |
| C1 — médio | `desktop/src/storage.rs::Replacement::commit`; `replacement_retry_preserves_an_identity_changed_by_another_frontend`: RED sobrescrevia registro concorrente. | Lock protegia uma tentativa, não intervalo entre saves. Reler vault sob lock; aceitar identidade esperada ou replacement já persistido, rejeitar alteração/corrupção/remoção e manter pending. Retry idempotente testado. |
| D1 — médio | `packaging/windows/OpenRad-Setup.nsi`: `.onInit` tinha mutex, `un.onInit` não; NULL de CreateMutex era ignorado. | Setup/repair/uninstall podiam mutar mesmos arquivos/estado/registro simultaneamente. Macro compartilhada retém handle e falha em NULL/objeto existente. NSIS completo compila; fixture de macro prova sucesso, exclusão e NULL sem setup real. |
| D2 — baixo | `src/platform/linux.rs::receive_fd`; regressão com payload inválido, dois FDs e truncamento de 32 FDs. | recvmsg já instala SCM_RIGHTS antes dos ensure que rejeitavam mensagem. OwnedFd assume todos os recebidos antes de validar. Coordenador compilou função anterior exata + mesmo teste: RED com FD retido; função nova GREEN. Teste completo Linux também passou. |
| E1 — médio | Paper `bridge/VirtualTcpBridge.java`; teste responde quatro persist probes: RED retransmitia segmento de 100 bytes sob janela zero. | ACK de persist era contado como sinal de perda. Exigir peerWindow != 0 antes de contabilizar duplicate ACK/fast retransmit; preservar persist/backpressure legítimos. |
| E2 — médio | Paper `VirtualOrigins.java`, `VirtualTcpBridge.java`; `VirtualOriginsTest`: 127.0.0.2:49152 herdava origem registrada para 127.0.0.1:49152. | Chave era só porta, lookup aceitava qualquer loopback. Usar endereço+porta completos e remoção condicional pelo valor; isolamento IPv4 alternativo/IPv6 e duas origens cobertos. |
| E3 — alto | `clients/mod/fabric/build.gradle.kts`; compileJava real 1.19.2 falhou `cannot find symbol Tooltip` no Java gerado. | Adaptação legacy removia import mas buscava expressão antiga de duas linhas; código agora tinha uma linha. Atualizar substituição e input de cache. Build/test 1.19.2 e verificador Java17 passam. Outros targets do mesmo ramo ainda exigem matriz completa. |
| F1 — médio | Android `engine/Engine.kt`; `EngineLifecycleTest.terminalErrorIsPublishedOnlyAfterTheEngineStopsAcceptingWork`: RED via primeiro callback ERROR ainda com isRunning=true. | ERROR era publicado antes de halt; StateFlow podia conflar snapshot igual, watcher perdendo stopSelf. Parar admissão antes do primeiro ERROR publicado. |
| F2 — médio | Android `Engine.kt::submit/drainCommands`; `commandDuringShutdownFailsWithoutWaitingForAnotherControlLoop`: RED sem callback. | Última fase ONLINE permitia enfileirar após parada/dreno. running+fase e admissão/dreno usam gate; rejeição limpa senha e completa callback. |
| F3 — médio | Android `AndroidStores.kt`, `EngineRepository.kt`, `PreferenceTransactions.kt`; testes de falha, concorrência, cancelamento e cache alterado antes de commit(false)/throw. | apply não informava falha; flows/sucesso precediam persistência; RMW concorrente perdia alterações. Serializar transformação/save/publicação, commit em IO, aplicar config só após sucesso e restaurar raw anterior no cache em falha. Rollback não declara disco durável quando o próprio disco recusa gravação. Mensagem específica traduzida em 4 idiomas. |
| F4 — médio | Android `EngineRepository.kt`, `CommandGate.kt`; cancelAndJoin antigo não terminava em 1 s enquanto resposta era retida. | Busca NonCancellable era filha do caller de collectLatest; nova query não invalidava geração até resposta/deadline (~20 s). Owner no scope do repositório mantém gate da operação admitida, caller cancela prontamente; waiters cancelados/stale não são enviados; loadMore compartilha gate. |
| G1 — médio | `clients/python/openrad.py::UDPServer`; novo teste expira ID2 por 91 s, reutiliza e entrega callback antigo: RED result=old em ambas as ordens de fila. | Callback/cache usavam só endereço+request_id. Geração opaca acompanha resposta enfileirada e é conferida ao publicar; dedup e cache da operação nova preservados. |
| G2 — médio | Python `LocalClient.receive`; timeout=.1 após 50 s ocioso consumia 3.0 s no relógio controlado. | Refresh status tinha timeout fixo externo ao orçamento. Verificar prazo antes e passar min(3, restante); testes com/sem reply respeitam prazo total. |
| H1 — médio | Workflow `minecraft-version-compat.yml`; teste novo RED no YAML anterior, GREEN no atual. | Filtros não incluíam 2.3.0-dev. Habilitar push/PR dessa branch mantendo path filters e execução manual. |
| H2 — médio | `scripts/ci-changed-components.py`; repo temporário base→Fabric inválido→README: RED pulava Fabric. | Diff apenas do evento assumia validação anterior bem-sucedida. PR usa diff cumulativo da base; push continua incremental. Mesma regressão GREEN, sem inferir sucesso de jobs anteriores. |

## 7. Suspeitas, contratos e riscos que ainda precisam de validação

| Gravidade estimada | Arquivos/evidência e causa | Próxima ação; status |
| --- | --- | --- |
| Médio | `src/tunnel.rs`, Python `radvpn.py`/AGENTS: exceção de origem ARP é contrato explícito; Rust permite caso gratuitous e Python replies mais amplos. | Definir política L2 entre clientes e testar interoperabilidade antes de restringir. Não removido como se fosse regressão. Sem exploit remoto novo demonstrado. |
| Médio | `src/session.rs`: TCP write usa timeout por syscall, sem orçamento agregado de toda emissão. | Injetar backpressure contínuo e medir latência Stop/join; não reproduzido travamento. Não liberar buffers ou cortar protocolo especulativamente. |
| Médio | `desktop/src/shortcut.rs`, onboarding em app: Command::output sem deadline no thread UI. | Mover helper/resultado para backend com cancelamento e testar Desktop redirecionado/COM lento. Travamento não reproduzido nesta rodada. |
| Médio | `src/crypto.rs`: ShServer/Identity/temporários BigUint não oferecem zeroização consistente; Channel aceita plaintext junto do teto cujo ciphertext excede teto de decrypt. | Revisão de tipos/limites com vetores e caminhos reais; não houve extração de segredo nem mensagem real de 4 MiB reproduzida. Não contar como falha de autenticação confirmada. |
| Baixo | `daemon.rs::atomic_save`, `file_io.rs::atomic_write`: sync do arquivo sem fsync explícito do diretório Unix. | Validar durabilidade de rename em crash/power-loss e alinhar contrato; perda de energia não simulada. |
| Médio | `backend::commit_shutdown_replacement`: pré-checagem anterior pode rejeitar replacement já persistido após erro ambíguo antes do novo commit idempotente. | Reproduzir callback/vault ambíguo no caminho shutdown e alinhar sem sobrescrever identidade concorrente. Não confirmado nesse caminho. |
| Médio | Paper `pendingSynCapExcludesEstablishedFlowsAndTimeoutReleasesItsSlot`: uma falha em await ESTABLISHED com handshake de 400 ms; isolado e rerun completo passam sem alteração. | Repetir sob carga controlada e distinguir deadline frágil de defeito de lifecycle. Carga é hipótese, não causa comprovada. Log/ XML da falha preservados. |
| Médio | Android: harness não compila integração AndroidStores/EngineRepository com APIs/AGP reais; coroutines 1.6.4 no harness versus 1.8.1 no projeto. | Executar Gradle/SDK e dispositivo; verificar commit/cache, callbacks e lifecycle reais. Nenhuma certificação de APK pelo teste JVM. |
| Baixo | Dependências/Actions: avisos de manutenção e major tags; OSV não cobre todo ecossistema. | Planejar migração keyring/zbus, pinning de Actions e SBOM/scan de artefatos. Sem atualização massiva não validada nesta auditoria. |

## 8. Desempenho: análise e medições

**Benchmark reproduzido de VirtualRows:** fonte exata do HEAD anterior ao patch versus fonte corrigida, mesmo harness otimizado com rustc -O, mesmas bibliotecas egui/eframe de dependência debug; 5 rodadas alternadas por versão, 200 frames medidos depois de aquecimento, 50 mil linhas fracionárias, viewport 300 e scroll na linha 45 mil. Medianas: **411,168 ms antes → 32,139 ms depois para 200 frames** (aproximadamente 12,8× neste caso sintético). Construções: 4.800 → 2.200; chamadas kind: 19.995.200 → 2.200 em 200 frames. Scripts/fontes/hashes e amostras estão no pacote (`benchmark_virtual_rows.py`, `virtual-rows-benchmark-results.json`).

É tempo do harness headless, não CPU/RSS de processo ocioso, GPU, FPS nativo, throughput VPN ou ganho global do produto. Dependências debug limitam generalização. O custo de offsets cresce cerca de 4 bytes por item (aproximadamente 200 KB extras com 50 mil linhas). Coordenadas finais egui continuam f32.

Contra v2.2.0, o delta introduziu VirtualRows para as listas longas/peers e throttle de spinner; v2.2.0 ainda construía linhas desses caminhos por iteração, embora alguns seletores já usassem show_rows. Não foi feito benchmark temporal comparável do aplicativo inteiro v2.2.0; o benchmark acima isola a regressão do código recente ba4fd57 e sua correção.

Custos residuais por inspeção: PeerListCache e NetworkListCache ainda comparam metadados O(n) em refresh; keep procura chave linearmente quando existe menu offscreen; dirty/revisão/layout legítimos reconstroem offsets O(n); Plan aloca vetores de linhas visíveis. Não se afirma frame inteiro O(visible). Spinners pendentes mantêm repaint ~33 ms; UI ainda acorda periodicamente e backend faz polling de status. Filas principais limitadas e roteamento por índices foram preservados. Não foi identificado novo O(n²) reproduzido nesta rodada; locks/serialização/RSS em carga real continuam sem medição.

## 9. Correções aplicadas e preservação de comportamento

Patches localizados nos arquivos do inventário final. Além dos 19 IDs: README Paper sem afirmação antiga contraditória sobre loopback; testes de widget ID reais; novo workflow tooling; regressões de mutex que extraem a macro de produção; traduções Android da falha de gravação.

Não foram alterados wire headers, fixtures, chaves reais, identidade real, Cargo.lock ou protocolos. Nenhum driver foi instalado/removido e nenhuma VPN/adaptador real foi parado/reconfigurado. Sem push, merge, reset, commit ou release. Testes existentes não foram desabilitados; a fixture de comando Android passou a marcar running=true para representar corretamente uma engine online sob o novo contrato. O teste Paper foi ampliado para responder persist probes reais, sem esconder a falha transitória de outra função.

## 10. Testes executados e resultados

Os comandos completos, cwd, tempos/exit codes e logs estão em `checks/`; os testes de agentes com detalhes adicionais estão nos oito relatórios e no diretório `android-harness/`. Contagens por plataforma se sobrepõem e não devem ser somadas como testes únicos.

| Validação | Resultado e alcance |
| --- | --- |
| `cargo fmt --all -- --check` | PASS inicial e final. |
| `cargo test --workspace --locked` Windows MSVC | PASS final: **361 aprovados, 0 falhas, 1 ignorado**, incluindo 85 desktop; headless, sem TAP real. Baseline também PASS. |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | PASS Windows MSVC. Clippy adicional `-p openrad-client --all-targets --target x86_64-unknown-linux-gnu --offline --locked -- -D warnings` também PASS (core/CLI/tests Linux, sem desktop). |
| Core Rust Linux, `cargo test -p openrad-client --target x86_64-unknown-linux-gnu --offline --locked --no-run` | PASS crossbuild fresco das fontes atuais; sysroot/vendor anteriores reutilizados somente como ferramentas/dependências. Artefatos novos têm hashes registrados. |
| Execução dos novos binários Linux | PASS: **294 aprovados, 0 falhas, 3 ignorados**, UID 65534, WSL real; inclui CLI/daemon/transports/reference/localization e nova regressão SCM_RIGHTS. GUI não faz parte desse core build. |
| Repro isolada Linux FD | RED na função receive_fd exata do HEAD anterior; GREEN na nova, mesmo teste e libs. Não executa helper privilegiado. |
| Python Linux `run_tests.py` | PASS **28 normal + 28 -S**, com fonte final fresca. |
| Python `openrad.py self-test` normal e `-S` | PASS **39 + 39**, loopback/identidades sintéticas; py_compile PASS. |
| Paper `gradlew.bat --no-daemon check` | PASS final: **175 testes reportados, 0 falhas, 1 skipped**; verifyJar 121 classes Java21. Primeira rodada após patch falhou em outro teste de timing, preservada e investigada como risco. |
| Fabric 1.21.1 JDK21 | PASS `:core:test :fabric:build :fabric:test`, 11 tarefas, antes do patch exclusivamente legacy. |
| Fabric 1.19.2 | RED compile antes, PASS build/test depois, **25 testes**, JAR/bytecode Java17 verificados. |
| Fabric moderno 26.3 JDK25 | Build PASS; testes **NO-SOURCE**, não aprovados como gameplay/testes de cliente. |
| Android Gradle JDK17 `:app:testDebugUnitTest` | **FALHOU** por SDK ausente; retry do revisor também preservado. Não equivale a falha funcional comprovada do APK, nem a aprovação. |
| Android harness JVM | Suíte ampla fresca após rollback: **145 testes reportados, exit 0**, incluindo **1 assumption-skipped de emulador** (144 corpos efetivamente exercitados); 12 testes afetados também PASS. Fontes atuais engine/helpers, fixtures e testes selecionados; não compila todos os chamadores Android. |
| `python scripts/check-sync.py` | PASS final; fixtures/locales e 41 classes Java espelhadas. Android 188 strings em cada idioma, placeholders consistentes. |
| Tooling Minecraft unittest | PASS final **32 testes**, incluindo seleção cumulativa e branch. Repro CI baseline 2 FAIL esperados, atual 2 PASS. |
| Empacotamento/assinatura | História de pacotes 2 PASS; Properties Java 1 PASS; colisões de assinatura 8 casos PASS; verificação de JAR dos targets construídos. Sem chave real. |
| PowerShell plataforma | Setup 11 casos sintéticos; recovery 43; delayed named-pipe PASS; Rust Windows selecionados 30 PASS (subconjunto do workspace). |
| NSIS 3.11 | Script completo compilado /WX com payload sintético; macro real em fixture sem elevação: 3 casos PASS. Nenhum instalador real executado. |
| Actionlint 1.7.12 | PASS para os 3 workflows alterados/novo e para todos os workflows; Shellcheck indisponível/desabilitado. |
| Benchmark UI | 10 execuções alternadas PASS; tempos/amostras e primeiro erro de link de harness preservados. |

Tentativas sem validação também foram retidas: descoberta unittest em scripts encontrou zero testes devido aos nomes com hífen; execução Python nativa falhou por fcntl; duas chamadas Fabric iniciais tiveram argumento -P fracionado pelo shell e foram repetidas corretamente; compilação inicial do benchmark faltou caminho de libs Windows e foi corrigida. Não são passes, nem defeitos de produto derivados artificialmente do ambiente.

## 11. Limitações dos testes

Não executados: TAP Linux privilegiado ignorado; driver/TAP/UAC/install/uninstall reais Windows; renderer Vulkan/screenshot Linux; desktop/CLI release packages frescos multiplataforma; APK Android/AGP completo, emulador/dispositivo/bateria/protect e lifecycle do SO; todos os targets Minecraft declarados e seus mixins/NMS em runtime; gameplay multiplayer; rede pública/coordenador real/Radmin oficial; NATs reais, testes longos de falha parcial, pressão persistente de disco/processo e throughput real. Os testes padrão e fixtures não usam credenciais do usuário.

Os binários core Linux são novos, cross-built e executados em kernel Linux WSL, com runtime/sysroot de proveniência registrado. Não se apresenta isso como build nativo completo Debian12 da GUI ou qualificação dos pacotes. Não foi rodado cargo-audit instalado; a consulta OSV não é substituto universal de SCA.

Cobertura dos cenários solicitados: cancelamento e resposta atrasada têm regressões novas TCP/UDP/DNS/API/search; troca de identidade e gravação recusada têm vault/transações sintéticos; perda/duplicação/reordenação/fragmentação têm testes existentes de transportes e TCP virtual, ampliados para janela zero. Fechamento/retry de replacement e concorrência de perfil são cobertos pelos testes desktop/CLI. Reinício do daemon, conectar/desconectar rapidamente, falha de DNS e autenticação interrompida foram analisados com os testes headless disponíveis e leitura de fluxo; não foram repetidos como ensaios de longa duração com VPN/driver/servidor reais. Falha de disco Android continua injetada, não uma falha de filesystem em dispositivo.

## 12. Pendências

1. Executar Gradle/SDK Android nas dependências declaradas, conferir chamadas AndroidStores/EngineRepository e APK em dispositivo; repetir cenários de disco negado, search cancelado e service restart.
2. Executar matriz Minecraft completa após fix legacy e CI alterado; repetir falha de timing Paper sob carga controlada; validar forwarding real e Force Relay em gameplay.
3. Qualificar Windows TAP/UAC e Linux GUI/rasterização em ambientes dedicados, com autorização para a rede de teste.
4. Investigar os riscos da seção 7: atalho síncrono, orçamento TCP write, zeroização/limites Channel, fsync de diretório, política ARP entre clientes e shutdown replacement ambíguo.
5. SBOM e varredura de dependências/artefatos de todos os ecossistemas; tratar manutenção zbus e dependências de targets novos sem atualização indiscriminada.
6. Promover versões/notas/inventário da 2.3.0 somente após qualificação; gerar e verificar binários/assinaturas/checksums com proveniência verdadeira.

## 13. Riscos que impedem declarar a release estável

O Android modificado ainda não tem build completo contra SDK/AGP e coroutines declarados; Minecraft só tem targets selecionados construídos e uma falha de timing ainda sem causa definitiva; os caminhos privilegiados de instalação/data plane não foram executados; GUI Linux e artefatos finais não foram produzidos/qualificados. Versões e documentação de release continuam em 2.2.0/Unreleased. Esses fatos impedem uma declaração responsável de 2.3.0 estável, mesmo com os patches e suítes locais aprovados.

## 14. Recomendações priorizadas

**Antes de release:** revisar/integrar os patches locais, obter passes da nova CI no SHA efetivamente integrado, executar Android completo e matriz Minecraft, qualificar data plane e GUI nas plataformas de suporte anunciado e resolver/reproduzir o timing Paper. Nenhuma dessas verificações foi tratada como passe presumido.

**Próxima manutenção:** unificar os contratos ARP e de recuperação de identidade, medir latência de Stop sob backpressure, retirar criação de atalho do thread UI e revisar limites/zeroização/durabilidade de arquivos.

**Otimização depois da correção:** medir CPU/RSS idle e sob milhares de peers, refresh O(n) dos caches, serialização de snapshots e alocações de Plan. Manter o benchmark fracionário e as regressões de cancelamento/persistência como barreiras contra retorno dos defeitos.

## Adendo de fechamento e inventário

O adendo a seguir é gerado após a revisão final para registrar o resultado mais recente dos testes Android/Clippy Linux, arquivos alterados e integridade dos entregáveis. Os relatórios individuais preservam a ordem real das evidências; o consolidado prevalece quando o coordenador completou uma validação inicialmente pendente.

<!-- final-generated-inventory -->

### Resultado final registrado

- **19 defeitos confirmados; 19 patches aplicados**, sem alteração de HEAD, índice ou trabalho preexistente.
- Clippy Linux core/all-targets: PASS, warnings negados; não inclui GUI.
- Android pós-rollback: recompilação fresca, JUnit **OK (145 tests)**, 30,709 s, exit 0; teste opt-in de emulador **assumption-skipped**. Outros 12 testes afetados passaram isoladamente. A integração Android/Gradle permanece pendente por SDK ausente.
- Formatação final, sincronização de fixtures/locales/41 classes Java, 32 testes tooling e Actionlint completo: PASS.
- Rerun remoto de CI e qualificação release não executados; nenhuma publicação autorizada ou realizada.
- Revisão final incluiu diffs tracked e todos os novos arquivos; nenhum arquivo de funcionalidade removido, fixture ou credencial real alterada. Testes não foram desabilitados. Os novos testes e o relatório fazem parte do patch entregue.

### Inventário completo desta auditoria

36 arquivos modificados/novos (incluindo este relatório); hashes finais no manifesto do pacote de evidências.

| Estado | Arquivo |
| --- | --- |
| Modificado | `.github/workflows/minecraft-2.2.0-integration.yml` |
| Modificado | `.github/workflows/minecraft-version-compat.yml` |
| Novo | `.github/workflows/tooling.yml` |
| Modificado | `clients/android/app/src/main/java/com/openrad/mobile/data/AndroidStores.kt` |
| Novo | `clients/android/app/src/main/java/com/openrad/mobile/data/CommandGate.kt` |
| Modificado | `clients/android/app/src/main/java/com/openrad/mobile/data/EngineRepository.kt` |
| Novo | `clients/android/app/src/main/java/com/openrad/mobile/data/PreferenceTransactions.kt` |
| Modificado | `clients/android/app/src/main/java/com/openrad/mobile/engine/Engine.kt` |
| Modificado | `clients/android/app/src/main/res/values-pt/strings.xml` |
| Modificado | `clients/android/app/src/main/res/values-ru/strings.xml` |
| Modificado | `clients/android/app/src/main/res/values-vi/strings.xml` |
| Modificado | `clients/android/app/src/main/res/values/strings.xml` |
| Novo | `clients/android/app/src/test/java/com/openrad/mobile/data/CommandGateTest.kt` |
| Novo | `clients/android/app/src/test/java/com/openrad/mobile/data/PreferenceTransactionsTest.kt` |
| Modificado | `clients/android/app/src/test/java/com/openrad/mobile/engine/EngineCommandFailureTest.kt` |
| Novo | `clients/android/app/src/test/java/com/openrad/mobile/engine/EngineLifecycleTest.kt` |
| Modificado | `clients/mod/fabric/build.gradle.kts` |
| Modificado | `clients/plugin/README.md` |
| Modificado | `clients/plugin/src/main/java/io/github/gringoestrangeiro/openradplugin/bridge/VirtualOrigins.java` |
| Modificado | `clients/plugin/src/main/java/io/github/gringoestrangeiro/openradplugin/bridge/VirtualTcpBridge.java` |
| Novo | `clients/plugin/src/test/java/io/github/gringoestrangeiro/openradplugin/bridge/VirtualOriginsTest.java` |
| Modificado | `clients/plugin/src/test/java/io/github/gringoestrangeiro/openradplugin/bridge/VirtualTcpBridgeTest.java` |
| Modificado | `clients/python/openrad.py` |
| Modificado | `clients/python/tests/test_incoming.py` |
| Modificado | `desktop/src/app.rs` |
| Modificado | `desktop/src/storage.rs` |
| Modificado | `desktop/src/virtual_list.rs` |
| Novo | `docs/audits/openrad-2.3.0-deep-audit.md` |
| Modificado | `packaging/windows/OpenRad-Setup.nsi` |
| Modificado | `scripts/ci-changed-components.py` |
| Novo | `scripts/test-windows-setup-mutex.py` |
| Modificado | `src/platform/linux.rs` |
| Modificado | `src/session.rs` |
| Modificado | `src/udp.rs` |
| Modificado | `tests/test_minecraft_ci_matrix.py` |
| Modificado | `tests/test_minecraft_ci_scope.py` |

### Entregáveis e evidências

Cópia do relatório: `outputs/openrad-2.3.0-deep-audit.md`. Patch completo: `outputs/openrad-2.3.0-audit.patch`. Pacote `outputs/openrad-2.3.0-audit-evidence.zip`: oito relatórios de escopo, logs/JSON de comandos, evidências RED/GREEN, XML de falhas preservadas, hashes/fontes de benchmark e scripts de reprodução. Esse diretório outputs pertence ao chat de auditoria, fora do checkout; o relatório solicitado permanece também em docs/audits no repositório.

Os paths Windows/Linux presentes nos logs refletem o ambiente real, sem tokens, contas VPN ou chaves de produção. Ferramentas/runtime em cache são dependências externas de reprodução, não binários release certificados. O pacote não inclui instalador, driver ou APK para uso.

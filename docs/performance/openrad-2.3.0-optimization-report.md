# OpenRad 2.3.0 — otimização de performance (relatório parcial verificável)

Data: 5 de novembro de 2026. Base pública de referência: commit `ba4fd57` da branch `2.3.0-dev`.
Este documento não é um atestado de release nem uma auditoria final dos oito escopos.

## Preservação e método

- O "antes" medido é `ba4fd57` com 19 correções locais da auditoria anterior, ainda não publicadas naquele momento; elas entram neste pull request junto com as otimizações. Por isso, refazer o "antes" a partir de `ba4fd57` sozinho não reproduz exatamente os números abaixo. O snapshot local usado (`work/optimization/baseline-source.zip`, inventário `baseline-state.json`) não é público.
- Não houve commit, push, merge, release, reset destrutivo ou alteração em VPN, adaptador, perfil ou credenciais reais.
- O host de medição registrado era um Intel i7-11370H (4C/8T), ~16 GB RAM, Windows 11. Benchmark Rust com `rustc -O` e dependências debug já existentes; não substitui medição de build Release completo.
- Os dados brutos de medição estão em `work/optimization/`, fora do repositório: `a/`, `b/`, `c/final/` e `g/`. O diretório `work` pertence à pasta de evidências da sessão Codex de 2026-11-05.

## Resultado por escopo

| Escopo | Resultado nesta etapa | Estado |
| --- | --- | --- |
| A — dataplane Rust | WSASend com buffers vetorizados eliminou uma alocação por datagrama, mas prejudicou cenários de cauda/MTU; experimento rejeitado. | Sem patch de produção |
| B — estado e IPC do daemon | Serialização direta da resposta de STATUS, com um snapshot Arc capturado sob lock e serializado após soltar o lock. Evita árvore `serde_json::Value` temporária. | Implementado e testado em `src/daemon.rs` |
| C — Desktop | Revisões de snapshots para evitar refresh O(n) nos frames inalterados; métricas de conexão mais preguiçosas; índice de linha retida em VirtualRows. | Implementado pelo trabalho anterior; benchmark concluído e 87 testes do harness aprovados |
| D — Windows/Linux I/O | Além da alteração de status em B, ainda falta investigação dedicada dos drivers e IPC/latência. | Pendente |
| E — Android | SDK/AGP completo e benchmarks de energia, TUN, CPU e lifecycle não executados nesta rodada. | Pendente |
| F — Minecraft | Benchmark Paper/Fabric em runtime e matriz de versões completa não executados nesta rodada. | Pendente |
| G — Python/API | Limite inferior do prazo de expiração do cache UDP para evitar varrer milhares de registros a cada poll; TTL e deduplicação preservados. | Implementado pelo trabalho anterior e validado nos testes Python |
| H — qualidade | Comparações e testes Rust/format/sync no Windows; integração multiplataforma e CI remoto ainda pendentes. | Parcial |

## B — medição independente do status do daemon

Cinco pares baseline/candidato em processos alternados (AB/BA), 40 operações medidas por caso e por processo. Valores abaixo são medianas dos p50 de cada rodada, com tempo de serialização local; não incluem transporte IPC do SO ou latência VPN.

| Peers sintéticos | Antes (p50) | Depois (p50) | Aceleração |
| ---: | ---: | ---: | ---: |
| 0 | 6,2 µs | 1,6 µs | 3,88× |
| 10 | 35,4 µs | 8,4 µs | 4,21× |
| 100 | 373,7 µs | 68,5 µs | 5,46× |
| 1.000 | 3,627 ms | 1,016 ms | 3,57× |
| 5.000 | 18,807 ms | 3,989 ms | 4,71× |
| 10.000 | 38,015 ms | 8,546 ms | 4,45× |

Com 10 mil peers, o benchmark contabilizou aproximadamente 231.716 alocações por status antes e 17 depois; bytes temporários e alocações foram medidos separadamente do tempo. A comparação verifica o JSON por valor, aceitando diferenças de ordem de chaves. Um teste de regressão acrescentado a `src/daemon.rs` compara a resposta direta e a API pública nas fases inicial, conectada com peer Unicode e reconexão.

Arquivos brutos: `work/optimization/b/run-baseline/bench-{0..4}.jsonl`, `work/optimization/b/run-candidate/bench-{0..4}.jsonl` e `work/optimization/b/comparison-summary.json`. A fonte original de `daemon.rs` foi preservada em `work/optimization/b/production-before.rs`.

## C — benchmark do desktop

Os benchmarks `work/optimization/c/final/results.json` são cinco pares alternados headless com egui, sem janela GPU. Aos 10 mil peers, frame estável (mediana p50): 3,092 ms antes, 1,446 ms depois (~53% menor). Refresh de cache sem mudanças passou de trabalho O(peers) a caminho de revisão constante nos casos medidos. Não há alegação de ganho de FPS/GPU global.

Limitações importantes: com 1.000 peers, o frame estável mediano aumentou de 1,429 ms para 1,573 ms (~10% pior); scroll e layout também apresentaram regressões pequenas em certos casos. O benefício não é uniforme. O harness candidato concluiu 87 testes aprovados e 2 testes marcados como ignorados; na suíte de workspace Rust final o desktop executou 87 testes aprovados.

## G — benchmark Python da API local

A comparação confirmatória com cache preenchido por 3.000 entradas reduziu STATUS p50 de 647,5 µs para 141,0 µs; SEND_UDP com 100 destinos de 1.049,7 µs para 535,0 µs. Cinco pares alternados, cenário autenticado local, filas sintéticas; não representa throughput VPN criptografado. As medidas de memória do harness original apresentavam viés de GC e não foram interpretadas como economia de RSS.

Testes registrados pelo agente Python: 29+29 em modos normal e `-S`, self-test 39+39. Transporte TCP autenticado sob taxa fixa não mostrou ganho sustentado; nenhuma melhoria de throughput, RAM ou CPU em repouso foi reivindicada.

## Validações deste prosseguimento

- `cargo fmt --all`: passou.
- `cargo test --locked -p openrad-client direct_status_payload_preserves_the_public_json_contract`: 1 passou.
- `cargo test --workspace --locked -j 2`: **366 testes passaram, 0 falharam, 1 ignorado**; inclui 87 testes do desktop.
- `cargo clippy --workspace --all-targets --locked -- -D warnings`: passou.
- `python scripts/check-sync.py`: passou (fixtures, locale e 41 classes compartilhadas Minecraft).
- `git diff --check`: passou.
- Os logs estão em `work/optimization/checks/full-rust-after-b.log` e `clippy-after-b.log`.

## Próximas tarefas e riscos de release

1. Concluir D com perfilamento Windows/Linux e validação de IPC com tráfego real, sem mexer em drivers de produção.
2. Concluir E com Gradle/Android SDK, testes em emulador/dispositivo e energia; Android ainda não está qualificado.
3. Concluir F com benchmark Paper/Fabric e matriz de versões; registrar testes sem fonte e falhas intermitentes.
4. Concluir H com revisão cruzada dos benchmarks, cenários de idle/RSS e integração de CI no SHA exato das alterações.
5. Medir VPN real, CPU/RSS idle e GPU/frametime nativo; sem essas medições não declarar a otimização total concluída.
6. Repassar as pequenas regressões da UI em datasets pequenos antes de decidir manter ou refinar mudanças.

Todos os patches estão locais. A versão 2.3.0 continua sem aprovação para release, e este relatório permanece parcial até os oito escopos e os testes de plataforma serem fechados.

## Qualificação local antes do pull request (5 de novembro de 2026)

- Paper Java 21: `clients/plugin/gradlew.bat --no-daemon --max-workers=2 clean check` aprovado. A etapa `verifyJar` confirmou 121 classes, metadados e licença.
- Fabric 1.21.1 / JDK 21: `:core:test :fabric:build :fabric:test` aprovado.
- Fabric 26.3 / JDK 25: `build` aprovado; não há testes nesse target (`NO-SOURCE`).
- Testes de inventário/seleção de CI Minecraft: 32 aprovados; histórico de empacotamento: 2; parser de propriedades de assinatura Android: 1; `check-sync.py` aprovado.
- Windows sintético: setup do adaptador (11 casos) aprovado; caminhos de assinatura Android (8 casos) aprovados.
- Test-RadminRecovery.ps1 não concluiu neste runner: `Process.Start` de um subprocesso PowerShell retornou Win32 `Access denied` na linha 346. Isto é impedimento do ambiente de execução, e não foi considerado aprovação nem falha de comportamento do OpenRad. Requer repetição em GitHub Actions Windows.
- O Android SDK completo não estava disponível no Windows local. Gradle/assemble APK e teste instrumentado em emulador permanecem sem validação local; workflows de CI do projeto podem validar em runners dedicados.
- GUI Linux/Vulkan, TAP com privilégios, teste real de driver/UAC Windows, interoperabilidade com o serviço Radmin real e toda a matriz Minecraft não foram executados localmente. PR de revisão não equivale a qualificação final da release.
- Git e segurança: validação de whitespace e varredura de formatos comuns de chaves nos novos trechos passaram; nenhuma credencial de desenvolvimento foi intencionalmente incluída.

Este adendo documenta verificações executadas localmente antes da publicação do PR; os resultados dos workflows do GitHub pertencem ao commit que cada execução testar e devem ser consultados separadamente. Não há afirmação de "zero bugs", throughput VPN real superior ou release pronta.
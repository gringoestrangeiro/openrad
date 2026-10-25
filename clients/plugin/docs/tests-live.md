# Relatório de validação — OpenRad Plugin 0.2.4-dev (alpha)

Primeira integração live: **20/10/2026**, horário de São Paulo. Repetição do pipeline com o JAR final, build e validação: **21/10/2026**. O status permanece desenvolvimento; o critério de aceitação integral do pedido NÃO está concluído.

## Resumo verificável

| Verificação | Resultado e alcance |
| --- | --- |
| Baseline preservado | Projeto original sem Git; commit inicial `f637d80` preserva 28 arquivos. Os 9 testes iniciais passaram. Checkout Rust não alterado. |
| Java 21 | `gradlew.bat clean check`: 101 testes, 0 falhas, 0 erros, 0 ignorados. |
| Java 25 | Build limpo e mesmos 101 testes executados separadamente; 0 falhas/erros/ignorados. Paper, comandos, reload, shutdown e status/login criptografado também executados. Alvo continua bytecode 21. |
| Análise estática | Compilação com `-Xlint:all`; verificação de artefato; checagem de whitespace Git. Não equivale a auditoria formal. |
| JAR | 108 classes em bytecode 65, metadados Paper e licença. Sem binários nativos, classes de dependências externas ou runtime downloads. |
| Dependências runtime | Configuração Gradle `runtimeClasspath`: nenhuma. Paper API é fornecida pelo servidor; JUnit só em teste. |
| Paper real | Paper 1.21.1 build 133 em Windows. Boot/comandos/reload/shutdown em Java 21 e 25 no build anterior; compatibilidade LAN do 0.2.1-dev executada em Java 21 com LANBroadcaster 2.4.0 real. |
| Criptografia | Fixtures sintéticas canônicas de CBC/rekey, SRP cliente/servidor e verifier; RSA independente; arquivos privados Windows. |
| Controle sintético | RSA→SRP→LoginComplete→Registered→persistência→attachment→rede privada→heartbeat, com serviço local real de teste. |
| Peers sintéticos | Coordenador propósito5, hub de ofertas, negociação e Ethernet cifrada em TCP, UDP e relay entre sockets locais. |
| Interop Rust real | Java↔Rust canônico nas duas direções, TCP e UDP:50 frames de 1514 bytes por direção em cada uma das 4 combinações. |
| TCP virtual | 18 casos de integração: transferências acima de 2 MiB, perda, reordenação, duplicatas, wrap, ACK parcial, janelas pequenas/zero, persist, FIN/RST, quotas, backend parado, deadlines, revogação. |
| Runtime | 9 casos com controle/peer/backend locais reais: fluxo íntegro, continuidade de peer saudável, ACL/papel na mesma rede, reconnect com novo VIP, cancelamento e geração obsoleta. |
| Entradas inválidas | Codecs e firewall; 10.000 frames malformados determinísticos, incluindo cabeçalhos com checksum correto. |
| Registro real | Identidades novas e isoladas, IPs virtuais atribuídos, identidade persistida e reutilizada, attachment e heartbeat op17 confirmados. |
| Rede privada real | Criação, SRP de senha, entrada/descoberta, peer mútuo em direto TCP, Ethernet bidirecional, leave e delete confirmados. |
| Pipeline real integrado | Serviço público→`VpnRuntime`→peer DIRECT_UDP autenticado→Ethernet→TCP virtual→Paper real: status e pedido de criptografia de online-mode recebidos. |
| Catálogo Minecraft | Seletores por idioma/número, Unicode, deduplicação, limites e paginação correlacionada. Redes recusadas não derrubam redes explícitas; cursor repetido falha sem autorizar nomes inventados. |
| Descoberta LAN | Captura multicast local real, parser/checksums/MTU, compartilhamento UDP, expiração, fechamento/reabertura e runtime com peer autenticado/revogação/ACL. Paper + LANBroadcaster 2.4.0 real → túnel DIRECT_TCP → anúncio com VIP e porta virtual corretos. |
| Gameplay oficial | **NÃO EXECUTADO**: login Microsoft/Mojang completo, mundo/chunks/movimentação/chat/múltiplos jogadores com Radmin oficial. |

Contagens de casos são as dos XML JUnit finais, incluindo casos parametrizados. Relatórios de teste são incluídos no pacote de entrega. O teste de fallback cobre falha real de descoberta de candidatos diretos seguida de relay sintético autenticado, sem ignorar o caso.

## Evidência do pipeline integrado live

A execução preservada em `live-runtime-minecraft-evidence.txt` confirmou:

```text
LIVE_RUNTIME_AUTHENTICATED_PEER_OK path=DIRECT_UDP phase=CONNECTED
LIVE_RUNTIME_PAPER_STATUS_OK bytes=166
LIVE_RUNTIME_PAPER_ONLINE_ENCRYPTION_REQUEST_OK bytes=172
LIVE_RUNTIME_BRIDGE_METRICS Snapshot[connections=2, pendingFrames=0, bufferedBytes=131072, acceptedFrames=10, rejectedFrames=0, resets=0, retransmissions=1, backendFailures=0]
LIVE_RUNTIME_MINECRAFT_PIPELINE_OK
LIVE_RUNTIME_STOPPED true
LIVE_RUNTIME_TEST_PEER_LEFT_OK
LIVE_RUNTIME_TEST_NETWORK_DELETED_OK
```

Foi usado um peer Java controlado com duas identidades descartáveis próprias no mesmo host, conectadas ao serviço real, e um Paper local com `online-mode=true`. O teste não respondeu ao desafio criptográfico de login com uma conta Microsoft e não entrou no mundo. Receber esse desafio comprova a chegada até a fase de login criptografado, não a autenticação de jogador.

O JAR imutável usado nesse teste live em 20/10 foi o snapshot de 224702 bytes, SHA256 `55e5691fda2b8b001a4d373d9bd19f885b855c36f8d5c54f354150b9e64afe3e`. Ele é uma evidência de integração anterior ao acabamento final, não o checksum automático do JAR entregue. O checksum final está no arquivo `SHA256SUMS.txt`. Alterações posteriores incluem limites/persist, fallback de bind, formatação e testes de regressão; cada nova build foi validada localmente.

## Repetição com o JAR final em 21/10

SHA256 do JAR final: `2d8db87cde122966995d0bdaebf8e64e32e332fd5563565f5e69a11acd7b7a61`.

O mesmo pipeline foi repetido em Java 21 usando uma cópia imutável desse JAR: canal autenticado **DIRECT_TCP**, status de 166 bytes e pedido de criptografia de 172 bytes, 10 frames admitidos, 0 rejeitados, 0 resets, 0 retransmissões e 0 falhas de backend. O runtime encerrou, o peer saiu e a rede temporária foi apagada. O EOF registrado na etapa de encerramento corresponde ao peer de teste fechando sua conexão após os dois checks, não a perda de dados no teste.

O JAR final também foi carregado no Paper com Java 25; status, pedido de criptografia, comandos, rejeição de reload inválido, reload válido, reconnect e encerramento passaram. Esses resultados são incluídos na pasta de evidências da entrega. Os bytes de JAR compilados por JDKs diferentes podem divergir apesar de ambos usarem `--release 21`; o artefato final tem bytecode 65 e foi executado efetivamente em ambas as JVMs.

## Execução dentro do plugin carregado no Paper

Em **21/10/2026 às 00:20:34 São Paulo**, o mesmo JAR final também foi validado com o runtime pertencendo ao **plugin carregado dentro da JVM do Paper**, e não a um runtime iniciado separadamente pelo harness. O peer Java descartável entrou na rede de aceitação manual, recebeu a oferta do plugin pelo serviço real, completou SRP e negociação de serviço em DIRECT_TCP e recebeu status de 166 bytes e pedido de criptografia de 172 bytes. O peer saiu da rede ao terminar. A rede ficou preservada para o teste posterior com o amigo. Evidência: `final-live-plugin-minecraft-evidence.txt`.

O console confirmou `CONNECTED`, IP `26.x.x.x`, peer autenticado e limpeza de fluxos após desconexão. O Paper isolado foi encerrado com `stop`. A preparação manual inclui um lançador local para iniciar depois, instruções de aceitação e um arquivo privado separado com os dados da rede. Não há senha ou identidade dentro do JAR/ZIP de código-fonte.

## Bugs corrigidos durante a implementação

- Registro real envia op 21 antes de op 8: corrigida a sequência e acrescentado teste de controle completo.
- Join privado precisa do proof de servidor e da aprovação GUID correlacionada; membership op 37 isolado não libera a rede.
- ACL e papel precisam ser satisfeitos na mesma rede; redes diferentes não podem compor uma autorização.
- Worker antigo não pode revogar um substituto do mesmo RID ou da nova geração; conexão saudável não pode ser recriada a cada tick.
- Final de UDP com socket remoto encerrando não pode ocultar um frame já completo; ACK/retry/congestion e encerramento receberam regressões.
- Persist cobre também bytes em voo quando a janela fecha; FIN, ACK parcial e epochs de revogação receberam testes.
- Falhas no bind/descoberta de canais diretos preservam alternativas; sockets de candidatos perdedores e timers são liberados.
- Senhas, chaves temporárias e credenciais são separadas de logs/configuração pública; gravação de identidade mantém o mesmo objeto emitido nas tentativas de persistência.

## Ocorrências de ambiente e perfis

Builds paralelos esgotaram a memória/paginação do host. Esses processos falharam antes de estabelecer resultado de testes e NÃO foram marcados como aprovação. Foram limitados heap/worker/forks no projeto e os builds finais executados em sequência. Um teste live inicialmente usou diretório de classes mutável enquanto outro build recompilava; ele foi repetido com JAR imutável. A rede temporária daquele teste incompleto foi removida em execução de limpeza confirmada.

A geração automática de verification-metadata do Gradle 9.1 falhou com colisão de identificadores do Paper API snapshot; nenhum arquivo falso de verificação foi criado. A distribuição do Wrapper tem SHA256 fixado, o inventário/listagem de runtime e a inspeção do JAR passaram. Não foi executado um scanner CVE externo nem emitida certificação de dependências.

O primeiro teste de registro, anterior à correção op21/op8, deixou `registration.pending` num perfil de desenvolvimento em quarentena. Esse perfil não foi registrado novamente nem apagado automaticamente. Dois outros perfis novos foram usados nos testes bem-sucedidos, preservados com ACL privada fora do repositório/artefatos. Nenhuma credencial de instalação existente foi importada. Todas as redes temporárias das execuções automatizadas foram abandonadas/apagadas; não foram alteradas redes do usuário. Depois desses checks, uma rede privada exclusiva de aceitação manual foi preparada para o teste posterior com o amigo informado pelo usuário. Essa rede permanece intencionalmente disponível, com credenciais em arquivo privado separado e lançadores de início/remoção; ela não está incluída no código-fonte ou no JAR.

## Pendências para declarar completo

- Cliente oficial em uma máquina de teste controlada, na mesma rede, conectando ao IP virtual/porta anunciados.
- Login Minecraft online-mode completo e jogo real: mundo, chunks, movimento, chat, desconexão/reconexão e vários jogadores.
- Interop live de relay público e condições WAN/NAT em hosts distintos; perda/rede bloqueada e queda real da conexão em longa duração.
- Carga sustentada/soak em hospedagem alvo; execução Linux e outras versões 1.21.x separadamente.

O host tem um Radmin oficial instalado, mas seu perfil existente foi preservado. Não foram obtidos tokens/credenciais de conta Minecraft nem usado um segundo host oficial controlado. O usuário informou que um amigo possui esse ambiente e vai combinar o teste posteriormente. Esses fatos limitam o E2E e impedem classificar esta entrega como estável, beta validada ou missão completa.

## LANBroadcaster real — 21/10/2026, 00:36–00:37 (São Paulo)

Paper 1.21.1 build 133 / Java 21 carregou OpenRad Plugin 0.2.1-dev e o [LANBroadcaster Paper 2.4.0](https://modrinth.com/plugin/lanbroadcaster/version/toiZxpnA). O download foi conferido com SHA-512 publicado pela API Modrinth, `6101351d054daab2087c87febe6d14e4a09a39b7c7b1d37882c8e59114d73fdd26259f9f7479fd2954c3515b455ff2129a12bca32fafb6972dd04e0bb6821b03`.

O LANBroadcaster anunciou a porta física 25575 e o MOTD `ENTRE AQUI!!!`. O plugin capturou esses anúncios e enviou um quadro multicast cifrado a um peer Java autenticado na rede privada isolada de teste. O peer verificou VIP/MAC de origem, destino 224.0.2.60:4445, checksum IPv4 e o payload `[MOTD]ENTRE AQUI!!![/MOTD][AD]25565[/AD]`. Pela mesma conexão passaram status Minecraft e pedido de criptografia online-mode. O peer saiu da rede ao terminar; a rede de aceitação foi preservada e o Paper foi encerrado com `stop`.

```text
LIVE_PAPER_PLUGIN_AUTHENTICATED_PEER_OK path=DIRECT_TCP
LIVE_LANBROADCASTER_MULTICAST_OVER_AUTHENTICATED_TUNNEL_OK
LIVE_LAN_SOURCE_VIP_OK destination=224.0.2.60:4445 backendPort=25575 virtualPort=25565
LIVE_LAN_MOTD_OK value=ENTRE AQUI!!!
LIVE_PAPER_PLUGIN_STATUS_OK bytes=140
LIVE_PAPER_PLUGIN_ONLINE_ENCRYPTION_REQUEST_OK bytes=172
LIVE_PAPER_PLUGIN_TEST_PEER_LEFT_OK
```

Evidências: `live-lan-evidence.txt`, `paper-lan-java21.log` e checks/XML atuais no pacote. O harness `LivePluginMinecraftSmoke` aceita `--expect-lan` como último argumento para exigir o anúncio antes do teste TCP; esse modo requer LANBroadcaster instalado e o MOTD acima no Paper isolado. A captura não consulta perfis das instalações VPN do usuário. Não houve teste de exibição na lista multiplayer de um cliente Radmin oficial, nem ingresso de jogador autenticado no mundo. O anúncio alcança os membros autorizados das redes configuradas; não foi publicada uma rede pública ou iniciado monitoramento de jogadores desconhecidos.

## Seletores Minecraft e correção do acesso LAN — 21/10/2026

O build 0.2.2-dev acrescenta catálogo autenticado e seletores `all`, `all-<idioma>` e `<idioma>-<número>`, com arquivo UTF-8 e `/orb minecraft [seletor] [pagina]`. Os 93 testes passaram em Java 21 e 25, sem falhas, erros ou casos ignorados. O JAR contém 104 classes em bytecode 21, sem dependências externas de runtime ou binários nativos.

O usuário informou uso de nome offline; o ambiente Paper foi configurado com `online-mode=false` e `enforce-secure-profile=false`. O nome exato `lucasnettocaire` mantém OP nível 4 pelo UUID offline. Esse modo não verifica a propriedade dos nomes de jogador.

A imagem de erro mostrava o anúncio físico `<IP local>:25575`, enquanto o listener aceitava apenas `127.0.0.1:25575`. `server-ip=` foi deixado vazio, mantendo o backend interno do plugin em loopback. O Paper atualizado respondeu ao status Minecraft nos três endereços locais testados: `127.0.0.1:25575`, `<IP local>:25575` e `<IP da LAN>:25575`. O MOTD colorido e a versão 1.21.1 foram conferidos nas respostas. Isso confirma acesso a esses endereços a partir da própria máquina; acesso externo ainda depende da rede/firewall do cliente.

O catálogo real retornou 114 redes Minecraft, incluindo todos os idiomas oferecidos na consulta e variantes Dungeons/Legends. `select: [all]` foi aplicado na instância preparada. Seleção não significa aprovação automática pelo serviço, e `max-peers` continua limitando os canais ativos. As validações anteriores de cliente oficial/gameplay continuam pendentes; testes de catálogo e socket não substituem ingresso autenticado no mundo.

A validação live do novo build terminou com `CONNECTED` e `Minecraft: READY | catalogo: 114 | selecionadas: 114 | avisos: 0`. Foram executados `/orb minecraft all-ptbr`, `ptbr-2`, `ptbr-3`, `all-english`, `all-russian` e a página 12 de `all`; as redes consultadas apareceram como participantes da sessão. O Paper de validação foi encerrado normalmente (exit 0), e a mesma instância/JAR foi reaberta em console visível para o usuário, mantendo o servidor ligado. Evidências: `paper-catalog-java21.log` e `catalog-verification-transcript.log` no pacote.

## Alcance de todos os membros elegíveis — 0.2.3-dev

`max-peers: 0` cobre todo o roster elegível limitado a 4096, com até 16 negociações simultâneas. A busca agora prioriza membros ainda não tentados antes das repetições e ofertas recebidas antes de saídas. Falhas têm backoff exponencial limitado, e não podem reintroduzir membros revogados. A autenticação/ACL continua obrigatória. Um teste determinístico percorreu e autenticou logicamente 512 membros em lotes de 16, sem parar no 64º; esse teste não mede desempenho nem prova 512 conexões reais. Testes separados cobrem ordenação, backoff, revogação, capacidade automática no runtime real e exportação completa das opções YAML.

Os 101 testes passaram em Java 21 e 25, sem falhas/erros/ignorados. O JAR final tem 108 classes Java 21. Falhas de peers permanecem nos contadores por motivo e num resumo a cada 60 segundos, evitando spam por RID. Não foram desativados proofs, checksums ou validação de protocolo para aumentar o número de conexões.

O log enviado pelo usuário mostrou ingresso local de `lucasnettocaire` e comando criativo executado em 21/10/2026, 01:05, via endereço Wi-Fi. Isso comprova uso local informado pelo usuário; não comprova descoberta LAN ou gameplay de outro computador via Radmin oficial. A aparição na lista LAN do cliente oficial continua sem confirmação independente.

Na execução live do 0.2.3-dev, Paper/Java 21 atingiu catálogo READY com 114 redes selecionadas e criou `minecraft-network-options.yml` com 152 seletores. Foram exercitados os comandos de opções (duas páginas), listagem de peers e status. O teste de console terminou com exit 0, sem as antigas linhas repetidas `Peer ... interrupted`; os contadores registraram falhas reais no resumo periódico. A instância foi então reaberta em console visível.

Uma consulta posterior por cliente local de administração (nome offline já autorizado, sem outro jogador conectado) confirmou **123 canais VPN conectados simultaneamente**, **133 membros autenticados nesta sessão**, **205 de 2766 membros elegíveis tentados** e **6124 anúncios LAN enviados**. Esses valores são um retrato momentâneo, não uma promessa de alcance total. A consulta foi somente leitura e o cliente saiu depois. Evidências: `paper-reach-java21.log`, `reach-verification-transcript.log` e `final-visible-server-status.txt`. A ferramenta de protocolo Minecraft usada na administração é de desenvolvimento, fora do JAR e do ZIP do projeto; o plugin não a utiliza.

O servidor foi parado para a atualização por comando Minecraft `/save-all flush` seguido de `/stop`, depois de verificar que não havia jogadores online. Não foi terminado à força. O mundo e as permissões do usuário foram preservados.

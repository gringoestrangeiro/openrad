# OpenRad Client Mod — arquitetura v0.2.0-alpha

## Objetivo e separação

Mod exclusivamente cliente Fabric/Minecraft Java 1.21.1, Java 21. O processo
Minecraft hospeda o protocolo OpenRad; não instala TAP/TUN, drivers, rotas,
serviços do sistema ou dependências Bukkit/Paper. O projeto Paper é referência
de protocolo e não é alterado pela implementação do mod.

`core` não importa classes Fabric, Minecraft ou Netty. Contém configuração,
identidade, autenticação, controle, membership, canais criptografados,
Ethernet, descoberta e TCP virtual. `fabric` compila essas mesmas fontes e
adiciona somente o adaptador Minecraft, os mixins, eventos e comandos locais;
não mantém outra cópia do runtime. As classes históricas sob o namespace
`openradplugin` são implementações Java independentes de Bukkit, reutilizadas
sob MIT; esse namespace não implica dependência do plugin servidor.

## Controle, autorização e ciclo de vida

`ClientVpnRuntime` mantém uma geração de attachment por vez, carrega a identidade
em `config/openrad-client/state` e autentica RSA/SRP em worker de fundo. A VPN
só inicia por comando, botão no Multiplayer ou `auto-connect=true`. O arquivo
`registration.pending` impede duplicar um registro de resultado desconhecido.
Cada projeto/cliente tem sua própria identidade e não deve compartilhar esse
arquivo com uma sessão concorrente.

`ClientPeerService` pertence a uma geração. O worker de controle é o único que
muta membership; publica grants imutáveis de redes explicitamente configuradas.
Uma VIP deve corresponder a uma identidade autenticada elegível. Atualizações de
membership revogam canais, conexões TCP e anúncios daquele peer. Canais
criptografados têm um único proprietário de leitura/escrita para preservar a
sequência e estado da cifra. Handshakes, heartbeats e reconexão não usam a render
thread. Direct TCP, direct UDP e relay reutilizam os negociadores existentes.
Limites e filas impedem criação ilimitada de peers ou frames pendentes.

## API entre núcleo e jogo

- `runtime.status()` retorna um snapshot imutável para comandos/interface.
- `runtime.lanServers()` retorna snapshots validados e com validade limitada.
- `runtime.openTcp(Inet4Address, int, Duration)` valida a autorização, aguarda um
  canal autenticado e abre `OutboundTcpStack.Connection`.
- A conexão expõe `input()`, `output()` e `close()`. Nenhuma API desse contrato
  referencia tipos Minecraft/Fabric/Netty.

`OutboundTcpStack` recebe e emite Ethernet através de um `FrameSink` fornecido
pelo serviço de peers. Ele implementa o lado cliente de TCP virtual; não oferece
um listener TCP virtual de entrada nem encaminhamento genérico do host.
A desconexão da VPN encerra os streams da geração anterior.

## Integração de saída Minecraft

O mixin obrigatório de `Connection.connect(InetSocketAddress, boolean,
Connection)` altera apenas o argumento de endereço do socket para destinos
IPv4 resolvidos em `26.0.0.0/8`. A assinatura foi conferida no bytecode oficial
mapeado de Minecraft 1.21.1. Tanto gameplay quanto status ping passam por esse
ponto. O fallback `ServerStatusPinger.pingLegacyServer` possui um hook equivalente,
pois abre Netty diretamente; seu argumento ServerAddress original é preservado.
Destinos IPv4 comuns, IPv6, endereços locais e hostnames ainda não resolvidos
permanecem intactos. Literais IPv4 26/8 ainda não resolvidos são reconhecidos
sem DNS. A resolução DNS e o handshake Minecraft continuam sob
controle do próprio Minecraft; hostname original, porta original, login,
compressão e criptografia de jogo não são reescritos pelo mod.

`LoopbackBridge` aloca rapidamente uma porta efêmera em **127.0.0.1**, sem DNS
nem acesso VPN no hook. Um worker aceita uma única conexão e fecha o listener.
O destino VPN está fixado antes do accept: não existe protocolo de seleção de
alvo nem proxy que aceite destinos fornecidos pelo cliente local. O worker abre
o stream autorizado e copia dados em ambas as direções com buffers de 16 KiB e
backpressure por escrita bloqueante. A capacidade acompanha `max-connections`
(padrão 16, máximo 64). Accept expira após 5 s, setup usa
`connection-timeout-seconds` (padrão 20), leitura local ociosa expira após 60 s.
Recursos são fechados no EOF, erro, desconexão e encerramento do jogo.

Uma conexão 26/8 não usa a pilha IP do host para alcançar o destino virtual:
se a VPN estiver indisponível ou o peer não for autorizado, a conexão loopback
é encerrada. Não existe fallback silencioso para outro adaptador VPN.
O listener loopback é temporário, mas não autentica processos locais; outro
processo da mesma máquina poderia consumir a porta efêmera durante sua janela
de accept. Não há exposição para interfaces externas. O peer VPN e o destino
continuam sujeitos à autorização do runtime.

## Descoberta no Multiplayer

`LanServerRegistry` só recebe frames de peers autenticados e autorizados.
Valida MAC de origem/destino, IPv4 sem fragmentação, origem igual à VIP do peer,
checksums, UDP multicast Minecraft e conteúdo do anúncio. O endereço exibido
vem da identidade autenticada, não de um endereço arbitrário no MOTD.
O registro é limitado a 256 entradas, com TTL de 10 s, e é limpo na revogação ou
desconexão. Não abre socket multicast do sistema nem retransmite anúncios na
LAN física.

O mixin do Multiplayer combina o snapshot VPN com a lista LAN nativa. Entradas
VPN têm prefixo `[OpenRad]`, preservam sua VIP/porta e expiram quando somem do
snapshot. A lista só é reconstruída quando nomes/endereços mudam ou a lista LAN
física fica dirty, para não reiniciar a seleção a cada tick. O botão OpenRad no
canto superior permite conectar/desconectar antes de entrar em um mundo;
o tooltip mostra fase e detalhe. Comandos `/openrad` continuam disponíveis no
cliente durante o jogo.

## Validação e limites

Testes unitários verificam protocolo, autorização, parsing de LAN, TCP virtual
e o adaptador loopback: fluxo bidirecional maior que um buffer, consumo único
da porta, capacidade, fechamento, falha da VPN e preservação de destinos comuns.
O build Fabric verifica os tipos e o remapeamento dos mixins. Testes de runtime
ou interoperabilidade devem ser descritos separadamente com seu ambiente real;
um build ou sessão de controle autenticada não prova gameplay.

Esta alpha não é uma VPN de sistema. Somente conexões Minecraft capturadas pelo
hook passam pelo transporte. Não oferece UI para segredos de redes privadas,
servidor TCP virtual, UDP genérico ou suporte a outras versões Minecraft.
O protocolo usa criptografia legada de compatibilidade, sem AEAD moderno.
A compatibilidade ponta a ponta com clientes Radmin oficiais e cenários reais
NAT/relay precisa de testes específicos; não deve ser inferida dos testes
sintéticos ou anunciada como comprovada por eles.

## Smoke opcional dentro de Minecraft

`gradlew.bat :fabric:runSmokeClient -PopenradSmoke=true --no-daemon --max-workers=1`
compila um mod de teste separado em `fabric/src/smoke`. O source set e seus
recursos não entram no JAR de produção. A flag é obrigatória para registrar esse
mod e a configuração de execução. O processo usa `fabric/run-smoke`, nome offline
`OpenRadSmoke` e configuração padrão sem redes/identidade.

Após carregar os recursos, o teste usa APIs do próprio jogo para carregar/auditar
os três targets Mixin, inicializar/tickar o Multiplayer e verificar o botão.
Em seguida abre conexões Netty reais: um listener loopback de teste para conferir
o caminho comum e uma VIP 26/8 fictícia para conferir a interceptação mesmo com
VPN offline. Não interage com janelas Windows, não clica em controles do usuário
e não conecta a um servidor Minecraft existente. O Minecraft de teste encerra
automaticamente no sucesso; falha/timeout de 45 s encerra somente seu JVM com
código 1. O resultado fica em `fabric/run-smoke/openrad-smoke-result.txt` e os
logs têm marcadores `OPENRAD_SMOKE`. Isso testa carregamento e hooks reais;
não constitui gameplay ou interoperabilidade Radmin autenticada.

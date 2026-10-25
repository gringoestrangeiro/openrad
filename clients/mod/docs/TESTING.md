# Validação — OpenRad Client Mod 0.2.0-alpha

## Ambiente

Minecraft Java 1.21.1, Fabric Loader 0.17.3, Fabric API 0.116.17+1.21.1,
Java 21, Gradle 9.1.0 e Fabric Loom 1.13.6. Os testes de protocolo abrem
somente serviços sintéticos em loopback; não usam o servidor Paper existente.

## Testes automatizados

```powershell
.\gradlew.bat :core:test :fabric:test :fabric:build
```

Rodada de integração: **77 testes core e 9 testes Fabric, sem falhas**.

Cobertura relevante:

- Vetores RSA/SRP, criptografia e envelopes TLV/Ethernet existentes.
- Coordenador sintético autenticado, handshake SH e frames criptografados em
  caminhos direct TCP, direct UDP e relay; encerramento de candidatos perdedores.
- Membership: somente redes selecionadas, reprovação explícita, VIP duplicada,
  remoção de autorização e ausência de conexão em uma instalação nova.
- Anúncios LAN: vínculo com VIP/MAC autenticados, checksums, porta de destino,
  origem UDP efêmera válida, dados inválidos, expiração e revogação.
- TCP ativo: SYN/ACK, MSS, wrap de sequência, ACK parcial, retransmissão,
  duplicatas/sobreposição, janela zero e persist, buffers limitados, validação
  de pacotes, MAC negociado, RST, FIN, half-close e cancelamento.
- Capacidade: conexões ativas protegidas, prioridade para um servidor solicitado,
  limpeza física assíncrona limitada e cancelamento sem esperar sockets lentos.
- Adaptador Minecraft: portas IPv4 loopback de uso único, alvo fixo, buffers
  limitados, fechamento, timeout de accept, cancelamento e half-close; destinos
  comuns preservados e literais 26/8 não resolvidos reconhecidos sem DNS.

## Interoperabilidade TCP independente

`MinecraftTcpInteropTest` conecta o endpoint cliente novo a uma cópia **somente
para testes** do endpoint TCP de entrada do OpenRad Plugin 0.2.4-dev.
A origem e os hashes dessa referência estão em `REFERENCE-TEST-FIXTURE.txt`.
As classes de referência não entram no JAR do mod e o repositório de produção
não é alterado.

Os frames passam por `TunnelCodec` e pelos canais CBC reais em ambas as direções.
Um backend TCP isolado verifica o protocolo Minecraft: handshake 767, endereço
virtual e porta originais, solicitação de status, resposta JSON e ping/pong.
O teste descarta o primeiro SYN e o primeiro segmento de dados, exigindo
recuperação por retransmissão. Outro teste transmite **512 KiB**, encerra apenas
a saída do cliente e verifica a resposta SHA-256 enviada após o EOF.

Esses testes verificam bytes e estado de transporte. O backend é sintético;
não equivalem a login completo, mundo renderizado ou partida multiplayer.

## Verificação do cliente Minecraft

O cliente de desenvolvimento foi iniciado em `fabric/run/`, separado do perfil
Minecraft do usuário. Fabric carregou o mod 0.2.0-alpha e gerou a configuração
com a VPN desligada; nenhum perfil OpenRad foi registrado nessa inicialização.
O smoke test opt-in passou no Minecraft real em 21/10/2026:

```powershell
.\gradlew.bat :fabric:runSmokeClient -PopenradSmoke=true --max-workers=1
```

O teste usa um mod auxiliar somente em `src/smoke`, perfil isolado
`fabric/run-smoke/`, configuração sem redes e watchdog de 45 s. Esse mod de teste
não é incluído em `jar`/`remapJar`. Audita os três alvos de mixin no transformer
real do Fabric; inicializa e executa tick do Multiplayer, verifica o botão;
abre uma conexão Netty real para um serviço loopback isolado e outra para uma
VIP 26/8 fictícia, confirmando que a segunda chega a uma porta efêmera em
127.0.0.1. O cliente de teste encerra automaticamente e grava o resultado em
`fabric/run-smoke/openrad-smoke-result.txt`.

Marcadores confirmados: `MIXIN_AUDIT PASS`, `MULTIPLAYER_INIT_TICK_BUTTON PASS`,
`ORDINARY_CONNECTION PASS`, `VIRTUAL_CONNECTION_INTERCEPT PASS`, `RESULT PASS`.
O teste verifica o uso do adaptador com a VPN desligada; não faz registro remoto
nem se conecta a um servidor Minecraft externo. Uma inicialização do jogo,
sozinha, não comprova o transporte de gameplay.

## Verificação manual ponta a ponta

1. Use uma instalação de teste Fabric 1.21.1 com Fabric API e este JAR.
2. Configure uma rede pública exata compartilhada com o servidor; use uma
   identidade exclusiva e conecte pelo botão OpenRad na tela Multiplayer.
3. Confirme peer autenticado; entre pelo anúncio `[OpenRad]` ou VIP/porta
   virtual. Verifique login, mundo, chat, movimento, chunks e desconexão.
4. Repita com direct TCP, direct UDP e relay em hosts independentes e redes
   com NAT diferentes. A conexão de controle ou a presença em uma rede não
   substitui esses testes.
5. Para compatibilidade oficial, repita usando um peer no **Radmin VPN oficial
   para Windows**. Nenhum resultado sintético deve ser apresentado como essa prova.

Login/mundo reais com outro host, NAT da Internet e Radmin oficial continuam
pendentes. A alpha não oferece UI/senha para redes privadas nem UDP genérico.

> **Download (2.1.5, alpha):** [release 2.1.5](https://github.com/gringoestrangeiro/openrad/releases/tag/v2.1.5),
> arquivos `OpenRadClientMod-2.1.5-fabric-<versão>.jar` para Minecraft 1.21–1.21.11 (Java 21)
> e 26.1–26.3 (Java 25). Instale **um** JAR, da versão exata do seu jogo, com a Fabric API.
> Veja o [resumo da 2.1.5](README-2.1.5.md); as seções abaixo descrevem gerações anteriores.

# OpenRad Client Mod — Minecraft Java 1.21 a 26.3

**Matriz estendida:** versões Minecraft **1.21 até 1.21.11** (Java 21, builds `0.2.3-alpha`) e **26.1 até 26.3** (Java 25, builds `0.3.0-alpha`). Veja [a matriz detalhada gerada a partir dos JARs](docs/COMPATIBILITY-1.21-26.3.md) e use `build-full-range.ps1` para reconstruir e testar os alvos.

**Atenção:** compilação não comprova inicialização do Minecraft ou gameplay. O suporte real a cada versão ainda requer testes de cliente e mixins; não instale mais de um JAR OpenRad por perfil.

A documentação de `0.2.3-alpha` logo abaixo descreve a geração anterior; a adaptação 26.x está em `modern/`.

Versão **0.2.3-alpha**, Java 21. Cliente VPN dentro do processo Minecraft, sem instalar TAP/TUN, drivers ou rotas do sistema.

Esta versão implementa canais autenticados entre peers e **TCP virtual de saída** para servidores `26.x.x.x`. O Minecraft usa seus protocolos normais de status e jogo sobre esse fluxo. As conexões para outros endereços continuam pelo caminho normal.

- Autenticação RSA/SRP, identidade própria persistida, heartbeat e reconexão.
- Peers autorizados somente por redes públicas explicitamente configuradas; negociações TCP/UDP/relay reutilizam o protocolo OpenRad.
- TCP com SYN/ACK, sequência de 32 bits, buffers limitados, janela, retransmissão, FIN/RST e cancelamento.
- Anúncios LAN validados recebidos de peers autenticados aparecem na tela Multiplayer com o prefixo `[OpenRad]` e expiram quando deixam de chegar.
- Botão OpenRad no Multiplayer e comandos `/openrad connect`, `/openrad disconnect`, `/openrad status`, `/openrad networks`.

## Suporte a mais versões — 0.2.3-alpha

O mod agora oferece **quatro builds separados**, para Minecraft Java
**1.20.6, 1.21, 1.21.1 e 1.21.4**. Cada JAR usa a Fabric API adequada
ao seu alvo; coloque somente o JAR da sua versão na pasta mods/.
Os quatro builds passaram em Gradle, mas a inicialização e a jogabilidade
em todas as versões ainda exigem testes reais de cliente.

Confira a matriz e os limites em docs/COMPATIBILITY-0.2.3.md.

Para recompilar os quatro alvos, use o script build-matrix.ps1.
Para apenas a 1.21.4, execute:
    .\build-matrix.ps1 -MinecraftVersions '1.21.4'

## Instalação

1. Use uma das versões validadas (**1.20.6, 1.21, 1.21.1 ou 1.21.4**), Java **21**, Fabric Loader compatível e a Fabric API da mesma versão. Confira a matriz no documento de compatibilidade.
2. Coloque somente o JAR correspondente ao seu Minecraft na pasta mods/ (por exemplo OpenRadClientMod-0.2.3-alpha-fabric-1.21.1.jar para 1.21.1).
3. Abra o jogo uma vez. O mod cria `config/openrad-client/openrad-client.properties` com a conexão desligada.
4. Feche o jogo e configure nomes **exatos** das redes públicas, separados por ponto e vírgula. Exemplo:

```properties
auto-connect=false
network-names=Minecraft [Português 1];Minecraft [English 2]
max-peers=256
max-connections=16
connection-timeout-seconds=20
```

5. Abra o jogo e clique **OpenRad** na tela Multiplayer. Aguarde a autenticação e o estabelecimento de um canal com o peer do servidor.
6. Entre pelo anúncio `[OpenRad]` ou adicione diretamente o IP virtual e a porta, por exemplo `26.1.2.3:25565`. O destino precisa estar online e compartilhar uma rede selecionada e autorizada.

O servidor precisa estar acessível na VPN e escutando na porta anunciada. No OpenRadBridge, use a porta **virtual exposta** pelo plugin. Uma sessão de controle conectada, por si só, não comprova conectividade com um peer ou jogabilidade.

## Configuração e limites

`max-peers` aceita 1..4096; no máximo oito novas negociações acontecem simultaneamente. `max-connections` aceita 1..64 e inclui status/pings e conexões de jogo. `connection-timeout-seconds` aceita 1..120. Configurações antigas sem essas opções mantêm os valores padrão.

A identidade fica em `config/openrad-client/state/` e é exclusiva do mod. Nunca copie a identidade do plugin/cliente VPN para esse diretório nem compartilhe `identity.json` ou `registration.pending`. Um lock impede uso simultâneo do mesmo perfil. O registro remoto só ocorre após conectar explicitamente ou configurar `auto-connect=true` com redes selecionadas.

Ainda não há configuração de senha para redes privadas, VPN para outros programas ou validação com o cliente Radmin oficial. A criptografia legada mantém compatibilidade com OpenRad e não é AEAD moderno. Consulte [docs/TESTING.md](docs/TESTING.md) para distinguir os testes automatizados da validação real de gameplay.

## OpenRad Menu — versão 0.2.2-alpha

O menu abre na tela inicial, no Multiplayer, na pausa ou via `/openrad menu`.

- **Conectar / Desconectar** a VPN do Minecraft.
- **Redes**: escolher **Todas** (`all`), **Todas em português**
  (`all-ptbr`), **Todas em japonês** (`all-japanese`) e filtros de idiomas.
  Após carregar o catálogo autenticado, redes numeradas (`ptbr-1` etc.)
  também aparecem.
- **Conectar automaticamente**: desligado por padrão, requer rede selecionada.
- **Avançado**: máximo de peers, conexões de jogo, timeout, limites de
  redes, nomes exatos e filtros técnicos.
- **Salvar**: grava alterações sem expor credenciais; alterações de rede
  durante uma conexão existente podem exigir reconexão.

A tela de idiomas pode ser usada **offline**; redes numeradas são mostradas
quando houver uma listagem autenticada. Filtros salvos permanecem editáveis.

Os textos acompanham o idioma escolhido no Minecraft. Português brasileiro,
inglês e japonês possuem traduções integrais. Onze idiomas adicionais têm
traduções parciais, e os outros idiomas utilizam textos nativos do Minecraft
onde possível, com fallback em inglês para mensagens técnicas restantes.
O nome **OpenRad** não é traduzido.

Detalhes em [docs/OPENRAD-MENU.md](docs/OPENRAD-MENU.md).

## Compilar

```powershell
.\gradlew.bat :core:test :fabric:test :fabric:build
```

O JAR remapeado está em `fabric/build/libs/`; os artefatos de entrega ficam em `release/`. O diretório `core/` não depende de Minecraft, Fabric, Bukkit ou Paper. Consulte [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

O desenvolvimento e os testes deste mod não modificam o plugin Paper nem seu servidor de testes.

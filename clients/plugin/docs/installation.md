# Guia de instalação e configuração

Versão 2.2.0-dev (alpha; último release publicado: 2.1.5); veja as pendências de aceitação em `tests-live.md`.

**Caminho mais simples:** instale o JAR e, no console, use `orb join "Nome da Rede" senha` e `orb status`. Os comandos abaixo descrevem a configuração manual equivalente.

## Build

Requer JDK 21; o Gradle Wrapper baixa a distribuição fixada. A partir de `clients/plugin/`:

```sh
./gradlew clean check        # Linux/macOS
.\gradlew.bat clean check    # Windows
```

O JAR é gerado em `build/libs/OpenRadPlugin.jar`. O `check` executa os testes JUnit e o `verifyJar`, que valida metadados Paper, bytecode Java 21, licença e ausência de bibliotecas nativas ou dependências de runtime empacotadas.

## Preparar o Paper

Use um servidor Paper 1.21.x com Java 21. O teste executado usou Paper 1.21.1build 133. Copie somente `OpenRadPlugin.jar` para `plugins/`, inicie e aguarde os arquivos padrão serem gerados. O plugin vem desativado até você configurar suas redes.

O Paper mantém seu próprio listener TCP. Se estiver escutando em todas as interfaces, `127.0.0.1` costuma ser um backend adequado; se estiver limitado a um IP específico que não aceita loopback, ajuste a configuração do servidor para permitir o loopback necessário. O plugin só admite `127.0.0.1` ou `::1` como backend.

## Configurar redes

Edite `plugins/OpenRadPlugin/config.yml` com nomes de redes existentes. Para rede pública, `secret` vazio; para privada, uma chave no arquivo privado `plugins/OpenRadPlugin/secrets.properties`:

```yaml
vpn:
  device-name: "Paper server"
  networks:
    - name: "Private Minecraft"
      secret: "private-minecraft"
      allow-rids: ["123456789"]
      deny-rids: []
```

```properties
private-minecraft=your-network-password
```

O arquivo de segredos é UTF-8 no formato JavaProperties; caracteres especiais de Properties precisam de escape, por exemplo `\` escrito como `\\`. Não inclua senhas em diagnósticos compartilhados. Arquivos são restringidos ao proprietário; não os sincronize para um local público. A identidade é persistida em `identity.json` após registro.

`vpn.acl` aplica filtros globais; cada entrada de rede pode restringir RIDs adicionalmente. Deny tem precedência. O peer só é admitido se a mesma rede satisfizer a ACL e os papéis de membership. Não basta pertencer a alguma outra rede liberada.

## Habilitar e conectar

Configure `bridge.enabled: true` e a porta virtual, depois `/orb reload` (ou use `/orb enable`). O backend usa a porta do próprio Paper quando `bridge.backend-port: 0`. A leitura/validação e a conexão ocorrem em workers. `/orb status` mostra o IP 26atribuído e peers. O jogador usa o Radmin oficial na rede correspondente e conecta no endereço mostrado, com a porta virtual. Ping ICMP não é suportado.

O plugin não modifica `online-mode`. Mantenha a autenticação de jogador que seu servidor já exige. O Paper enxerga uma conexão de loopback; o IP virtual não é encaminhado como IP real e não há suporte inventado a Bungee/Velocity forwarding.

## Limites e operações

`bridge.max-players-through-vpn` limita fluxos TCP simultâneos, incluindo conexões auxiliares/handshakes do Minecraft; não é uma contagem exata de jogadores. Há também limite interno de8 fluxos por peer e `bridge.max-pending-tcp` para handshakes pendentes. O runtime limita peers com `vpn.max-peers` e até8 handshakes de peer simultâneos. Filas, memória e deadlines adicionais estão em `virtual-tcp.md`.

`/orb reload` valida a nova configuração/segredos antes de encerrar conexões. Uma alteração inválida não substitui a configuração vigente. Uma configuração válida reinicia a geração e desconecta jogadores. `/orb reconnect` mantém a configuração atual validada. Retirar uma rede revoga seu acesso local; não apaga a participação no serviço.

Se houver `registration.pending` sem identidade salva, preserve esse estado. Registro automático permanece bloqueado porque o serviço pode já ter emitido a credencial. Uma decisão deliberada por nova identidade requer tratar esse perfil separadamente; não apague marcadores automaticamente em scripts de recuperação.

O diagnóstico de socket não comprova um túnel. Verifique fase/IP/peers e registre evidências reais antes de anunciar a instância como pronta para gameplay. Não altere o perfil de VPN existente no computador durante esse teste.

## Plugins de anúncio LAN

LANBroadcaster e plugins que emitem o mesmo formato de descoberta Minecraft Java são suportados pela captura multicast local. A opção `bridge.lan-discovery.enabled` é `true` por padrão. Consulte [a configuração completa](lan-discovery.md) e os contadores em `/orb status`.

## Seleção das redes públicas

Use `vpn.minecraft-networks.enabled: true` e `select: [all]` para todas as redes Minecraft do catálogo. Também existem `all-ptbr`, `ptbr-1`, `ptbr-2`, `ptbr-3`, `all-english` e os equivalentes de outros idiomas. `select: []` apenas lista. Confira `/orb minecraft` e [o guia completo](minecraft-networks.md).

Para tentar todos os membros elegíveis, use `vpn.max-peers: 0`. A lista de valores para redes pode ser consultada por `/orb minecraft options` e `minecraft-network-options.yml`. Status diferencia candidatos, tentativas, autenticações e falhas; conexões não são garantidas por selecionar uma rede.

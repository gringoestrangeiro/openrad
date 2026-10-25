# OpenRad Plugin 2.1.5 (alpha)

> **A 2.1.5 ainda está em desenvolvimento, por isso ainda não há release 2.1.5.** O código
> está versionado como 2.1.5; a última release publicada é a
> [2.1.0](https://github.com/gringoestrangeiro/openrad/releases/tag/v2.1.0), cujos links
> de download aparecem abaixo.

Plugin Java 21 para servidores **Paper 1.21.x** que coloca o próprio servidor Minecraft dentro de uma rede Radmin VPN/OpenRad. Jogadores que usam o Radmin VPN oficial (ou o OpenRad) entram no servidor digitando o IP `26.x.x.x` do plugin — sem instalar VPN, TAP/TUN ou rotas no host. O artefato é somente `OpenRadPlugin.jar`; o plugin não inicia processos nem carrega bibliotecas nativas, então funciona em hospedagens compartilhadas.

**Estado: alpha.** Testes automatizados, interoperabilidade com o cliente Rust canônico e testes no serviço real até o pedido de criptografia do Minecraft passam. Gameplay completo com vários jogadores pelo Radmin VPN oficial ainda não foi medido; veja [o relatório](docs/tests-live.md) e as [notas da 2.1.0](docs/changes-2.1.0.md).

## Início rápido (3 passos)

1. Baixe `OpenRadPlugin-2.1.0.jar` da [release 2.1.0](https://github.com/gringoestrangeiro/openrad/releases/tag/v2.1.0) (ou compile com `./gradlew clean check`, veja abaixo), coloque em `plugins/` e inicie o servidor (Paper 1.21.x, Java 21). Confira o arquivo com o `SHA256SUMS` da release.
2. No **console** do servidor, entre na sua rede Radmin:

   ```text
   orb join "Nome da Rede" senhaDaRede
   ```

   Rede pública: `orb join "Nome da Rede"` (sem senha). Redes públicas Minecraft do catálogo: `orb public all-ptbr` (ou `all`, `all-english`, `ptbr-1`…; veja `orb minecraft options`).
3. Rode `orb status`. Ele mostra o IP virtual, por exemplo `Jogadores ... conectam em: 26.12.34.56`. Os jogadores entram na mesma rede pelo Radmin VPN e adicionam esse IP no Minecraft.

Pronto: o comando grava `config.yml`, guarda a senha em `secrets.properties` (nunca no YAML), liga a VPN do plugin e aplica tudo sem reiniciar o servidor. A porta do Minecraft é detectada automaticamente (`bridge.backend-port: 0`).

> A senha não é aceita de jogadores, só pelo console: comandos digitados por jogadores ficam registrados no log do Paper. Em painéis que guardam histórico do console, prefira editar `secrets.properties` (veja abaixo).

## Comandos

Todos exigem `openradplugin.admin` (OP por padrão). Nomes com espaço vão entre aspas.

| Comando | O que faz |
| --- | --- |
| `/orb help` | Lista os comandos. |
| `/orb status` | Fase, IP virtual, endereço para os jogadores, peers, métricas TCP e anúncios LAN. |
| `/orb join <rede> [senha]` | Adiciona (ou atualiza a senha de) uma rede e ativa a VPN. Senha só pelo console. |
| `/orb leave <rede>` | Remove a rede da configuração e apaga a senha salva. Não sai da rede no serviço Radmin. |
| `/orb public <seletor...\|off>` | Seleciona redes públicas Minecraft (`all`, `all-ptbr`, `ptbr-1 ptbr-2`, …) ou `off`. |
| `/orb enable` / `/orb disable` | Liga/desliga a VPN do plugin mantendo a configuração. |
| `/orb networks` | Redes conhecidas da sessão autenticada. |
| `/orb minecraft [seletor] [pagina]` | Catálogo público Minecraft; `/orb minecraft options` lista os seletores. |
| `/orb peers [pagina]` | Peers elegíveis, IPs e caminho de transporte. |
| `/orb reload` | Relê `config.yml`/`secrets.properties`, valida e aplica (desconecta jogadores da VPN). |
| `/orb reconnect` | Reinicia a sessão VPN com a configuração atual. |
| `/orb diagnose` | Probes de loopback e de saída configurados. Não comprova o túnel. |

Configuração inválida nunca derruba a sessão atual: o comando responde com o motivo (por exemplo `a senha da rede deve ter de 6 a 256 caracteres`) e nada é alterado.

## Configuração manual (opcional)

Tudo acima também pode ser feito editando os arquivos em `plugins/OpenRadPlugin/`:

1. Use Paper 1.21.x e Java 21. Mantenha o listener Minecraft existente; o plugin não abre outro listener físico na porta 25565.
2. Coloque `build/libs/OpenRadPlugin.jar` em `plugins/` e inicie o servidor. O plugin gera `config.yml` e `secrets.properties`, inicialmente com a VPN desativada.
3. Adicione suas redes em `vpn.networks`. Para rede privada, referencie uma chave de `secrets.properties`; não coloque a senha no YAML público.
4. O backend é `127.0.0.1` na porta do próprio Paper (`bridge.backend-port: 0` = automático). Defina um número só se o Minecraft escutar em outra porta local.
5. Defina `bridge.enabled: true` e execute `/orb reload` como administrador. A sessão inicial registra uma identidade exclusiva, salva a credencial com acesso restrito ao proprietário e conecta às redes configuradas.
6. Consulte `/orb status`. O endereço `26.x.x.x` pertence à interface virtual interna do plugin. Jogadores da mesma rede usam esse IP e `bridge.listen-virtual-port`.

Exemplo de `config.yml`:

```yaml
bridge:
  enabled: true
  backend-host: "127.0.0.1"
  backend-port: 0          # automático: porta do server.properties
  listen-virtual-port: 25565
vpn:
  device-name: "My Paper server"
  networks:
    - name: "My private network"
      secret: "minecraft-network"
      allow-rids: []
      deny-rids: []
```

Arquivo **privado** `secrets.properties` (UTF-8):

```properties
minecraft-network=your-network-password
```

Para rede pública, use `secret: ""`. A lista de redes precisa existir no serviço; o plugin não cria automaticamente redes a partir de nomes na configuração. Redes pendentes de aprovação administrativa não recebem acesso. Uma lista de RIDs permitidos vazia libera membros autenticados das redes configuradas; a lista de bloqueio tem precedência. IDs sem sinal devem ser escritos entre aspas no YAML.

O backend é fixado pelo administrador, não pelo peer. O Paper enxerga a origem como loopback, não como o IP virtual do jogador. `online-mode` e o protocolo Minecraft não são alterados. Não habilite IP forwarding de proxies esperando que o plugin forneça o IP real.

## LANBroadcaster e descoberta LAN

Anúncios Minecraft Java emitidos localmente por plugins como o LANBroadcaster são capturados e enviados aos peers VPN autenticados e autorizados. O IP anunciado é o VIP do OpenRad Plugin e a porta é `bridge.listen-virtual-port`. A opção `bridge.lan-discovery.enabled` vem habilitada por padrão. Configure o MOTD no servidor e use `/orb status` para conferir a captura e os envios. Veja [configuração e limites da descoberta LAN](docs/lan-discovery.md).

O resumo periódico `VPN discovery` fica desligado por padrão: configure `vpn.discovery-log-interval-minutes: 5` se quiser registros a cada cinco minutos. Os contadores continuam em `/orb status`. A antiga pasta de dados `plugins/OpenRadBridge` é migrada automaticamente quando possível, preservando a identidade. Não carregue o JAR antigo e o novo juntos.

## Redes públicas Minecraft

Redes públicas podem ser selecionadas por `vpn.minecraft-networks.select`: `[all]`, `[all-ptbr]`, `[ptbr-1, ptbr-2, ptbr-3]`, `[all-english]` e equivalentes de todos os idiomas retornados pelo serviço. `[]` apenas consulta o catálogo. Use `/orb minecraft [seletor] [pagina]` e o arquivo `minecraft-networks.txt` para ver nomes, números e membros informados. `/orb minecraft options` e `minecraft-network-options.yml` mostram todos os valores válidos para a configuração. `vpn.max-peers: 0` tenta alcançar todos os membros elegíveis (roster limitado a 4096), sem o corte de 64, com negociações graduais e retentativas espaçadas. Veja [o guia de seletores](docs/minecraft-networks.md).

I/O administrativo, DNS evitável, registro, SRP, peers e backend executam fora da thread principal do Paper. Status e listagens usam snapshots. Desativação do plugin cancela sockets e espera um prazo limitado pela limpeza.

## Identidade e recuperação

`identity.json` e `secrets.properties` contêm segredos. Não os compartilhe nem coloque em controle de versão. O formato da identidade é compatível com o Rust, mas identidades em uso não devem ser compartilhadas simultaneamente entre clientes. O plugin usa um lock de arquivo e nunca importa os perfis de outras instalações automaticamente.

`registration.pending` significa que um pedido pode ter emitido uma identidade, mas a conclusão não foi confirmada/persistida. O plugin bloqueia outro registro automático para evitar duplicação. Preserve o arquivo e recupere a identidade antes de decidir manualmente por um novo registro. Falhas de gravação de uma identidade recém-emitida recebem tentativas de persistência usando o mesmo objeto e a mesma credencial.

Reload remove o acesso de redes retiradas da configuração, mas não apaga sua participação persistente no serviço Radmin. A ACL considera apenas as redes configuradas e papéis ativos. Uma alteração de endereço, servidor, papel ou autorização invalida os canais afetados.

## Build e verificação

Requer JDK 21. O Gradle Wrapper baixa a distribuição fixada; não é preciso instalar Gradle.

```sh
./gradlew clean check        # Linux/macOS
.\gradlew.bat clean check    # Windows
```

A saída é `build/libs/OpenRadPlugin.jar`. `verifyJar`, incluído em `check`, verifica bytecode Java 21, metadados, licença e ausência de executáveis/bibliotecas nativas ou dependências de runtime empacotadas. Paper API é `compileOnly`; JUnit é apenas de teste. O Gradle Wrapper tem checksum da distribuição fixado. O build restringe memória e paralelismo para ambientes menores.

Os testes normais são locais e sintéticos; não contatam o serviço real. Scripts em `scripts/` e `src/test/interop/` são ferramentas explícitas de desenvolvimento, não entram no JAR e não são necessários na hospedagem. Os scripts live exigem perfis de teste isolados e criam/apagam suas próprias redes temporárias.

Documentação: [instalação detalhada](docs/installation.md), [arquitetura](docs/architecture.md), [paridade](docs/protocol-parity.md), [criptografia](docs/crypto-parity.md), [transportes](docs/transport-parity.md), [TCP virtual](docs/virtual-tcp.md), [ameaças](docs/threat-model.md), [hosts e limites](docs/host-compatibility.md), [testes](docs/tests-live.md), [licenças](docs/third-party-licenses.md).

Referência técnica: [OpenRad](https://github.com/gringoestrangeiro/openrad), monorepo 2.1.0; licença MIT em `LICENSE`. Este é um projeto comunitário sem afiliação com Radmin/Famatech.

# OpenRad — guia do CLI

Este guia descreve o CLI do código atual do OpenRad, incluindo o comando
`broadcast-peer`. CLI e desktop usam o mesmo serviço, perfil, identidade e
preferências de conexão. O [guia em inglês](cli.md) contém a documentação
equivalente do projeto.

Os nomes, RIDs e endereços usados nos exemplos são ilustrativos.

## Executar o CLI

Nos exemplos, `openrad` representa o executável disponível no `PATH`. No Linux,
a partir da raiz do projeto, o binário compilado está em
`./target/release/openrad`. Em um arquivo de distribuição extraído, execute
`./openrad`. No PowerShell, use `.\openrad.exe` no diretório do programa.

Para compilar o workspace a partir do código-fonte, com Rust 1.95 ou superior:

```sh
cargo build --workspace --release --locked
```

No Linux, execute o cliente como usuário normal. Somente o auxiliar temporário
que configura a interface TAP recebe privilégios; permita a autorização do
Polkit ao conectar. Ambientes sem agente de autorização precisam configurar
essa permissão conforme o [guia do Linux](linux.md#tap-permissions).

No Windows, o suporte é experimental. É necessário instalar o adaptador
TAP-Windows6 dedicado do OpenRad e executar com elevação. O instalador pode
preparar o CLI sem abrir o desktop:

```powershell
.\OpenRad-Setup.exe --no-launch
```

Consulte o [guia do Windows](windows.md) para instalação, recuperação e limites
da validação da plataforma. Ao trocar de compilação, mantenha os executáveis
CLI e desktop juntos. Um serviço já iniciado continua executando o código
antigo até ser encerrado e iniciado novamente.

## Sintaxe, opções e ajuda

```text
openrad [OPÇÕES] COMANDO [ARGUMENTOS] [OPÇÕES_DO_COMANDO]
```

| Opção | Efeito |
| --- | --- |
| `--data-dir CAMINHO` | Seleciona o perfil. Use o mesmo caminho em todos os comandos e no desktop. |
| `--language system\|en\|pt\|ru\|vi` | Define o idioma desta execução. O padrão é `system`. |
| `--json` | Imprime a resposta em JSON para uso em scripts. |
| `-h`, `--help` | Mostra a ajuda geral ou a ajuda de um comando. |
| `-V`, `--version` | Mostra a versão do executável. |

```sh
openrad --help
openrad --version
openrad join --help
openrad broadcast-peer --help
openrad --language pt status
```

O idioma automático segue as variáveis de locale do sistema. Idiomas não
suportados usam inglês. Comandos, opções, nomes informados pelo usuário e
valores do JSON continuam com seus valores originais.

## Primeiro uso e identidade

Registre uma identidade uma única vez para o perfil:

```sh
openrad init --node-name meu-dispositivo
openrad start
openrad status
```

Se você já tem um arquivo de identidade do OpenRad, importe-o:

```sh
openrad init --identity identidade.json
```

A importação reutiliza a identidade e não registra outro dispositivo. O comando
`init` recusa sobrescrever uma identidade existente ou inicializar um perfil
em uso. Se a identidade já está no armazenamento de credenciais do desktop,
os dois frontends a reutilizam; um armazenamento bloqueado deve ser
desbloqueado antes de tentar registrar outra identidade.

O CLI salva identidades provisionadas/importadas no arquivo privado
`profile/identity.json`, dentro do perfil. No Linux, o diretório usa permissão
`0700` e o arquivo usa `0600`. Arquivos de identidade aceitos pelo CLI são
limitados a 64 KiB. Preserve uma cópia de segurança e trate esse arquivo como
uma credencial.

Para ambientes controlados, `init` também aceita `--host ENDEREÇO` e
`--modulus ARQUIVO`, que substituem o servidor de registro e o módulo RSA. O
módulo é salvo no perfil para as próximas conexões.

## Perfis e serviço compartilhado

O perfil padrão no Linux é `$XDG_STATE_HOME/openrad`, normalmente
`~/.local/state/openrad`. No Windows, é `%LOCALAPPDATA%\openrad`. Quando
apropriado, um perfil existente do desktop é preservado para reutilizar a
identidade já salva.

Para usar outro perfil:

```sh
openrad --data-dir /caminho/do/perfil start
openrad --data-dir /caminho/do/perfil status
openrad --data-dir /caminho/do/perfil peers
openrad --data-dir /caminho/do/perfil broadcast-peer 123456
```

Informe esse mesmo `--data-dir` ao desktop. Perfis diferentes podem ter
identidades e configurações diferentes.

| Comando | Comportamento |
| --- | --- |
| `start` | Inicia o serviço em segundo plano ou reutiliza o serviço desse perfil. |
| `start --no-tap` | Inicia o serviço e os canais com peers sem criar a interface de rede para tráfego das aplicações. |
| `status` | Mostra o estado do serviço, da interface, das redes, dos peers e do tráfego. |
| `stop` | Encerra a sessão compartilhada e limpa a configuração de interface criada para a sessão. |

`start` retorna quando o serviço local aceita comandos; a autenticação ainda
pode estar em andamento. Consulte `status` para acompanhar `connecting`,
`connected`, `reconnecting` ou um erro. Rodar `start` novamente reutiliza o
serviço, inclusive quando o desktop já está aberto.

Fechar o terminal ou a janela do desktop mantém o serviço conectado. O comando
`stop` e o botão **Desconectar** do desktop encerram a sessão dos dois
frontends. No Linux, a interface TAP temporária é removida ao encerrar. No
Windows, a configuração da sessão é removida e o adaptador instalado permanece.

Após substituir os binários, carregue a nova compilação encerrando e iniciando
o serviço quando puder interromper a sessão:

```sh
openrad stop
openrad start
```

## Redes, peers e conexão

```sh
openrad networks
openrad peers
openrad search minecraft
openrad join 'Rede Pública de Exemplo'
openrad ping 123456
```

`networks` lista nome, ID e seu papel nas redes. `peers` mostra RID, nome,
endereço da VPN, estado de conexão e transporte escolhido. Use o RID para
comandos que exigem um membro específico.

`search` aceita uma consulta opcional e `--cursor NÚMERO` para continuar a
paginação. Redes privadas são acessadas pelo nome exato e pela senha. Um
membro pendente de aprovação não pode trocar tráfego até ser aprovado pelo
administrador. Os peers precisam estar online e autorizados para trocar
pacotes; o serviço acompanha novas presenças automaticamente.

Para testar uma conexão já autenticada:

```sh
openrad ping 123456
```

O RTT é medido pelo canal do peer, com prazo de 3.000 ms. Isso exige uma
conexão estabelecida com o peer; um estado apenas online não basta.

Para mudar o nome do seu dispositivo ou a política de transporte:

```sh
openrad rename novo-nome
openrad force-relay true
openrad force-relay false
```

`rename` mantém identidade, credenciais, endereço e associações às redes. Uma
sessão ativa reconecta para anunciar o nome novo. `force-relay true` salva o
uso exclusivo de relays e reconecta sem tentativas diretas TCP/UDP.
`force-relay false` restaura a seleção normal de transporte. Essas preferências
também são usadas pelo desktop.

## Broadcast de saída para um único peer

Sem restrição, o OpenRad replica broadcasts para os peers elegíveis e
autenticados. Para encaminhar todos os broadcasts que saem da máquina apenas
a um peer, use:

```text
openrad broadcast-peer [DESTINO]
openrad broadcast-peer --all
```

| Uso | Efeito |
| --- | --- |
| `openrad broadcast-peer` | Consulta a configuração sem alterá-la. |
| `openrad broadcast-peer 123456` | Salva o RID indicado como único destino. |
| `openrad broadcast-peer 26.1.2.3` | Localiza um peer pelo IP da VPN e salva seu RID. |
| `openrad broadcast-peer 'Nome do peer'` | Localiza um peer pelo nome exato e salva seu RID. |
| `openrad broadcast-peer 0.0.0.0` | Remove a restrição e volta a distribuir aos peers elegíveis. |
| `openrad broadcast-peer --all` | Faz a mesma reversão. |

O valor `0.0.0.0` serve somente para limpar a configuração. O RID `0` é
inválido, e `--all` não pode ser combinado com um destino.

A seleção por nome ou IP precisa da lista de peers carregada pelo serviço.
Se houver mais de um resultado, o comando falha; use o RID mostrado por
`peers`. Nomes compostos apenas por números são interpretados como RIDs.
A seleção por RID, a consulta e a reversão funcionam com o serviço parado e
não iniciam uma conexão.

A preferência é salva pelo RID e compartilhada com o desktop. Se você
selecionar por nome ou IP, a resolução ocorre naquele momento: renomear o
peer ou mudar seu IP depois não transfere a configuração para outro peer.
Salvar um RID ainda desconhecido é permitido, mas ele só recebe tráfego
quando estiver autorizado e com uma conexão estabelecida.

O efeito sobre os pacotes é o seguinte:

- Somente os broadcasts **de saída** são restringidos. O recebimento continua
  aceitando broadcasts de todos os peers autorizados.
- A regra inclui quadros Ethernet com MAC de destino
  `ff:ff:ff:ff:ff:ff`, broadcasts IPv4 válidos e os anúncios ARP gratuitos
  gerados pelo OpenRad.
- Os IPs, MACs e o conteúdo do quadro enviado permanecem iguais. Um pacote
  para `26.255.255.255` continua com esse destino quando chega ao peer.
- Se o peer escolhido estiver offline, sem autenticação, fora das redes
  autorizadas ou bloqueado pela política de tráfego, o broadcast é
  descartado. Ele não é distribuído aos demais nem guardado para entrega
  futura. Filas ou janelas de transporte cheias também podem descartar frames.
- Unicast e multicast continuam seguindo suas regras normais.
- A alteração vale para novos quadros de saída, sem reconectar a VPN. Ela não
  retira quadros que já foram colocados em filas antes da mudança.

Isso também direciona os pedidos ARP em broadcast, inclusive pedidos pelo
endereço de outro dispositivo, ao peer escolhido. A validação normal no
recebimento pode descartar esses pedidos. Portanto, restringir o destino pode
impedir a resolução ARP dos outros peers.

O broadcast é distribuído pelo cliente local, com um envio por conexão. Cada
envio usa TCP direto, UDP direto ou relay, conforme o canal daquele peer. O
relay encaminha a transmissão da conexão correspondente; o cliente não
manda um único pacote ao servidor para pedir que ele distribua à rede toda.
Um broadcast recebido vai para a interface local e não é retransmitido
automaticamente aos outros peers.

Com a restrição removida, os destinatários podem abranger todas as redes
compartilhadas pelo perfil. O quadro de broadcast não indica uma rede privada
específica.

No desktop, a mesma configuração está em **Configurações → Broadcasts de
saída**, com os botões **Aplicar destino de broadcast** e **Enviar a todos os
pares**. Esses controles salvam imediatamente; salvar ou descartar outras
preferências não sobrescreve o destino de broadcast.

## Redes privadas e senhas

As senhas são lidas de arquivos UTF-8, nunca de um argumento contendo a senha.
O arquivo é limitado a 4 KiB; a senha deve ter entre 6 e 256 caracteres. Uma
quebra de linha final LF ou CRLF é removida.

Exemplo no Bash, com entrada oculta e arquivo privado:

```sh
umask 077
read -r -s -p 'Senha da rede: ' senha_rede
printf '\n'
printf '%s\n' "$senha_rede" > senha-rede.txt
unset senha_rede

openrad create 'Amigos LAN' --password-file senha-rede.txt
```

No outro dispositivo:

```sh
openrad join 'Amigos LAN' --password-file senha-rede.txt
```

Transfira a senha por um meio apropriado e remova as cópias temporárias quando
não forem mais necessárias. O serviço não salva as senhas de rede no perfil.

## Administração e recuperação

Os comandos de administração exigem o papel correspondente na rede:

```sh
openrad grant-admin 'Amigos LAN' 123456
openrad revoke-admin 'Amigos LAN' 123456
openrad kick 'Amigos LAN' 123456
openrad leave 'Amigos LAN'
openrad delete 'Amigos LAN' --yes
```

Para selecionar a rede nesses comandos, use nome exato ou ID de `networks`.
Se o nome for ambíguo, use o ID. `kick` remove a associação; não constitui um
banimento permanente. `delete` remove a rede para todos e exige `--yes`.
Deixar a rede pode ser recusado quando você é o último administrador; nesse
caso, conceda administração a outro membro ou exclua a rede.

Para recuperar conexões ou repetir a configuração da interface:

```sh
openrad retry-peers
openrad retry-interface
```

`retry-peers` solicita novas tentativas com a política de recuperação do
serviço. `retry-interface` repete a configuração da TAP após você resolver
o problema de driver ou autorização. Uma operação de rede que expirou pode
ter chegado ao servidor; confira `status` e `networks` após a reconexão antes
de repetir uma alteração cujo resultado ficou incerto.

## Referência rápida de comandos

Os argumentos em maiúsculas são marcadores para os valores que você fornece.

| Comando | Finalidade |
| --- | --- |
| `init --node-name NOME` | Registrar e salvar uma identidade nova. |
| `init --identity ARQUIVO` | Importar uma identidade existente. |
| `start [--no-tap]` | Iniciar ou reutilizar o serviço. |
| `status` | Consultar o estado da sessão e da interface. |
| `stop` | Encerrar a sessão compartilhada. |
| `networks` | Listar redes, IDs e papéis. |
| `peers` | Listar peers, RIDs, IPs, estados e transportes. |
| `search [CONSULTA] [--cursor NÚMERO]` | Buscar redes públicas e continuar a paginação. |
| `join REDE [--password-file ARQUIVO]` | Entrar em uma rede pública ou privada. |
| `create REDE --password-file ARQUIVO` | Criar uma rede privada. |
| `leave REDE` | Sair de uma rede. |
| `delete REDE --yes` | Excluir uma rede administrada. |
| `kick REDE RID` | Remover um membro. |
| `grant-admin REDE RID` | Conceder administração. |
| `revoke-admin REDE RID` | Revogar administração. |
| `retry-peers` | Solicitar recuperação dos canais de peers. |
| `retry-interface` | Repetir a configuração da interface. |
| `ping RID` | Medir RTT pelo canal autenticado de um peer. |
| `rename NOME` | Mudar o nome do dispositivo preservando a identidade. |
| `broadcast-peer [DESTINO] [--all]` | Consultar, restringir ou restaurar os broadcasts de saída. |
| `force-relay true\|false` | Ativar ou desativar o uso exclusivo de relay. |

Aliases disponíveis: `provision` para `init`, `public-networks` para `search`,
`create-network` para `create` e `delete-network` para `delete`.

## JSON e códigos de saída

Para scripts, use `--json` e verifique também o código de saída do processo:

```sh
openrad --json status
openrad --json peers
openrad --json broadcast-peer
```

A resposta tem os campos `ok`, `message` e `data`. Exemplo ilustrativo de
consulta de um destino salvo:

```json
{"ok":true,"message":"Outgoing broadcasts: RID 123456","data":{"broadcast_peer":123456}}
```

Com a distribuição normal habilitada, `data.broadcast_peer` é `null`.
As chaves e mensagens do JSON permanecem estáveis independentemente do idioma
selecionado. Uma execução bem-sucedida retorna `0`; uma falha retorna um código
não zero. Erros anteriores à obtenção de uma resposta podem aparecer somente
em stderr, sem produzir JSON em stdout.

## Diagnóstico

O perfil contém o log do serviço em `service.log` e os diagnósticos de conexão
no diretório `diagnostics/`. Os logs de inicialização ficam no diretório de
logs do OpenRad, normalmente `~/.local/share/OpenRad/logs` no Linux;
`OPENRAD_LOG_DIR` permite substituí-lo. Eles podem conter identificadores,
endereços e nomes de redes, mas não devem conter senhas, chaves de sessão ou
conteúdo dos pacotes.

| Situação | Ação |
| --- | --- |
| Serviço parado | Execute `start` e acompanhe `status`. |
| Interface indisponível | Resolva a autorização no Linux ou o driver/elevação no Windows e execute `retry-interface`. |
| Nome/IP do destino de broadcast não pode ser selecionado | Aguarde a lista de peers carregar, consulte `peers` ou informe um RID. |
| Nome/IP do destino é ambíguo | Informe o RID do dispositivo correto. |
| Broadcasts não chegam aos outros peers | Consulte `broadcast-peer`; use `broadcast-peer --all` para restaurar a distribuição. |
| Peer selecionado não recebe | Confira conexão, associação autorizada e política de tráfego; os frames não ficam guardados para depois. |
| Resolução ARP de outros peers falha no modo restrito | Restaure todos os destinatários ou considere o direcionamento dos pedidos ARP ao peer escolhido. |
| Serviço rejeita um comando do CLI novo | Encerre o serviço antigo e inicie os binários da mesma compilação nova. |

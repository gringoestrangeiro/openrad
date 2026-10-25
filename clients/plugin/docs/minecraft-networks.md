# Redes públicas de Minecraft por idioma e número

O plugin consulta o catálogo público pelo controle autenticado do Radmin/OpenRad. Os nomes e números vêm do serviço, sem uma lista fixa de redes inventadas. Todos os idiomas e números retornados são catalogados, incluindo novas redes em consultas futuras.

```yaml
vpn:
  minecraft-networks:
    enabled: true
    select: [all]
    max-networks: 256
```

`enabled: true` habilita a consulta. `select: []` apenas lista, sem entrar automaticamente em redes públicas. Selecione uma ou mais opções:

| Opção | Redes selecionadas |
| --- | --- |
| `all` | Todas as redes Minecraft retornadas pelo catálogo, de todos os idiomas. |
| `all-ptbr` | Todas as redes de português, independentemente do número. |
| `ptbr-1`, `ptbr-2`, `ptbr-3`, … | Cada rede numerada de português. |
| `all-english`, `english-1`, `english-2`, … | Grupo inglês ou redes individuais. |
| `all-vietnamese`, `vietnamese-12` | Grupo vietnamita ou uma rede individual. |
| `all-russian`, `russian-30` | Grupo russo ou uma rede individual. |
| `all-chinese`, `chinese-2` | Grupo chinês ou uma rede individual. |
| `all-spanish`, `spanish-2` | Grupo espanhol ou uma rede individual. |
| `all-japanese`, `japanese-1` | Grupo japonês ou rede individual; grupos sem número usam `1`. |

Os mesmos formatos existem para os demais idiomas presentes: `korean`, `french`, `german`, `italian`, `turkish`, `indonesian`, `polish`, `hungarian`, `serbian`, `latvian`, `norwegian`, `lithuanian`, `danish`, `estonian`, `swedish`, `slovak`, `slovenian`, `czech`, `croatian`, `dutch`, `finnish`, `romanian`, `greek`, `bulgarian`, `ukrainian`, `hebrew`, `arabic`, `persian` e `thai`. Idiomas novos ganham um alias derivado do nome real; consulte a listagem para saber o seletor exato. Resultados como Minecraft Dungeons/Legends também aparecem com seletores próprios e entram em `all`; a compatibilidade de gameplay deste plugin continua sendo Minecraft Java.

Exemplo de seleção combinada:

```yaml
select: [ptbr-1, ptbr-2, ptbr-3, english-2, all-spanish]
```

Seletores sobrepostos não duplicam a entrada. Um seletor sem correspondência gera aviso e não cria uma rede. `max-networks` limita a expansão entre 1 e 512; se exceder esse valor, a seleção automática é recusada com status `FAILED`, sem truncamento silencioso. As redes explícitas em `vpn.networks` continuam funcionando e suas senhas/ACLs têm precedência sobre um seletor que alcance o mesmo nome.

## Ver e aplicar

- `/orb minecraft`: catálogo paginado, dez redes por página.
- `/orb minecraft 2`: segunda página de todas as redes.
- `/orb minecraft all-ptbr`: todas as redes de português.
- `/orb minecraft all-english 2`: segunda página do inglês.
- `/orb minecraft ptbr-3`: nome real, membros informados e participação na sessão dessa rede.
- `/orb status`: estado do catálogo, quantidade selecionada e avisos.
- `/orb reload`: valida a configuração, reconecta, consulta o catálogo novamente e aplica os seletores. Encerra os fluxos anteriores.

O arquivo `plugins/OpenRadPlugin/minecraft-networks.txt` guarda o catálogo completo em UTF-8, com seletor, quantidade informada de membros e nome real. A quantidade é a de membros da rede VPN informada pelo serviço; não representa jogadores Minecraft online. A consulta é refeita em cada attachment/reconexão e tem limites de páginas, registros e tempo. Ela roda fora da thread principal do Paper.

Entrar numa rede pública depende do serviço: redes cheias ou que recusam o dispositivo aparecem nos avisos, enquanto outras redes continuam sendo processadas. Selecionar `all` não garante aprovação em todas. Catálogo, seleção e participação real são estados diferentes. Retirar um seletor revoga sua autorização local após reload, mas não executa leave de participações persistentes no serviço. A ACL global continua valendo e os peers precisam autenticar o canal cifrado.

`vpn.max-peers: 0` tenta conectar a todos os membros elegíveis das redes selecionadas, dentro do roster limitado a 4096 membros. Um valor positivo entre 1 e 4096 limita os canais simultâneos. A entrada é gradual, com até 16 negociações concorrentes; os canais já autenticados são mantidos. Uma varredura de muitos peers pode demorar. Peers ainda não tentados vêm antes das repetições, e ofertas recebidas têm prioridade. Falhas recebem espera exponencial de 5 segundos até 10 minutos. Computadores offline, sem resposta, bloqueados por firewall/NAT ou incompatíveis podem continuar sem receber o anúncio. Isso não publica o servidor para usuários de redes que não foram selecionadas.

A opção `0` remove o corte de 64 computadores, mas não remove os limites de jogadores, memória, banda ou do protocolo. Não há uma garantia de que todos verão o servidor ao mesmo tempo. Apenas canais autenticados ativos recebem anúncios LAN. `/orb status` distingue membros elegíveis, membros já tentados, autenticações bem-sucedidas nesta sessão, canais conectados agora e retentativas em espera.

## Endereço LAN e `getsockopt`

`online-mode=false` controla a autenticação Minecraft; ele não corrige uma conexão TCP recusada. Se o LANBroadcaster anuncia um endereço de interface como `100.x.x.x:25575`, mas o Paper escuta somente em `127.0.0.1:25575`, esse endereço não aceita conexões.

Para permitir acesso direto pelas interfaces da máquina, deixe `server-ip=` vazio em `server.properties` e reinicie o Paper. Mantenha `bridge.backend-host: 127.0.0.1`; o backend do plugin continua sendo loopback. Neste ambiente, a porta física é 25575 e a porta anunciada pelo túnel é 25565. No mesmo computador, use `127.0.0.1:25575`; pela VPN interna do plugin, use o VIP mostrado por `/orb status` com porta 25565. Acesso remoto às interfaces físicas também depende do firewall e da rede correspondente.

## Todas as opções disponíveis

Use `/orb minecraft options` e `/orb minecraft options 2` para percorrer a lista completa de valores aceitos. O arquivo `plugins/OpenRadPlugin/minecraft-network-options.yml`, gerado junto da configuração, contém cada seletor global, por idioma e individual. Copie os itens desejados para `vpn.minecraft-networks.select`; o arquivo gerado é uma referência, não substitui `config.yml`. A lista acompanha o catálogo a cada reconexão. `/orb peers [pagina]` também foi paginado para não despejar milhares de linhas no chat.

## Mensagens de conexão dos peers

`Minecraft catalog: 114 networks; 114 selected` indica catálogo carregado e seleção expandida. Isso não equivale a 114 jogadores ou a todos os peers autenticados.

- `REMOTE_CLOSED` / antigo `EOFException`: o fluxo terminou antes de completar a operação. Não identifica sozinho a causa ou o lado responsável.
- `TIMEOUT`: uma tentativa excedeu o prazo sem resposta suficiente.
- `CONNECTION_REFUSED`: o endpoint TCP recusou a conexão.
- `PROTOCOL_REJECTED`: alguma validação do protocolo ou autenticação foi recusada. Não significa necessariamente erro no YAML e não é motivo para desativar a validação de segurança.
- `IO_FAILURE`: outro erro de transporte; a contagem não expõe mensagens que possam conter dados privados.

As falhas não são ignoradas: continuam nos contadores do status e no `/orb status`. O resumo de console `VPN discovery` pode ser ativado com `vpn.discovery-log-interval-minutes` (padrao `0`, desativado). O build 0.2.3-dev substitui as linhas individuais repetidas de cada peer por esse resumo. Logs `joined the game` e `logged in` indicam entrada no Minecraft; são eventos diferentes dessas tentativas VPN.

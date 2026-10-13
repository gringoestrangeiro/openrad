# OpenRAD em Python para Linux

Cliente independente em **três arquivos Python**, adaptado dos projetos OpenRAD
Python e Rust sob licença MIT. Não precisa de Wine, executáveis do Radmin, Rust
ou de acessar as pastas originais em tempo de execução.

Há dois modos sem privilégios: a classe **`OpenRad`**, importável e assíncrona,
troca pacotes diretamente em memória; o comando `run` oferece um servidor UDP
em `127.0.0.1`, com uma porta alta escolhida pelo sistema. Ambos transportam
IPv4, Ethernet e UDP de aplicação pela rede Radmin, usando canais de peers
autenticados e criptografados. Funcionam como usuário comum, sem sudo e sem
TUN/TAP. A API por importação não abre nenhum socket UDP.

## Importar e usar com asyncio

Os três módulos podem ficar junto da sua aplicação ou ser instalados num
ambiente virtual. O pacote inclui o comando `openrad` e os fixtures do self-test:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install .
# Opcional, para AES acelerado: .venv/bin/python -m pip install '.[fast]'
```

Este servidor de eco roda dentro do event loop da sua aplicação e não usa o
protocolo UDP local. A porta 49000 abaixo é uma **porta virtual**, dentro dos
pacotes enviados pela rede Radmin:

```python
import asyncio
from openrad import OpenRad

async def main():
    async with OpenRad(
        data_dir="./profiles/aplicacao",
        networks=["Minecraft [Português 15]"],
        force_relay=False,
    ) as vpn:
        print(vpn.status()["vip"])
        async with vpn.subscribe("udp", ports=[49000]) as packets:
            async for packet in packets:
                await vpn.send_udp(
                    packet.source, packet.destination_port, packet.source_port,
                    packet.data, peer=packet.peer,
                )

asyncio.run(main())
```

Para enviar de outro módulo, importe a mesma classe e passe a instância em uso:

```python
from openrad import OpenRad

async def enviar(vpn: OpenRad, destino: str, dados: bytes):
    peer = await vpn.wait_peer(destino, timeout=45)  # VIP ou RID
    return await vpn.send_udp(peer["vip"], 49001, 49000, dados, peer=peer["rid"])
```

`await vpn.start()` inicia o motor e aguarda as redes solicitadas. Também existe
`await vpn.start(wait=False)` seguido de `await vpn.wait_online()`. As threads
internas de controle e transporte continuam rodando enquanto sua aplicação
executa outras tarefas; registro, espera de rede e encerramento não bloqueiam
o event loop. `await vpn.close()` encerra o motor, aguarda as threads, libera o
perfil e acorda os consumidores com `ChannelClosed`. O contexto `async with`
faz isso automaticamente, inclusive quando seu código lança uma exceção.

Importar ou construir `OpenRad(...)` não abre sockets, cria diretórios, inicia
threads nem altera sinais. Cada instância pertence a um único event loop e a
uma única sessão; após fechar, construa outra. Instâncias simultâneas precisam
de perfis e identidades diferentes. Uma segunda instância com o mesmo perfil
falha sem afetar a primeira. Por padrão, `networks=None` reutiliza a seleção
salva; `networks=[]` aceita todas as redes já associadas à identidade.

As principais operações da API são:

| Operação | Uso |
| --- | --- |
| Estado e membros | `vpn.status()`, `vpn.peers()`; snapshots síncronos em memória |
| Conectar | `await vpn.connect(rid)`, `await vpn.wait_peer(vip_ou_rid)` |
| Redes | `await vpn.join(nome, password=None)`, `create(nome, senha)`, `leave(nome)`, `delete(nome)` |
| Seleção de redes | `await vpn.select_networks([nome, ...])`; seleciona redes sem repetir joins |
| Busca e relay | `await vpn.search(query)`, `await vpn.set_force_relay(True)` |
| Envio | `await vpn.send_udp(destino, porta_origem, porta_destino, bytes)`, `send_ipv4(bytes)`, `send_ethernet(bytes)` |
| Recebimento | `vpn.subscribe("udp"/"ipv4"/"ethernet", ports=(), peers=(), capacity=256)` |
| Quadro Ethernet padrão | `await vpn.receive(timeout=None)` |

Cada assinatura é um `PacketChannel`, com `await channel.receive(timeout=...)`
ou `async for packet in channel`. Um `Packet` imutável contém `kind`, `peer`,
`data`, `sequence`, `source`, `destination`, `source_port` e `destination_port`.
Nos canais Ethernet/IPv4, `data` é o quadro/pacote completo; no canal UDP é
somente o payload da aplicação. Campos IP/portas ficam `None` quando não se
aplicam. As três representações de um mesmo quadro compartilham `sequence`.

Assinaturas separadas recebem suas próprias cópias lógicas dos eventos, usando
os mesmos bytes imutáveis. Vários consumidores de uma mesma assinatura dividem
a fila. Os filtros vazios aceitam todos os peers/portas; portas só podem ser
filtradas em canais UDP. Cancelar uma espera não fecha o cliente ou a assinatura.
`timeout=0` consulta somente o que já está na fila; a ausência de pacote gera
`TimeoutError`. Fechar a assinatura termina seu `async for`.

O canal de memória usa uma notificação por lote ao event loop, filas limitadas
e entrega em lotes de até 64 quadros. Não faz polling periódico para receber
pacotes. O padrão é 256 pacotes por assinatura e 1024 quadros na ponte entre
threads, ajustáveis por `receive_queue`, `bridge_queue` e `capacity`. Um
consumidor lento descarta os novos eventos da própria fila e incrementa
`channel.dropped`; isso não bloqueia as outras assinaturas. As contagens também
aparecem em `status()["counters"]`. Há até 64 assinaturas por instância.

Sem assinaturas explícitas ou uso de `receive()`, o receptor padrão guarda seu
backlog limitado como quadros brutos: não acorda o asyncio nem cria objetos
`Packet`. O primeiro uso converte o backlog preservando ordem e `sequence`.
Assinaturas ativas decodificam somente as camadas solicitadas. Autenticação,
validação, keepalives e respostas ARP/ICMP continuam no motor.

Envios retornam `{"ok": True, "queued": N, "bytes": ...}` ao entrar na fila;
não são confirmação de entrega remota. Filas cheias, destinos desconectados e
pacotes inválidos geram `ValueError`. Comandos aceitam `timeout=40`; cancelar ou
expirar uma chamada de comando não desfaz uma operação já enviada ao servidor.

`OpenRad(..., local_udp=True, udp_port=0)` disponibiliza **também** o protocolo
ORAD no localhost, servido pelo mesmo event loop. Você pode combinar programas
que usam `LocalClient` com assinaturas em memória, sem iniciar outro motor. O
padrão `local_udp=False` não cria socket UDP, `api.json` nem interface TAP.

## Executar

Requer Python 3.10 ou mais recente em Linux. A biblioteca padrão é suficiente.
Se PyCryptodome já estiver instalado, o cliente usa sua implementação de AES
para acelerar a criptografia; o fallback Python também é testado.

```bash
python3 openrad.py run --network 'Minecraft [Português 15]' --force-relay
```

`--mode auto`, o padrão, verifica `CAP_NET_ADMIN`. Sem essa capacidade ele usa
o servidor UDP. Para solicitar explicitamente esse modo:

```bash
python3 openrad.py run --mode server --force-relay --network 'Minecraft [Português 15]'
# --no-tun também seleciona o modo server.
```

O processo mostra o endereço UDP, a porta, o VIP e o caminho do arquivo
`api.json`. O token de acesso fica nesse arquivo privado e não aparece no log.
O perfil padrão é `$XDG_STATE_HOME/openrad-python`, ou
`~/.local/state/openrad-python` quando a variável não está definida.

O primeiro uso cadastra uma identidade e a salva para reutilização. Cadastros
com resposta ambígua não são repetidos automaticamente. Redirecionamentos
autenticados do servidor têm limite de tentativas. Você também pode importar
uma identidade sua e inativa para um perfil novo:

```bash
python3 openrad.py --data-dir ./profiles/meu-cliente run --identity /caminho/identity.json
```

O perfil tem permissão `0700`; identidade, configurações e descoberta da API
têm permissão `0600`. Cada perfil permite somente um motor ativo. Senhas de
redes privadas não são salvas nas configurações. O perfil é separado do OpenRAD
Rust e não controla o serviço existente desse programa.

## Comandos

Execute os comandos abaixo em outro terminal, enquanto `run` estiver ativo.
Todos conversam com o motor através do UDP local.

```bash
python3 openrad.py status
python3 openrad.py peers
python3 openrad.py search 'Minecraft'
python3 openrad.py join 'Minecraft [Português 15]'
python3 openrad.py join 'Minha rede privada' --private
python3 openrad.py create 'Minha rede privada'
python3 openrad.py leave 'Minha rede privada'
python3 openrad.py delete 'Minha rede privada'
python3 openrad.py connect RID_DO_PEER
python3 openrad.py force-relay true
python3 openrad.py ping VIP_OU_RID --count 3
python3 openrad.py stop
```

`--private` e `create` pedem a senha sem exibi-la. Também existe
`--password-file /caminho/arquivo` para automação. Coloque `--data-dir` antes do
subcomando para usar um perfil diferente. `delete` envia a exclusão ao servidor
e exige que sua identidade tenha autorização de administrador nessa rede.

`force-relay true` impede configuração direta nas duas direções e retira
conexões diretas existentes. As conexões entre peers usam relay TCP; sem essa
opção, o cliente também tenta TCP direto. O transporte direto ENet/UDP do Radmin
não foi portado nesta versão. No CLI, a **API local permanece UDP** em ambos os
casos; `OpenRad` oferece a API em memória, independentemente do transporte.

O motor mantém heartbeat do controle e dos peers, reconecta com espera
progressiva, limita as conexões simultâneas e acompanha entrada, saída e estado
dos membros. Uma conexão com falha aparece em `peers` e não derruba o motor.
Conexões solicitadas por `connect` recebem prioridade quando a capacidade está
ocupada. `--passive` desativa conexões automáticas de saída; `connect` continua
disponível. `--max-peers` aceita qualquer inteiro positivo, com padrão 32.
Por exemplo, `--max-peers 400` permite testar centenas de conexões. O número
de negociações simultâneas cresce com esse limite, até 64; o padrão continua
com quatro negociações. O I/O Linux usa `poll()`, aceitando descritores acima
de 1023 sem root. Um limite baixo de arquivos abertos pode ser aumentado até
o hard limit do usuário, por exemplo com `ulimit -n 4096`.

## Usar UDP de aplicação sem TUN

Você pode testar com dois clientes seus na mesma rede. No cliente B:

```bash
python3 openrad.py --data-dir ./profiles/b run --force-relay --network 'Minecraft [Português 15]'
# Em outro terminal:
python3 openrad.py --data-dir ./profiles/b udp-echo --port 49000
```

No cliente A:

```bash
python3 openrad.py udp-send VIP_OU_RID_DO_B 49000 'Olá pelo UDP local' --source-port 49001
```

O caminho é: programa A → UDP localhost A → canal criptografado entre os peers
→ UDP localhost B → programa B. A resposta percorre o caminho inverso.
`udp-send` verifica os bytes exatos da resposta; `--wait 0` apenas envia.

Programas que usam a API podem trocar seus próprios pacotes sem interface de
rede no kernel. Jogos que esperam um adaptador de rede do sistema precisam de
uma integração com a API ou do TAP opcional. O modo server não instala rotas
nem atribui o VIP às interfaces do computador.

## SDK síncrono para o servidor UDP local

`LocalClient` está no próprio `openrad.py`, sem outro módulo de SDK. O exemplo
abaixo assina uma porta UDP virtual e responde aos pacotes recebidos:

```python
from openrad import LocalClient, default_profile

with LocalClient.from_profile(default_profile()) as client:
    client.hello(subscribe=["udp"], ports=[49000])
    while True:
        message = client.receive(timeout=60)
        source, destination, source_port, destination_port, data = client.udp_payload(message)
        client.send_udp(source, destination_port, source_port, data, peer=message["peer"])
```

`data` é `bytes`: NUL, bytes não UTF-8 e payload vazio são suportados. Use
`send_ipv4(packet, peer=rid)` para um pacote IP completo ou
`send_ethernet(frame, peer=rid)` para Ethernet. O VIP e o MAC de origem devem
ser os atribuídos à sua identidade; consulte `status()`. O MTU é 1500 e o limite
do payload UDP de aplicação é 1472 bytes. ARP replies têm a exceção de origem
descrita abaixo. Fragmentos ficam disponíveis nas
interfaces IPv4/Ethernet; o SDK de UDP não faz remontagem de fragmentos.

Uma resposta de envio confirma a entrada na fila de transmissão. Para verificar
a entrega, receba um pacote de resposta da aplicação remota. `ping` faz isso
com identificador, sequência, checksum e payload ICMP correlacionados.

`LocalClient` mantém a assinatura durante chamadas longas de `receive()`. A
mesma instância é síncrona e deve ser usada por uma thread por vez; crie clientes
separados para consumidores concorrentes.

## Protocolo UDP local ORAD v1

Cada datagrama tem um cabeçalho binário de **40 bytes**, seguido do payload.
Inteiros usam ordem de rede, big-endian. A descrição em Python é
`struct.Struct("!4sBBHIQI16s")`.

| Offset | Tamanho | Campo |
| --- | --- | --- |
| 0 | 4 | Magic ASCII `ORAD` |
| 4 | 1 | Versão: `1` |
| 5 | 1 | Opcode |
| 6 | 2 | Flags: `0` em requisições; máscara `0x0001` indica erro em respostas |
| 8 | 4 | ID de requisição; deve ser não zero |
| 12 | 8 | RID do peer; `0` seleciona pela rota/destino |
| 20 | 4 | Tamanho exato do payload |
| 24 | 16 | Token binário de `api.json`, decodificado de hexadecimal |

| Opcode | Operação | Payload |
| --- | --- | --- |
| `0x01` | HELLO / assinatura | JSON UTF-8 com `subscribe`, `ports`, `peers` |
| `0x02` | STATUS | Vazio |
| `0x03` | PEERS | JSON: `cursor` e `limit` (1–64) |
| `0x04` | COMMAND | JSON: `op` e campos do comando |
| `0x05` | BYE | Vazio; libera a assinatura |
| `0x10` | SEND_ETHERNET | Quadro Ethernet completo, até 1514 bytes |
| `0x11` | SEND_IPV4 | Pacote IPv4 completo, até 1500 bytes |
| `0x12` | SEND_UDP | IPv4 de destino (4 bytes), porta de origem (`u16`), porta de destino (`u16`), dados |
| `0x40` | RX_ETHERNET | Quadro Ethernet recebido |
| `0x41` | RX_IPV4 | Pacote IPv4 recebido |
| `0x42` | RX_UDP | IPv4 de origem, IPv4 de destino, porta de origem (`u16`), porta de destino (`u16`), dados |

As respostas usam `opcode | 0x80`, o mesmo ID da requisição e JSON com `ok`.
Eventos RX incluem o RID autenticado no cabeçalho e uma sequência de evento;
as representações Ethernet, IPv4 e UDP de um mesmo quadro compartilham essa
sequência. O payload RX_UDP começa com `struct.Struct("!4s4sHH")`, com 12 bytes.

Exemplo de HELLO:

```json
{"subscribe":["ethernet","ipv4","udp"],"ports":[49000],"peers":[]}
```

Listas vazias de `ports` e `peers` aceitam todos os destinos UDP e peers da
assinatura. O filtro de portas afeta somente RX_UDP. Pode haver até 32 clientes;
uma assinatura ociosa expira em 120 segundos. Qualquer requisição renova a
assinatura. Envie STATUS periodicamente quando implementar outro cliente.

COMMAND aceita `search` (`query`, `cursor`), `join` (`name`, `password` opcional),
`create` (`name`, `password`), `leave`/`delete` (`name` ou `network_id`),
`connect` (`rid`), `force_relay` (`enabled`, booleano) e `stop`. Operações de rede
são serializadas para evitar respostas ambíguas de autenticação privada.

O SDK repete requisições com o mesmo ID a cada 500 ms. A API guarda respostas
de pacotes por 10 segundos e comandos por 90 segundos: repetir o mesmo datagrama
nessa janela não envia o pacote ou comando duas vezes. Não reutilize um ID com
conteúdo diferente. Comandos de rede podem levar até 35 segundos. O limite do
datagrama da API é 64000 bytes e pedidos JSON têm limite de 16384 bytes.

`peer=0` escolhe conexões por VIP/MAC de destino. Broadcast e multicast IPv4,
Ethernet e IPv6 bruto são enviados **uma vez para cada peer elegível conectado**
das redes selecionadas; compartilhar várias redes não duplica um peer. Isso
inclui ARP requests/replies em broadcast, mesmo quando o IP de destino ARP é
o VIP de um único membro. Um RID explícito restringe o envio a esse peer.
Membros offline, pendentes de aprovação ou fora da seleção são excluídos, mesmo
que ainda exista uma conexão em processo de encerramento. O limite configurado
de conexões (`max_peers`, 32 por padrão, sem teto fixo) também se aplica ao fan-out;
pacotes não são guardados para membros desconectados.

O MAC Ethernet e o IP de origem de IPv4/ARP requests continuam vinculados ao
canal autenticado. **ARP replies (`opcode=2`) têm uma exceção intencional nos
dois sentidos:** `send_ethernet()` e o recebimento aceitam o MAC de origem
Ethernet, o sender MAC ARP e o sender IP ARP arbitrários. Isso vale para replies
normais, em broadcast e gratuitous, sem reescrever seus bytes. Os limites de
tamanho, formato ARP, roteamento de destino e associação à rede continuam
valendo. Em um `Packet` Ethernet, `peer` é o RID do canal, mesmo quando o frame
ARP anuncia outro remetente.

Essa política é a mesma no modo em memória, no UDP local e no TAP. Quadros
recebidos não são retransmitidos para outros peers. Sem TAP, o motor responde
ARP requests e ICMP echo para seu próprio VIP.

## TAP opcional e testes

`--mode tap` usa `/dev/net/tun` e `ip` quando o processo **já possui**
`CAP_NET_ADMIN`. O programa não chama sudo e não solicita elevação. No modo
auto, falha de criação do TAP provoca fallback para o servidor UDP. A API UDP
continua disponível quando o TAP está ativo.

```bash
python3 openrad.py self-test
python3 -S openrad.py self-test  # inclusive sem PyCryptodome/site-packages
```

Os testes usam vetores sintéticos de criptografia, prova de senha privada,
framing TCP parcial, autenticação da API, rejeição de pacotes alterados,
deduplicação e troca UDP real em localhost sobre um canal TCP criptografado.
Também testam três instâncias importáveis com SH/TCP criptografado, todos os
tipos de pacote, broadcast/multicast para vários peers, ARP replies de origem
arbitrária na saída e entrada, importação por módulos externos, filas cheias,
cancelamento, timeout, consumidores concorrentes e liberação de perfis/threads.

As verificações contra o serviço real estão documentadas em `VALIDATION.md`.
Veja [VALIDATION.md](VALIDATION.md) para a validação na rede pública real.

## Benchmark de centenas de peers

O ensaio offline usa conexões TCP e autenticação SH reais, `PeerChannel`,
`PureCC`, `PeerWorker.run`, `Engine` e a ponte de memória. Somente o serviço
externo de coordenação é substituído. O gerador roda em outro processo e não
entra no CPU/RSS do cliente. A carga contém 10% ARP e 30% de cada tamanho de
quadro UDP Ethernet: 60, 500 e 1500 bytes. `--rate` é a taxa por peer e por
direção, e as respostas ARP entram na contagem de L2.

```bash
python3 tools/benchmark.py --peers 400 --seconds 60 --rate 5 --output reports/load.json
python3 tools/benchmark.py --peers 400 --seconds 10 --rate 5 --subscribers udp
python3 tools/benchmark.py --peers 400 --seconds 5 --rate 5 --profile reports/load.prof --profile-scope loop
python3 -m pstats reports/load.prof
```

Os resultados de carga sem profiler são os números de desempenho. CPU é
`process_time / monotonic_elapsed`, somando todas as threads: 0,5 corresponde
a metade de um core estimado. RSS/HWM vêm de `/proc/self/status`. O JSON também
registra perdas, entrega aos assinantes, conexões mantidas e cumprimento da
taxa solicitada; manter peers ociosos não comprova throughput.

Para repetir o teste nas oito redes públicas Minecraft [Português 1–8], com
perfil separado persistente, conexão por relay e uma janela de 60 segundos:

```bash
python3 tools/live_benchmark.py --force-relay --seconds 60
```

Esse último comando acessa o serviço real e entra nas oito redes. Os detalhes
e os limites medidos, incluindo o backend PyCryptodome, estão em `VALIDATION.md`.

| Arquivo | Responsabilidade |
| --- | --- |
| `openrad.py` | CLI, servidor UDP, SDK e testes integrados |
| `radvpn.py` | Motor, peers, sockets, perfis e TAP Linux opcional |
| `radproto.py` | Criptografia, autenticação, TLV, redes e pacotes |

Créditos: estrangeiro e asimplestray, conforme [LICENSE](LICENSE), e os
protótipos em `openrad/work` e a implementação em `openrad-rust/src`.

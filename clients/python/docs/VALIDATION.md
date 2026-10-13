# Validação em Linux — 2 de outubro de 2026

A meta foi verificada na rede pública **Minecraft [Português 15]**. Dois
clientes Python com identidades próprias trocaram pacotes usando somente a
API UDP de localhost e os canais criptografados de relay do Radmin.

O ensaio registrado começou às **18:12:25, America/Sao_Paulo**. O resultado
detalhado está em `reports/live-udp-verification.json`, um arquivo privado
`0600`, excluído pelo `.gitignore`. Ele registra checks, hashes dos payloads,
tempos de ida e volta, metadados dos processos e estados finais. Não contém
tokens da API, senhas ou chaves de sessão.

## Resultado real

| Verificação | Resultado |
| --- | --- |
| Entrada na rede pública com ambos os clientes | Passou |
| Usuário efetivo | `uid=1000` nos dois processos |
| Privilégio `CAP_NET_ADMIN` | Ausente nos dois processos |
| Descritores para `/dev/net/tun` | Nenhum |
| Modo automático sem privilégios | Selecionou `server` no cliente B |
| Modo explicitamente sem túnel | `--no-tun` no cliente A |
| Force relay | Ativo nas duas direções |
| Ping pelo protocolo UDP local | 3/3 respostas em cada direção |
| UDP binário de aplicação | 40 trocas de ida e volta, todas exatas |
| Tamanhos testados | 0, 1, 7, 32, 64, 96, 255, 1000 e 1472 bytes |
| Conteúdo binário | Incluiu NUL e bytes não UTF-8 |
| Eventos recebidos | Ethernet, IPv4 e UDP, verificados para cada datagrama |
| Broadcast e multicast | Entregues ao peer próprio selecionado |
| Envio pela operação de Ethernet bruto | ICMP enviado e resposta exata recebida |
| Rede privada | Criada e autenticada com a senha correta |
| Senha privada incorreta | Rejeitada pelo servidor |
| Saída e exclusão da rede privada de teste | Passaram; rede de teste removida |
| Controle ao terminar | Ambos `online`, com `error=null` |

As duas identidades de teste (RIDs e IPs virtuais) e suas credenciais
permanecem nos perfis privados e não são publicadas.

As 40 trocas representam **80 datagramas UDP de aplicação** percorrendo a rede
e suas respectivas representações pela API. A mediana de ida e volta observada
nesse ensaio foi **13,74 ms**, com mínimo de **11,57 ms** e máximo de
**237,05 ms**. Esses valores descrevem esse ensaio, não uma garantia de latência.

Além do par controlado, uma execução anterior com conexão automática estabeleceu
31 canais de peers da rede pública por relay e processou tráfego de fundo sem
derrubar o controle. Tráfego com fonte inválida foi descartado. A verificação de
payloads e os testes ativos de aplicação foram feitos entre os dois clientes
próprios; não dependem de um participante desconhecido responder.

## Testes reproduzíveis locais

```bash
python3 openrad.py self-test
python3 -S openrad.py self-test
python3 -m py_compile openrad.py radvpn.py radproto.py
```

**35 testes passam**, tanto com a aceleração AES/MD4 disponível quanto usando
somente a biblioteca padrão. A compilação sintática dos três módulos passa.
Os testes cobrem:

- Vetores fixos de SH, CChannel, encadeamento CBC e troca de chave do Rust.
- AES-256 do fallback Python e rejeição de ciphertext alterado.
- Verificador de senha privada com nomes Unicode, prova de senha e correlação.
- Validação de todo o registro Ethernet antes de entregar qualquer quadro.
- Preservação de registro TCP parcial após timeout e rejeição de tamanho abusivo.
- Checksum UDP, vínculo do VIP de origem e seleção do destinatário.
- Remoção de membro e rede com dois RIDs em um evento, incluindo o próprio nó.
- Bloqueio exclusivo do perfil e permissão privada da identidade.
- Force relay sem consumir vagas com ofertas que ainda não têm ticket de relay.
- UDP local real sobre sockets TCP com SH/CChannel: payload binário, resposta,
  três representações RX, deduplicação e rejeição de tokens/headers inválidos.
- API async em memória com três clientes, SH/CChannel e seis endpoints TCP.
- Broadcast IPv4 limitado/global, multicast IPv4/IPv6 e Ethernet em broadcast,
  com vários destinatários, sem duplicar peers que compartilham várias redes.
- Exclusão imediata de peers fora da seleção ou pendentes de aprovação.
- ARP replies unicast, broadcast e gratuitous com MAC Ethernet, sender MAC ARP
  e sender IP ARP diferentes do canal: aceitos na **saída e na entrada**, intactos.
- ARP requests/IPv4 com origem incorreta e ARP malformado continuam rejeitados.
- Timeout, cancelamento, respostas tardias, vários consumidores, filas cheias
  e encerramento que acorda os consumidores e libera os perfis/threads.
- Inicialização não bloqueia o event loop e seu cancelamento aguarda a thread.
- Importação por módulos temporários separados, sem sockets, arquivos,
  threads nem alteração dos handlers de sinais durante import/construção.
- Modo direto com criação de sockets UDP e TAP proibida durante o teste.
- UDP local opcional junto com assinaturas em memória, inclusive um burst de
  150 pacotes pendentes que precisa de vários lotes para ser entregue.

## API async por importação: ensaios reais da versão 0.2.0

O pacote foi construído e instalado com `pip --no-deps --no-build-isolation`
num diretório temporário. Os módulos externos `sender.py` e `receiver.py`
importaram a instalação, a partir de outro diretório. Os scripts de ensaio
também ficaram fora do projeto; o cliente continua em apenas três `.py`.
O self-test foi executado dessa instalação com `python3 -S`, incluindo a busca
dos fixtures distribuídos pelo pacote.

Dois novos ensaios usaram **somente `OpenRad` e os canais em memória**. A criação
de qualquer `SOCK_DGRAM` foi bloqueada por uma guarda durante toda a execução.
Ambos rodaram com UID 1000, sem `CAP_NET_ADMIN`, sem descritor `/dev/net/tun`
e sem `api.json`. Não houve exceções pendentes no event loop.

O primeiro ensaio ocorreu às **18:55:41–18:55:55, America/Sao_Paulo**:

| Verificação | Resultado |
| --- | --- |
| Entrada na rede pública Minecraft | Três identidades próprias online, sem crash |
| Rede privada temporária | Criada, senha incorreta rejeitada, três membros autenticados |
| Force relay | Canais TCP de relay autenticados entre os três pares |
| UDP binário por importação | 54 ida/volta A↔B e 6 iniciadas por C; bytes exatos |
| Tamanhos A↔B | 0, 1, 2, 7, 31, 255, 511, 1024 e 1472 bytes |
| Representações | UDP, IPv4 e Ethernet correlacionados pelo mesmo `sequence` |
| Broadcast IPv4 | `255.255.255.255` e `26.255.255.255` chegaram a B e C |
| Multicast IPv4 | `224.1.2.3` e `239.255.255.250` chegaram a B e C |
| ARP request em broadcast | Chegou a B e C, mesmo com o target IP de B |
| IPv6 multicast bruto | Chegou a B e C |
| ARP replies OUT e IN | Unicast, broadcast e gratuitous; três campos de origem arbitrários |
| Encerramento | Threads paradas, locks liberados e rede temporária excluída |

Os quatro testes de fan-out não usaram RID explícito: o envio alcançou todas as
duas conexões elegíveis da seleção de teste. A seleção do emissor foi restrita
à rede privada temporária para não mandar pacotes de teste aos outros membros
da rede pública. Os dados da transmissão passaram inteiramente pela API pública
por importação, sem usar `LocalClient` ou o protocolo ORAD.

Nesse ensaio, a mediana de entrada na fila foi **123,50 µs** e o máximo foi
**295,49 µs**. A mediana de ida/volta A↔B foi **16,43 ms**, com mínimo
**13,00 ms** e máximo **237,68 ms**. Uma tarefa concorrente executou 2660 ciclos
de espera de 5 ms; o atraso adicional mediano foi **0,39 ms**, máximo **7,16 ms**.
Esses números são observações da execução com AES acelerado disponível.

O segundo ensaio ocorreu às **18:57:46–18:57:58** e usou A e B associados
**apenas à rede pública Minecraft [Português 15]**. Passaram 20 novas trocas
de ida/volta por importação, incluindo payload vazio e 1472 bytes; a mediana
foi **15,58 ms**. Os três tipos de ARP reply com origens diferentes também
saíram por `send_ethernet()` e foram recebidos intactos pelo outro cliente.
Os frames de teste foram endereçados somente ao peer próprio.

Os registros privados são `reports/live-async-verification.json` e
`reports/live-async-public-verification.json`, ambos `0600` e ignorados pelo Git.
Contêm hashes, tempos e resultados, sem senhas, credenciais ou payloads.
A terceira identidade foi criada no primeiro ensaio; as três identidades
estão paradas e disponíveis para reutilização.

## Limites do ensaio

Os caminhos reais verificados foram **UDP local + relay criptografado** e
**API por importação em memória + relay criptografado**, ambos sem TAP.
O TAP opcional e a negociação de TCP direto estão implementados, mas não foram
usados no ensaio público acima. Esta versão não implementa ENet/UDP direto do
Radmin. O SDK de aplicação UDP trata datagramas sem fragmentação; pacotes
fragmentados podem ser transportados pelas operações IPv4/Ethernet.

Os processos iniciados para o ensaio são encerrados ao concluir a validação.
As identidades e a associação à rede pública ficam salvas para reutilização.
Para repetir a troca entre dois perfis, veja os comandos de `run`, `udp-echo`,
`udp-send` e os formatos binários em [README.md](README.md).

## Otimização e carga de 400 peers — 2026-10-02

Ambiente: Linux 6.18.36, Intel Core i5-11400H, Python 3.14.6 e PyCryptodome
3.23.0. MB são decimais. A estimativa de cores divide CPU de todo o processo
pelo tempo de parede; os processos não foram presos a um core. O gerador de
tráfego offline é outro processo, excluído de CPU/RSS do cliente. Perfis de
cProfile foram feitos separadamente e seus tempos não servem como throughput.

| Ensaio sem profiler | Peers conectados | Quadros L2/s | Cores estimados | RSS máximo |
| --- | ---: | ---: | ---: | ---: |
| Original, 64 peers, 5 quadros/s por peer/direção | 64 | 704 | 0,387 | 44,7 MB |
| Otimizado, mesma carga de 64 peers | 64 | 704 | 0,111 | 42,0 MB |
| Otimizado, 400 peers, mesma taxa, 60 segundos | 400 | 4.400 | 0,515 | 64,6 MB |
| Otimizado, 400 peers com consumidor UDP | 400 | 4.400 | 0,608 | 62,9 MB |
| Otimizado, 400 peers, taxa dobrada, 15 segundos | 400 | 8.799 | 1,165 | 68,0 MB |

A redução de CPU na carga equivalente de 64 peers foi de aproximadamente
71%. A carga controlada mistura ARP e quadros UDP Ethernet de 60/500/1500
bytes, em ambas as direções; as respostas ARP acrescentam tráfego à taxa
solicitada. Os 400 peers ficaram conectados durante toda a janela de 60 s,
sem perdas no transporte, rejeições de fila de transmissão ou erros. O backlog
do receptor implícito sem consumidor descarta novos quadros quando fica cheio,
como esperado; isso não pula a autenticação ou a resposta do motor.
Keepalives foram enviados e respondidos
nos dois sentidos. O consumidor UDP recebeu os 18.000 datagramas previstos em
10 s. A taxa dobrada ultrapassa um core: o limite comprovado aqui é 4.400 L2/s
com 400 peers e menos de 300 MB, não throughput arbitrário.

O original alcançou apenas aproximadamente 1.099 L2/s com 256 peers, usando
1,42 core e sem cumprir a taxa solicitada. A tentativa original de 400 peers
falhou com `filedescriptor out of range in select()`. O wrapper público ainda
tinha o teto 64; o benchmark original usou diretamente o `Engine`, que já
aceitava um limite maior. O wrapper final aceita o limite 400 normalmente.

Mudanças aplicadas individualmente, com self-test após cada uma:

- CBC em um buffer completo, com ciphers independentes por direção reutilizados
  durante a cadeia. Rekey, edições de IV e alternância com fallback reconstroem
  o contexto quando necessário. Falhas de autenticação continuam consumindo IV.
- MD4 nativo opcional e soma little-endian com `memoryview.cast('Q')`, mantendo
  implementações Python, cauda parcial, entrada não contígua e big-endian.
- Receptor implícito sem wakeup/`Packet` quando não usado; backlog limitado e
  API de recepção preservados. Alocação somente das camadas solicitadas.
- `poll()` para descritores altos; leitura TCP em lotes limitados a 64 KiB,
  uma cópia do payload e escrita otimista sem teste de prontidão redundante.
- Permissão de um peer em tempo constante no caminho de pacotes, sem cache de
  autorização: mudanças de membro/role continuam valendo imediatamente.
- Capacidade do scheduler calculada uma vez por passagem e negociações de
  conexão escaláveis: quatro no padrão, até 64 para cargas grandes.

As threads por peer e os locks de estado/contadores foram mantidos. A remoção
das varreduras deixou o lock de pacote curto e os recursos dentro da meta;
reescrever o transporte em um reactor não foi necessário. TLV não é usado no
caminho Ethernet de dados e não foi um gargalo contínuo; suas verificações de
limite e singleton foram preservadas. Wire bytes, flags, padding/trailer,
tipos públicos e operações CLI existentes permanecem compatíveis.

O verificador diferencial `tools/verify_compat.py` compara a fonte original
salva com a final: 90 pacotes nativos encadeados até 65.520 bytes, 50 pacotes
no fallback, rekeys direcionais, ciphertext inválido, consumo de IV, `auth8`
e `sum64_le`. Os testes também cobrem trocas entre backends, IPv4/ICMP não UDP,
descritor 1200, batching TCP, backlog implícito e capacidade do scheduler.
Os números de carga usam PyCryptodome; o fallback stdlib passa os testes,
mas não tem a mesma velocidade de criptografia.

### Oito redes públicas reais

Foi usado um perfil dedicado em `reports/optimization/live-profile`, sem TAP
ou API UDP local. Todos os oito joins, de Minecraft [Português 1] a [Português
8], tiveram sucesso; cada ensaio teve uma janela de 60 s após os joins.

| Configuração | Conectados ao final / elegíveis | CPU médio, um core = 100% | RSS máximo |
| --- | ---: | ---: | ---: |
| TCP direto/relay automático, quatro setups | 5 / 487 | 5,87% | 37,3 MB |
| Relay, quatro setups | 25 / 484 | 5,95% | 38,0 MB |
| Relay, setup escalável até 64 | 406 / 492 | 20,78% | 61,6 MB |

O ensaio escalável alcançou 393 conexões em 25 s e 406 em 30 s, permanecendo
com pelo menos 405 entre 30 s e 60 s. O maior intervalo de CPU de 5 s foi
38,95%; após a rampa, aproximadamente 13% de um core. Durante a janela foram
recebidos 13.498 quadros e transmitidos 385. Esses valores de tráfego orgânico
não substituem a carga controlada acima. O total conectado é por RID único;
um peer associado a várias redes entra uma vez no total.

Nem todos os peers anunciados ficaram conectados. No modo automático, ofertas
IPv6 de conexão direta revelaram uma limitação anterior do parser IPv4;
o modo relay contornou esse caminho. Peers remotos também fecharam TCP,
recusaram o SYN/ACK de serviço ou expiraram negociações. As recusas foram
mantidas, sem alterar a autenticação para aceitá-las. Todos os ensaios e o
perfil dedicado terminaram com o cliente fechado e suas threads encerradas.

Os números completos e os perfis ficam em `reports/optimization/`, inclusive
`baseline-64.json`, `final-64.json`, `final-400-60s.json`, `final-400-udp.json`,
`12-cipher-cache-400-rate10.json`, `live-minecraft-8-scaled.json`, arquivos
`.prof`, logs de self-test e `final-wire-compat.txt`. A fonte original está
em `reports/optimization/baseline-source/` para repetir a comparação offline.

# Registro de paridade OpenRad Rust → Java

Referência principal: fonte local OpenRad 2.0.2, `src/config.rs`, `crypto.rs`, `protocol.rs`, `session.rs`, `network.rs`, `peer.rs`, `incoming.rs`, `udp.rs` e `tunnel.rs`. O checkout Rust não foi alterado. As fixtures canônicas sintéticas de `tests/fixtures` foram copiadas para recursos de teste.

| Contrato | Java / evidência |
| --- | --- |
| TLV length/tag uint32 big-endian, u32/u64, singleton, caps | `TlvCodec`; vetores e entradas truncadas/duplicadas. |
| Texto UTF-16BE com terminador 0x0001 | Codificação estrita; display lossy com remoção de controles. Senhas de rede usam UTF-8 + NUL, conforme Rust. |
| Bootstrap público e módulo RSA | `ServiceConfig`, `RsaSession`; PKCS#1 v1.5, headers little-endian de propósito3/4/5; recuperação independente por chave sintética de teste. |
| SH/SRP | `ShClient`/`ShServer`; grupo, inteiros e identidade não padronizados, proofs e derivação; fixtures Rust nas duas direções. |
| Canal AES-CBC | `Channel`; padding0xcc, trailer, IVs independentes e rekey mantendo corrente; fixtures canônicas inclusive rekey. |
| Identidade e registro | JSON Rust compatível e campos0x1233; op21 LoginComplete antes de op8 Registered; redirects e marcador de resultado incerto. |
| Attachment e VIP | Propósito4; LoginComplete/registration snapshots, endereço companheiro16 bytes, UES autenticado e VIP 26/8. |
| Redes | Membership/roles/changes, CRC64 da identidade da rede, op39/40 privada SRP, op37+42 correlacionados, descobertaop43/45, create/leave/deleteop52. |
| Peers | Coordenador propósito5; NewConnection RID/CID; candidatos correlacionados; rendezvous e ticketrelay antes de proof mútuo. |
| Serviço Ethernet | Marcador de corrida/GUID, SYN/SYNACK/ACK, versão5–8, RID e MAC autenticados; fixtures e interop canônico real. |
| Transporte de dados | Envelopes little-endian de quadros sem compressão, keepalives big-endian, correnteCBC; comparação de frames de 1514 bytes. |
| UDP confiável/UES | PunchP/A/C, CRC canônico, ENET subset, ACK/reordenação/fragmentos/retry, transactions e endpoints correlacionados. |
| MAC local | `02:1a:<quatro octetos do VIP>`, sem inferir endereço a partir de RID. |

O TCP/IP virtual é uma nova camada Java de endpoint de serviço; não é um port da interface TAP do Rust. Suas garantias e limitações são testadas separadamente em `VirtualTcpBridgeTest`.

Foram executadas quatro combinações reais com a biblioteca canônica Rust: Java cliente/Rust servidor e Rust cliente/Java servidor, sobre TCP e UDP. Cada combinação trocou50 quadros de 1514 bytes em cada direção. O harness Rust é apenas de desenvolvimento e não entra no plugin.

Também houve execução no serviço público com duas identidades Java novas: registro, attachment, criação/entrada/saída/apagamento de rede privada, descoberta de membros, canal direto TCP e depois runtime completo em direto UDP até o Paper. Isso não é equivalente a validação do cliente oficial nem a teste WAN em diferentes hosts.

A implementação preserva as principais regras de framing/cripto/ordem/correlação e limites. Não se afirma correspondência linha a linha de todos os schedulers/relatórios do Rust, suporte a todos os recursos de clientes desktop ou certificação completa do protocolo proprietário. Consulte `crypto-parity.md`, `transport-parity.md` e `tests-live.md`.

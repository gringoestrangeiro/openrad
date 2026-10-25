# Modelo de ameaças e limites

## Fronteiras de confiança

Inputs não confiáveis incluem registros de controle/peer, candidatos, quadros Ethernet, nomes exibidos, YAML e arquivos privados. O servidor do serviço é uma autoridade do protocolo para identidade, redes e candidatos; a segurança não deve ser descrita como independente dessa autoridade.

Somente uma sessão de controle autenticada cria ofertas de peer. Cada canal passa por SRP mútuo e valida proofs antes de instalar a chave. O handshake de serviço vincula RID/MAC/versão; o firewall vincula o canal ao IP de membership e às redes autorizadas atuais. Nenhum campo do pacote escolhe o destino do socket backend.

A ACL combina papel e regra na mesma rede. Desativação/revogação troca a geração e invalida filas/fluxos antigos. RIDs com VIP ambíguo são recusados. Candidatos recusam endereços sem destino, multicast, broadcast e loopback proveniente de controle remoto. Loopback de candidatos é aceito somente quando o próprio controle é local, para os testes sintéticos.

## Limites e recuperação

TLVs: 4 MiB por mensagem, 16.384 registros, singleton estrito. Setup TCP: até 4.096 registros/32 MiB, prazos individuais de I/O, conexão interruptível e escrita com cancelamento. Ofertas, filas, candidatos, fragmentos UDP e janelas têm limites explícitos; veja `transport-parity.md`.

O runtime limita peers e handshakes pendentes. O TCP virtual limita conexões globais/por RID, SYNs pendentes, filas e memória; trata janelas fechadas, retransmissão, RST validado e deadlines. Checksums e comprimento são validados; fragmentação IPv4, opções IPv4 e protocolos não suportados são descartados. Testes incluem 10.000 frames malformados determinísticos, quotas, backend parado, revogação e corrida de gerações. Isso não equivale a uma auditoria formal ou fuzzing exaustivo.

## Criptografia legada

O wire format exige RSA PKCS#1 v1.5 com módulo público legado de 1.536 bits, SRP baseado em SHA-1, AES-256-CBC encadeado e trailer de integridade derivado de MD4/soma. Essas primitivas e o trailer não devem ser apresentados como AEAD moderno ou segurança equivalente a TLS atual. A implementação preserva a compatibilidade; não substitui checks criptográficos por constantes nem ignora proofs.

Sessões SRP usam aleatoriedade nova; cada direção CBC mantém sua própria corrente, inclusive durante rekey. Proofs são comparados sem dependência do primeiro byte divergente. A corrente e as sequências UDP reduzem replay de registros antigos, mas não há uma extensão nova de anti-replay ou uma garantia formal além do protocolo legado. Nunca reutilize uma sessão/chave depois de falha de autenticação. O plugin descarta a sessão em erro.

## Segredos e host

Identidade e senhas ficam separadas do YAML público, com permissão de proprietário POSIX ou ACL Windows. A identidade usa escrita temporária privada e substituição atômica. Sem suporte a permissões privadas, o arquivo é recusado. Senhas e chaves não entram em snapshots/logs/fixtures. Arrays temporários são zerados quando possível; a JVM e APIs de YAML/Properties usam Strings imutáveis, portanto não há garantia de apagamento físico de todas as cópias na memória.

O marcador de registro persiste antes do envio que pode emitir credencial; resultado incerto impede outro registro automático. A identidade não é compartilhada com instalações existentes. Os testes live criaram seus próprios perfis e redes temporárias; nenhuma credencial do usuário foi importada.

Um membro admitido pode alcançar o listener Minecraft e consumir os limites permitidos. A autenticação de jogador continua sendo responsabilidade do Minecraft/Paper (`online-mode=true`). O Paper enxerga loopback e não pode aplicar regras por IP virtual sem suporte adicional que este plugin não inventa. Acesso do administrador local aos arquivos/processo está fora da proteção oferecida pelo plugin.

## Anúncios LAN

A captura UDP local não concede acesso à VPN. Somente anúncios Minecraft UTF-8 limitados, originados de endereços locais e destinados à porta do backend, geram quadros; cabeçalhos são reconstruídos com o VIP/MAC do attachment e destino multicast fixo. Um processo local pode falsificar o MOTD desse servidor; a captura não autentica a autoria do plugin emissor. Não use MOTD como prova de identidade. Um único anúncio recente é mantido e expira em cinco segundos; cada canal autenticado recebe no máximo um quadro a cada 1500 ms. Membership/ACL atuais são exigidas também no loop de envio. Datagramas dos peers continuam sem acesso a serviços UDP ou à LAN física.

## Catálogo público Minecraft

A consulta usa o controle já autenticado, com correlação por página, prazo de 20 segundos, até 16 páginas/512 mensagens/1024 resultados. Seletores resolvem somente nomes retornados; não criam redes ou autorização por prefixos de peers. Expansão limitada a 512 redes, sem truncamento silencioso. Redes explícitas e suas ACLs/segredos têm precedência; a ACL global e os proofs dos canais permanecem obrigatórios. Membros informados no catálogo não são jogadores Minecraft e não concedem acesso até a membership real ser admitida.

## Descoberta para todos os membros

`max-peers: 0` amplia a capacidade até o roster de 4096, sem eliminar ACL, vínculo de VIP/MAC ou SRP. O executor usa threads virtuais sob demanda; no máximo 16 negociações novas ficam em andamento. Ordenação justa e backoff evitam repetir indefinidamente os primeiros RIDs. Contadores/resumos categorizam falhas sem registrar mensagens de exceção, payloads ou segredos. Isso não constitui benchmark de 4096 canais reais: capacidade prática depende dos sockets, CPU, memória, banda e regras de rede do host.

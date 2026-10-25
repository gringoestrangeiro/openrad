# OpenRad Plugin 2.1.0 (alpha) — notas

O plugin passa a seguir a versão do monorepo (antes `0.2.4-dev`). O status continua **alpha**:
gameplay completo com vários jogadores pelo Radmin VPN oficial ainda não foi medido
(veja [tests-live.md](tests-live.md)). O formato de identidade, `config.yml` e
`secrets.properties` existentes continuam válidos.

## Mais fácil de usar

- Configuração sem editar arquivos: `/orb join <rede> [senha]`, `/orb leave <rede>`,
  `/orb public <seletor...|off>`, `/orb enable`, `/orb disable` e `/orb help`. Os comandos
  validam a configuração antes de gravar, substituem `config.yml` e `secrets.properties`
  atomicamente (preservando comentários) e aplicam a mudança sem reiniciar o servidor.
  Nomes com espaço usam aspas. A senha não é aceita de jogadores (o Paper registra comandos de
  jogadores no log); use o console ou `secrets.properties`.
- `bridge.backend-port: 0` (novo padrão) usa automaticamente a porta do Paper.
- `/orb status` mostra o endereço que os jogadores devem usar e, sem VPN, os próximos passos.
- Erros mostram o motivo real: linha/coluna de YAML inválido (sem o conteúdo da linha), chave
  ausente em `secrets.properties`, senha curta com o nome da chave, `password:` colocado no YAML,
  entradas inválidas em `vpn.networks`, senha de rede errada, rede recusada pelo serviço.
- Aviso quando a VPN está ativada sem nenhuma rede.

## Correções de alto impacto

- **Presença de novos membros (paridade com o PR #10 do Rust/Android):** um membro anunciado
  com coordenador já é considerado online, a menos que o serviço diga o contrário; atualizações
  parciais mantêm coordenador e presença conhecidos. Antes, um jogador que entrava na rede
  depois do plugin só era alcançável após um evento de presença.
- **Recusa do serviço (op 44) não derruba mais a sessão.** Como no Rust, só op 16 encerra a
  sessão; um registro de oferta malformado é descartado em vez de reconectar tudo.
- **Canais não são derrubados** por renomeação do peer, alternância de presença 1↔5 ou por ele
  entrar em outra rede compartilhada. Mudanças de VIP, coordenador, estado offline ou perda de
  uma rede autorizada continuam revogando o canal.
- **Backoff de reconexão** volta ao valor base após uma sessão que durou pelo menos 60 s.
- **Ofertas de entrada:** sem rota de setup livre, a oferta espera a próxima varredura em vez
  de iniciar uma conexão de saída cancelada logo depois (que gerava logins em loop no
  coordenador); a rota é liberada assim que o canal conecta.
- **Rede configurada recusada** (senha errada, nome inexistente) é registrada em `/orb status`
  e as demais redes continuam; timeouts continuam reconectando.
- **Roster grande:** acima de 4096 membros a sessão falhava em todo login. Agora membros além
  de um limite defensivo (65536) são ignorados sem erro.
- **UDP direto:** um candidato inalcançável (ex.: IPv6 num host só IPv4) não aborta mais os
  outros candidatos; `ENOBUFS` é tratado como congestionamento, como no Rust.
- **TCP virtual:** um ACK de dados novos reinicia o timer de retransmissão (RFC 6298 5.3),
  evitando travamentos de até 8 s durante recuperação de perdas.
- **Identidade recém-emitida:** gravação tenta por ~15 s (antes ~0,6 s) antes de desistir,
  porque uma credencial perdida não pode ser registrada de novo automaticamente.
- **LAN:** MOTD de duas linhas é anunciado numa linha em vez de ser rejeitado.

## Validação

`./gradlew clean check` (JDK 21): todos os testes JUnit e `verifyJar` passam, incluindo novas
regressões para presença, edição de configuração, tokens de comando, vínculo de canais, senha
errada e congestionamento UDP. Não houve teste novo com o serviço real nem com o Radmin VPN
oficial nesta versão.

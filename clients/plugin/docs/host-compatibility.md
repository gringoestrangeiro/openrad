# Compatibilidade e limitações conhecidas

| Item | Evidência |
| --- | --- |
| Java 21 | Build limpo, testes e Paper 1.21.1 executados. Bytecode 65 verificado no JAR. |
| Java 25 | Build/testes e execução no Paper 1.21.1 avaliados separadamente com sucesso; veja `tests-live.md`. Não muda o alvo Java 21. |
| Paper 1.21.1 build 133 | Inicialização, carregamento, comandos, reload, encerramento e protocolo status/online encryption request executados em Windows. |
| Outras versões 1.21.x | API declarada 1.21, sem NMS; não foram executadas individualmente. |
| Paper de séries posteriores | Não certificado. |
| Linux e outros hosts | Implementação JDK sem dependência nativa; não houve execução em hospedagem Linux nesta entrega. |
| Radmin oficial | Gameplay ainda não comprovado; o perfil oficial instalado no host foi preservado. |

O host precisa permitir instalação de plugins, criação de threads/sockets e TCP de loopback para o listener Paper. Controle usa TCP 17301; peers e relays usam endpoints negociados. UDP/UES precisam de saída e respostas se habilitados. A hospedagem pode bloquear listeners/candidatos diretos ou UDP; habilite relay e avalie a política de rede do provedor. O plugin não contorna firewalls do host nem requer privilégios de administrador.

O IP virtual não é instalado no sistema: ping/ICMP e outros serviços do host não respondem nesse endereço. Apenas TCP para a porta virtual Minecraft configurada é aceito. O listener real do Paper deve aceitar o loopback configurado. Não se abre outra porta física 25565.

A pilha TCP é passiva e limitada ao serviço. Não negocia window scaling, SACK, timestamps ou ECN; não processa TCP urgente, fragmentação IPv4 ou opções IPv4. Mantém janela até 65.535 bytes e MSS até 1.460. Redes com RTT alto podem ter throughput menor; não há benchmark de produção nem certificação de conformidade completa com RFCs.

Foram testadas duas identidades Java no mesmo host usando o serviço público, canais diretos, interop local com Rust e sockets locais de backend. WAN/NAT em outros hosts, IPv6 externo real, relay público sob perda, carga prolongada e múltiplos jogadores continuam sem evidência final.

Reload/reconnect encerram todas as conexões atuais. Retirar uma rede da configuração revoga o acesso local, mas não executa leave automaticamente no serviço. Operações administrativas e diagnósticos são limitados; um probe UDP enviado não prova retorno.

As credenciais usam arquivos privados do proprietário, sem cofre de credenciais do sistema. Preserve-os em backups privados. Um host que não consegue aplicar permissões privadas ou gravar a identidade de forma segura falha fechado.

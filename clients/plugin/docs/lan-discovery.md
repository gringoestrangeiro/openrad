# Descoberta LAN do Minecraft Java

O OpenRad Plugin captura os anúncios de plugins como o [LANBroadcaster](https://github.com/4drian3d/LANBroadcaster) emitidos no próprio computador para `224.0.2.60:4445`. Recria cada anúncio como Ethernet/IPv4/UDP no túnel autenticado, usando o VIP e o MAC virtuais do attachment. O MOTD é preservado; a porta do payload é substituída por `bridge.listen-virtual-port`.

```yaml
bridge:
  backend-host: "127.0.0.1"
  backend-port: 25575
  listen-virtual-port: 25565
  lan-discovery:
    enabled: true
```

Instale o LANBroadcaster no mesmo servidor Paper. Configure `motd=ENTRE AQUI!!!` em `server.properties` e reinicie. Com esse exemplo, o anúncio original contém 25575 e o recebido pelo peer contém 25565. A opção de descoberta vem habilitada por padrão, inclusive em configurações antigas que não tenham a chave. `/orb status` mostra `LAN: LISTENING`, os anúncios capturados, os envios por peer e as rejeições. `UNAVAILABLE` indica falha ao abrir UDP/multicast local; a conexão VPN continua funcionando. `IO_FAILED` indica falha posterior de recepção; `/orb reconnect` tenta abrir a captura novamente.

Somente endereços IPv4 pertencentes às interfaces locais ativas e anúncios para `bridge.backend-port` são aceitos. Não há dependência de API ou ordem de inicialização do LANBroadcaster. O protocolo aceito é `[MOTD]texto[/MOTD][AD]porta[/AD]`, UTF-8 válido, com até 1400 bytes, sem campos duplicados, controles ou dados adicionais. Endereço, MAC e porta de origem do datagrama capturado não são encaminhados. Os checksums IPv4/UDP são recalculados; destino Ethernet multicast `01:00:5e:00:02:3c`, IP multicast `224.0.2.60`, TTL 1 e porta UDP 4445 são fixos.

Cada geração VPN possui um receptor fora da thread do Paper. Ele participa do grupo nas interfaces IPv4 ativas com multicast, compartilhando a porta com outros ouvintes via `SO_REUSEADDR`. A lista de interfaces é reavaliada a cada reconexão. O sistema precisa permitir recepção multicast e loopback local dos anúncios. Containers e hospedagens que bloqueiam isso podem impedir a captura. Não são criadas interfaces VPN no Windows ou Linux.

Há um único anúncio recente em memória; nenhum anúncio é acumulado numa fila por peer. Cada peer recebe no máximo um anúncio a cada 1500 ms. O último anúncio vence após cinco segundos sem atualização. Somente canais autenticados, com membership e ACL atuais na mesma rede, recebem anúncios; canais revogados são encerrados. Reload, reconexão e disable fecham o socket e descartam o anúncio anterior. UDP recebido dos peers continua bloqueado pelo firewall TCP; não existe roteamento UDP genérico ou retransmissão de anúncios remotos para a LAN física.

O anúncio é enviado apenas aos membros das redes configuradas e permitidas. O MOTD não publica o servidor na Internet nem inscreve o plugin em uma rede pública automaticamente. O cliente do jogador precisa estar na mesma rede VPN, receber multicast na interface correspondente e abrir a lista multiplayer. Ausência de jogadores entrando não demonstra falha: pode não haver alguém olhando a lista. O teste com cliente Radmin oficial e entrada autenticada no mundo continua pendente.

Os testes locais cobrem parser, limites, checksums, captura multicast real, compartilhamento da porta, expiração, fechamento/reabertura e entrega pelo runtime via peer SRP autenticado, seguida de revogação. O teste live adicional usa Paper, LANBroadcaster real e um peer Java isolado; veja [o relatório](tests-live.md). Esses testes não equivalem à visualização da lista LAN no cliente oficial.

Para divulgação a todos os membros elegíveis das redes selecionadas, configure `vpn.max-peers: 0`. A autenticação é gradual e a conectividade do outro computador continua sendo necessária. A lista completa de seletores está em `minecraft-network-options.yml` ou `/orb minecraft options`. Veja o guia `minecraft-networks.md` para filas, limites e contadores de alcance.

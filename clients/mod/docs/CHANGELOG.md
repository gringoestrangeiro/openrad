# Histórico

## 0.2.0-alpha

- Implementa endpoint TCP ativo sobre Ethernet OpenRad, com buffers e número de conexões limitados.
- Integra conexões de jogo e status para 26/8 ao Minecraft por um endpoint local temporário de destino fixo e uso único.
- Conecta e mantém canais criptografados TCP, UDP ou relay entre membros autorizados de redes selecionadas.
- Revoga canais, anúncios e fluxos quando a autorização ou a sessão termina.
- Recebe anúncios LAN pelo túnel e os exibe na tela Multiplayer, sem retransmitir UDP para a LAN física.
- Adiciona botão de conexão no Multiplayer e limites configuráveis, preservando configurações anteriores.
- Adiciona testes de protocolo, autorização, descarte de anúncios inválidos, ciclo de vida e interoperabilidade TCP com uma implementação de servidor independente.

## 0.1.0-alpha

- Identidade, autenticação, conexão de controle, membership, redes públicas e comandos Fabric.

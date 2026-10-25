# OpenRad 0.2.2-alpha — Menu de Redes Simplificado

## Correções

1. Mensagem de "Conectando..." deixa de permanecer depois que o estado da sessão progride.
2. O estado "VPN autenticada — entrando nas redes" indica que a conexão de controle já foi autenticada, mas que a entrada em todas as redes selecionadas continua em andamento. A contagem de redes agora é atualizada durante essa etapa.
3. O menu de redes foi dividido em quatro opções mutuamente exclusivas: Nenhuma, Todas, Por idioma, Redes específicas.
4. A opção Nenhuma remove seletores e nomes de redes exatos anteriores, mesmo se estiverem escondidos na configuração. Também desativa a conexão automática.
5. A escolha de idioma e a seleção de redes específicas ficam em listas separadas, simples e paginadas. É possível marcar e desmarcar uma rede clicando no mesmo item.
6. O botão Salvar redes grava diretamente a escolha; Cancelar não a persiste.
7. As redes específicas só são propostas com base no catálogo autenticado ou em nomes anteriormente escolhidos. Não se criam redes imaginárias.

## Testes

Gradle: core:test + fabric:test + fabric:build, Java 21.
Smoke isolado: fabric:runSmokeClient -PopenradSmoke=true.
Inclui regressões para modo Nenhuma, seletores exatos legados, alternância individual de redes, confirmação/cancelamento e status obsoleto.

## Nota de segurança

Nenhum servidor Paper de produção é alterado. A conexão ao serviço OpenRad só ocorre com autorização explícita e seleção de redes; o primeiro arranque continua desligado por padrão. A identidade da VPN fica separada da configuração.

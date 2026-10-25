# OpenRad Menu — Fabric 1.21.1

## Funcionamento

Botão OpenRad na tela inicial, no Multiplayer e na pausa; comando `/openrad menu`.
O mod possui seleção de redes e controle de conexão, opções avançadas, estado
de autenticação, IP virtual e quantidade de peers. O título nunca muda de nome.

O seletor de redes oferece `all`, `all-ptbr`, `all-english` e `all-japanese`
mesmo sem autenticação, além de todos os grupos de idioma conhecidos pela
implementação OpenRad. Seletores numerados só ficam disponíveis após o
catálogo real, e seletores salvos continuam editáveis antes de conectar.
Nenhum nome de rede é inventado para preencher a listagem.

## Localização

As interfaces utilizam Component.translatable e OpenRadI18n.tr. O idioma
atual do Minecraft é consultado ao renderizar o menu, não há tradutor online.
Nome de idioma: Java/CLDR no idioma selecionado no Minecraft.

Traduções integrais: pt_br, en_us e ja_jp (46 mensagens).
Traduções parciais: ar_sa, de_de, es_es, fr_fr, it_it, ko_kr, pl_pl,
ru_ru, tr_tr, uk_ua, zh_cn.
Demais idiomas usam traduções nativas do Minecraft para controles genéricos,
e inglês somente para mensagens técnicas sem equivalente. Não afirmar que
há tradução humana integral dos 143 idiomas; esta é a cobertura atual.

## Segurança

Configurações preservam opt-in inicial e escrita atômica. O perfil VPN é
separado de configurações, e rede escolhida não autoriza peer inexistente.
A troca de geração fecha o runtime anterior. Paper de produção intocado.

## Verificação

Java 21: `gradlew.bat :core:test :fabric:test :fabric:build`.
Smoke isolado: `gradlew.bat :fabric:runSmokeClient -PopenradSmoke=true`.
Testes não equivalem à validação de gameplay WAN com o Radmin oficial.

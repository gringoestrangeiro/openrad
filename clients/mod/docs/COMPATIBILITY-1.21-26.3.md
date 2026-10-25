# OpenRad Client Mod — matriz Minecraft 1.21 a 26.3

Versões no intervalo: 17 releases Java, com compilações Fabric específicas.
O status é gerado lendo o próprio JAR: versão declarada, Java, classes,
mixins, licença e arquivos de idioma português e japonês.

| Minecraft | Java | OpenRad mod | Estado do artefato |
|---|---:|---|---|
| 1.21 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.1 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.2 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.3 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.4 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.5 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.6 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.7 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.8 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.9 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.10 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 1.21.11 | 21 | 0.2.3-alpha | BUILD VERIFICADO |
| 26.1 | 25 | 0.3.0-alpha | BUILD VERIFICADO |
| 26.1.1 | 25 | 0.3.0-alpha | BUILD VERIFICADO |
| 26.1.2 | 25 | 0.3.0-alpha | BUILD VERIFICADO |
| 26.2 | 25 | 0.3.0-alpha | BUILD VERIFICADO |
| 26.3 | 25 | 0.3.0-alpha | BUILD VERIFICADO |

## Como instalar

No cliente Minecraft, instale apenas um JAR correspondente à versão exata
do jogo. Instale também Fabric Loader/Fabric API adequados. Os builds 1.21.x
usam Java 21 e o projeto fabric/; os builds 26.x usam Java 25 e o projeto
modern/, com a API de renderização e comandos adaptada.

## Qualidade de validação

BUILD VERIFICADO não significa gameplay validado. Compilação e
inspeção do JAR não comprovam aplicação de todos os mixins durante o jogo,
conexão real ao Radmin/OpenRad, multiplayer, passagem de status ou movimentação.
Os testes do núcleo executam no projeto legacy; o projeto modern
ainda não possui uma suíte de smoke tests Minecraft. Faça testes isolados
antes de usar no perfil principal.

## Recompilação reproduzível

    .\build-full-range.ps1
    .\build-full-range.ps1 -Targets @('1.21.11','26.3') -Rebuild
    python tools/report-full-range.py

Em builds legados, Fabric Loom 1.13.6 e Gradle 9.1; na geração 26.x,
Fabric Loom 1.17.20 e Gradle 9.7.1. Fabric API é específica por versão.

Não modifique identidades VPN nem o servidor Paper de produção durante testes.

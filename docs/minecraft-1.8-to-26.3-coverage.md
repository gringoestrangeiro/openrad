# OpenRad Minecraft Java 1.8–26.3 — roteiro e builds comprovados

Base: upstream `v2.1.5`. Branch `feature/minecraft-1.8-to-26.3-version-coverage`.
PR em rascunho: https://github.com/gringoestrangeiro/openrad/pull/17
Nenhuma release ou merge autorizado.

## Escopo atual (SEM loaders novos)

- **Fabric: somente Minecraft 1.14 em diante.** Não criar Fabric 1.8–1.13:
  versões fora do escopo porque o loader oficial não existe para elas.
- **Paper: objetivo de cobertura 1.8 a 26.3**, mas o runtime Java21 existente
  precisa de um port para versões antigas do Java/Minecraft.
- Nada de Forge, NeoForge, Velocity, Bungee ou outra plataforma nesta branch.
  O trabalho anterior de proxies permanece na worktree isolada `OpenRad_Multiplatform_20261027`.

## Compilação e testes

| Minecraft | Fabric | Paper | JVM do JAR | Estado |
| --- | --- | --- | --- | --- |
| 1.20.3 | Gradle build/test, metadata e mixins verificados | Gradle check, metadata `api-version: '1.20'` | Java 21 | **EXPERIMENTAL.** Nao testado em jogo; runtime do cliente costuma ser Java 17 |
| 1.20.4 | Gradle build/test, metadata e mixins verificados | Gradle check, metadata `api-version: '1.20'` | Java 21 | **EXPERIMENTAL.** Versão do jogo costuma usar Java 17. Sem teste de startup/multiplayer com Java 21 |
| 1.20.5 | Gradle build/test, metadata e mixins verificados | Bloqueado: BOM Adventure 4.17.0-SNAPSHOT histórico indisponível | Java 21 para mod | Fabric build verificado, Paper não |
| 1.20.6 | Gradle build/test, metadata e mixins verificados | Gradle check, 150 testes (1 ignorado), 116 classes Java21 | Java 21 | Build verificado, sem prova de jogo |
| 1.21–1.21.11 | Builds da versão oficial 2.1.5 | Cobertura Paper da versão oficial 2.1.5 varia por destino | Java 21 | Preserve upstream |
| 26.1–26.3 | Builds da versão oficial 2.1.5 | Paper somente 26.1.1, 26.1.2, 26.2, 26.3 | Java 25 | Preserve upstream |

**IMPORTANTE:** build verificado não significa compatibilidade multiplayer real.
A versão 1.20.4 Java 21 é uma opção de desenvolvimento para instalação JVM
customizada, não um JAR padrão de Minecraft 1.20.4 que use Java 17.

## Comandos

No PowerShell na raiz do repositório:

```powershell
cd clients/mod
.\build-full-range.ps1 -Targets 1.20.3,1.20.4,1.20.5,1.20.6
cd ..\plugin
.\scripts\build-paper-targets.ps1 -Targets 1.20.3,1.20.4,1.20.6
cd ..\..
python -m unittest discover -s tests -p test_minecraft_version_coverage.py -v
python tools/minecraft_version_coverage.py --output docs/minecraft-java-releases-1.8-to-26.3.json
```

Os JARs experimentais 1.20.3 e 1.20.4 recebem sufixo `-java21-experimental.jar`.
Os relatórios de build e checksums são gravados em `clients/mod/release`
e `clients/plugin/release` na estação Windows.

## Próximos ports necessários

1. 1.20.3/1.20.4 e anteriores no Java 17 ou abaixo: criar uma variante de runtime
   compatível, substituindo virtual threads e APIs Java21 (incluindo List.getFirst)
   com cuidado para limites de memória e concorrência.
2. Adaptar mixins de rede do Fabric por família de versões, com um build independente
   para cada versão realmente testada.
3. Corrigir a resolução histórica de dependências Paper e portar para Java17/16/8,
   sem distribuir JARs Java21 renomeados como "suporte legado".
4. Validar Minecraft 1.20.3/1.20.4 com Java21 no cliente e servidor, handshake VPN,
   peers autenticados, LAN, reconexão, roteamento de TCP e shutdown.
5. Fazer validação regressiva de builds 1.20.5/1.20.6 e 1.21+/26.x.

A lista de 75 releases estáveis vem do manifesto de versões oficial da Mojang,
não representa 75 JARs funcionais nem 75 versões da API Paper.

Referências: https://wiki.fabricmc.net/install ;
https://wiki.fabricmc.net/player:tutorials:install_server ;
https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.97.3%2B1.20.4/

Nenhuma instalação, migração de mundos, release, merge ou edição do trabalho
antigo com loaders adicionais foi realizada por esta branch.

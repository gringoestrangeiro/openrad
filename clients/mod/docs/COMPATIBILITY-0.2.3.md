# Compatibilidade OpenRad Client Mod 0.2.3-alpha

## Builds por versão

JARs compilados e testes automatizados Gradle aprovados no Windows 11 com Fabric Loom 1.13.6:

| Minecraft Java | Fabric API utilizada | JAR |
|---|---|---|
| **1.20.6** | 0.100.8+1.20.6 | OpenRadClientMod-0.2.3-alpha-fabric-1.20.6.jar |
| **1.21** | 0.102.0+1.21 | OpenRadClientMod-0.2.3-alpha-fabric-1.21.jar |
| **1.21.1** | 0.116.17+1.21.1 | OpenRadClientMod-0.2.3-alpha-fabric-1.21.1.jar |
| **1.21.4** | 0.119.4+1.21.4 | OpenRadClientMod-0.2.3-alpha-fabric-1.21.4.jar |

Cada JAR exige no fabric.mod.json exatamente a versão de Minecraft da linha correspondente. **Instale apenas um JAR por cliente**, junto de Fabric Loader e Fabric API próprias da versão.

O Gradle também permite experimentar alvos 1.21.2 e 1.21.3, mas **não foram compilados nem validados nesta entrega** e não há JAR distribuído deles.

## Nível real de validação

- Confirmado: compilação contra os mapeamentos oficiais da versão indicada, tarefas de teste/build Gradle, remapJar, metadados exatos e classes/mixins no JAR com bytecode Java 21.
- Não validado: inicialização do Minecraft com Loader nas quatro versões, execução dos mixins em jogo, jogabilidade e conectividade WAN oficial OpenRad/Radmin.
- A versão permanece ALPHA. Teste em instância Minecraft isolada antes de usar no seu perfil principal.

## Recompilar a matriz

PowerShell na pasta do repositório:

    .\build-matrix.ps1

Para compilar uma versão individual:

    .\build-matrix.ps1 -MinecraftVersions '1.21.4'

Alternativamente:

    .\gradlew.bat --no-daemon '-PopenradMinecraftVersion=1.21' :core:test :fabric:build :fabric:test

O script confirma versão do minecraft no fabric.mod.json, bytecode Java 21, licença, entrypoint e mixins antes de copiar para release.

## Importante

- O Minecraft e a Fabric API precisam ser da mesma versão do JAR.
- Java 21 é obrigatório para este mod.
- O OpenRad só se conecta quando explicitamente solicitado ou habilitado na configuração.
- Não coloque quatro versões ao mesmo tempo na pasta mods/.
- O OpenRad não fornece tradução de protocolo entre diferentes versões do Minecraft. Para entrar no servidor, a combinação cliente/servidor deve ser normalmente suportada pelo jogo e/ou pelo host.
- Não copie identidades do plugin Paper para o mod; credenciais ficam separadas.

Nenhum servidor de produção, Paper ou perfil VPN real foi alterado para gerar estes builds.

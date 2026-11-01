# OpenRad: compatibilidade Minecraft Java (meta 1.14 a 26.3)

Desenvolvimento integrado na branch `2.2.0-dev` (**OpenRad 2.2.0**, base 2.1.5) a partir de
https://github.com/gringoestrangeiro/openrad/pull/18 (substitui o PR #17).

**Restrições respeitadas:** nenhum loader novo; Fabric começa em Minecraft
1.14, e não criamos builds Fabric ou Paper 1.8–1.13. A meta de compatibilidade para os dois clientes começa em 1.14;
as versões anteriores à 1.17 continuam sem JARs verificados. O código experimental de Velocity/BungeeCord
foi preservado em outra worktree, sem alterações.

## Resultados de compilação

| Minecraft Java | Mod Fabric | Plugin Paper | Estado |
| --- | --- | --- | --- |
| 1.17 | Java 16, Fabric API 0.36.0+1.17; Gradle build/test/remap e JAR verificados | Java 16, 119 classes; Gradle check/test/verifyJar verificados | **Ambos experimentais; runtime não testado** |
| 1.17.1 | Java 16, Fabric API 0.46.1+1.17, Gradle build/test/remap + verificação do JAR | Java 16, 119 classes, Gradle check/test + JAR verificados | **Ambos experimentais; runtime não testado** |
| 1.18 | Build/test/remap Java 17 e JAR exato verificados | Java 17, 119 classes; Gradle check/test/verifyJar verificados | **Ambos experimentais; runtime não testado** |
| 1.18.1 | Build/test/remap Java 17 e JAR exato verificados | Java 17, 117 classes, suíte Gradle check e rerun completo sem cache aprovados; JAR verificado | **Ambos experimentais; runtime não testado** |
| 1.18.2 | Adaptadores específicos de texto, comandos e UI; build/test/remap Java 17 verificados | Java 17, Gradle check/test e JAR verificados (117 classes) | **Experimental: ambos compilados, runtime não testado** |
| 1.19 | Java 17, Fabric Gradle build/test/remap verificados | Java 17, Gradle check/test + verifyJar (117 classes) verificados | **Experimental; gameplay/Radmin não testados** |
| 1.19.1 | Java 17, build/test/remap verificados | Java 17, Gradle check/test, JAR verificado | **Experimental; gameplay/Radmin não testados** |
| 1.19.2 | Java17; botão e tooltip legados, mixin LAN adaptado; build/test/remap | Java17; API antiga de metadados, Gradle check/test | **Experimental, sem teste em jogo** |
| 1.19.3 | Java 17 + PoseStack/LanguageInfo adaptados; Gradle build, testes e remap verificados | Paper Java 17 compilado e tests passaram | **Experimental, sem teste real em jogo** |
| 1.19.4 | Java 17 + PoseStack e Mixin específico; Gradle build/test/remap verificados | Paper 1.19.4 Java 17 + Gradle check verificado | **Experimental: nenhum teste real de Minecraft/Radmin** |
| 1.20.1 | Gradle build/test Java 17 verificados | Paper 1.20.1 Gradle check Java 17 verificado | **Experimental, runtime não testado** |
| 1.20.2 | Compilação, remapeamento, testes Java 17 verificados | API 1.20.2, Gradle check/test Java 17 verificados | **Experimental, sem teste de jogo/multiplayer** |
| 1.20.3 | Compilação, remapeamento, testes e metadata Java 17 verificados | API 1.20.3, Gradle check/test, classes Java 17 e plugin.yml 1.20 verificados | **Experimental, sem startup/jogo ou multiplayer** |
| 1.20.4 | Compilação, remapeamento, testes e metadata Java 17 verificados | API 1.20.4, Gradle check/test, classes Java 17 e plugin.yml 1.20 verificados | **Experimental, sem startup/jogo ou multiplayer** |
| 1.20.5 | Compilação, remapeamento e testes Java 21 verificados | Gradle check Java 21 verificado; BOM histórico 4.17.0-SNAPSHOT obtido de espelho e demais módulos Adventure resolvidos em 4.17.0 estável. **Experimental** | Ambos em desenvolvimento; sem validação em jogo |
| 1.20.6 | Compilação, remapeamento e testes Java 21 verificados | Gradle check/test e classfile Java 21 verificados | Compilação validada, gameplay não |
| 1.21–1.21.11 | Versões existentes da 2.1.5 | Cobertura Paper existente varia por versão | Preservar upstream |
| 26.1–26.3 | Versões existentes da 2.1.5 | Paper 26.1.1, 26.1.2, 26.2, 26.3 | Preservar upstream |

**Uma compilação bem-sucedida não garante funcionamento dentro do Minecraft.**
As builds experimentais novas não passaram por testes com jogadores,
coordenador Radmin, autenticação OpenRad em rede real ou reconexão em jogo.

## Porte Java 17: como funciona

O OpenRad usa virtual threads nas JVMs modernas. O adaptador
`ThreadCompat` preserva virtual threads em Java 21+ usando APIs por reflexão e
fornece *threads* da plataforma com limites de concorrência em Java 17.

No cliente e no plugin, as chamadas diretas incompatíveis com Java 17
(`Thread.ofVirtual`, `Thread.ofPlatform`, `getFirst`,
`Math.clamp` e `Thread.join(Duration)`) foram adaptadas.

- `clients/mod/core/src/main/java/.../ThreadCompat.java`
- `clients/plugin/src/main/java/.../ThreadCompat.java`

Os JARs de 1.20.2 a 1.20.4 foram compilados usando `--release 17`,
e a metadata do Fabric exige `java >=17`. A suíte de testes mais moderna
continua usando Java 21+ no ambiente de build.

## Builds locais reproduzíveis

No Windows PowerShell, na raiz desta worktree:

```powershell
cd clients/mod
.\build-full-range.ps1 -Targets 1.18,1.18.1,1.18.2,1.19,1.19.1,1.19.2,1.19.3,1.19.4,1.20.1,1.20.2,1.20.3,1.20.4,1.20.5,1.20.6
cd ..\plugin
.\scripts\build-paper-targets.ps1 -Targets 1.18.2,1.19,1.19.1,1.19.2,1.19.3,1.19.4,1.20.1,1.20.2,1.20.3,1.20.4,1.20.5,1.20.6
cd ..\..
python -m unittest discover -s tests -p test_minecraft_version_coverage.py -v
python tools/minecraft_version_coverage.py --output docs/minecraft-java-releases-1.8-to-26.3.json
```

Os builds Java 17 têm o sufixo `-java17-experimental.jar`. Arquivos JAR,
relatórios e SHA256 são salvos em `clients/mod/release` e
`clients/plugin/release` no PC Windows. Arquivos de Java 21 antigos
continuam preservados, mas não devem ser instalados como mods Java 17.

## Versões ainda pendentes

**Fabric 1.14–1.16.5:** portar APIs e mixins por geração, recompilar
para a JVM correspondente (Java 8, 16 ou 17), inicializar o jogo e testar
conexões reais. Fabric 1.8–1.13 **fora de escopo**.

**Paper 1.14–1.16.5:** requer portabilidade do núcleo e autenticação para
Java 8/11/16/17, dependendo da versão histórica do servidor. Não é correto
distribuir um mesmo JAR moderno renomeado para essas versões.

**Paper 1.20.5:** dependências históricas resolvidas na compilação; falta verificar o carregamento em servidor real,
executar o servidor de teste e validar.

Versões 1.21+/26.x existentes foram preservadas, mas precisam de uma matriz
de testes contínua após mudanças compartilhadas.

O catálogo JSON preserva o inventário histórico de **75 releases estáveis** entre 1.8 e 26.3
consultadas ao manifesto oficial da Mojang. Não indica 75 JARs funcionais.

Fontes de compatibilidade:
https://docs.fabricmc.net/players/faq
https://docs.papermc.io/paper/getting-started/

## Segurança e publicação

Apenas builds locais e PR em rascunho; **sem merge, sem release**.
Preservar autenticação e isolamento de rede, não alterar portas do Lucas Dev MCP.

**Paper 1.17.1:** o adaptador Java 16 preserva o texto de comentários do YAML antigo, mas pode mover comentários para o início do arquivo. O teste não equivale à inicialização de um servidor real.

**1.14–1.16.5 (Java 8):** a compatibilidade ainda exige um porte do núcleo de protocolo e rede, não apenas a troca de bibliotecas. A árvore Java atual do mod contém dezenas de declarações `record`, uso de `HexFormat`, streams e chamadas de arquivo ausentes no Java 8. Essas funcionalidades precisam ser reimplementadas com interfaces de dados Java 8, testes criptográficos e testes reais de conexão antes de qualquer JAR ser chamado de compatível. Para 1.17/1.17.1, o alvo técnico é Java 16; para 1.18.x, Java 17.

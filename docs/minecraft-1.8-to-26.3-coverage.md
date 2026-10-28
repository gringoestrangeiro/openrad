# OpenRad: cobertura de versões Minecraft Java (1.8 a 26.3)

Desenvolvimento baseado no **OpenRad 2.1.5**. PR em rascunho:
https://github.com/gringoestrangeiro/openrad/pull/17

**Restrições respeitadas:** nenhum loader novo; Fabric começa em Minecraft
1.14, e não criamos builds Fabric 1.8–1.13. O plugin Paper continua com
meta de compatibilidade histórica desde 1.8, mas essas versões antigas
**ainda NÃO estão implementadas**. O código experimental de Velocity/BungeeCord
foi preservado em outra worktree, sem alterações.

## Resultados de compilação

| Minecraft Java | Mod Fabric | Plugin Paper | Estado |
| --- | --- | --- | --- |
| 1.20.3 | Compilação, remapeamento, testes e metadata Java 17 verificados | API 1.20.3, Gradle check/test, classes Java 17 e plugin.yml 1.20 verificados | **Experimental, sem startup/jogo ou multiplayer** |
| 1.20.4 | Compilação, remapeamento, testes e metadata Java 17 verificados | API 1.20.4, Gradle check/test, classes Java 17 e plugin.yml 1.20 verificados | **Experimental, sem startup/jogo ou multiplayer** |
| 1.20.5 | Compilação, remapeamento e testes Java 21 verificados | **Bloqueado:** dependência histórica Adventure BOM 4.17.0-SNAPSHOT não encontrada nos repositórios configurados | Fabric experimental, Paper não |
| 1.20.6 | Compilação, remapeamento e testes Java 21 verificados | Gradle check/test e classfile Java 21 verificados | Compilação validada, gameplay não |
| 1.21–1.21.11 | Versões existentes da 2.1.5 | Cobertura Paper existente varia por versão | Preservar upstream |
| 26.1–26.3 | Versões existentes da 2.1.5 | Paper 26.1.1, 26.1.2, 26.2, 26.3 | Preservar upstream |

**Uma compilação bem-sucedida não garante funcionamento dentro do Minecraft.**
Os quatro alvos novos não passaram por testes com jogadores,
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

Os JARs de 1.20.3 e 1.20.4 foram compilados usando `--release 17`,
e a metadata do Fabric exige `java >=17`. A suíte de testes mais moderna
continua usando Java 21+ no ambiente de build.

## Builds locais reproduzíveis

No Windows PowerShell, na raiz desta worktree:

```powershell
cd clients/mod
.\build-full-range.ps1 -Targets 1.20.3,1.20.4,1.20.5,1.20.6
cd ..\plugin
.\scripts\build-paper-targets.ps1 -Targets 1.20.3,1.20.4,1.20.6
cd ..\..
python -m unittest discover -s tests -p test_minecraft_version_coverage.py -v
python tools/minecraft_version_coverage.py --output docs/minecraft-java-releases-1.8-to-26.3.json
```

Os builds Java 17 têm o sufixo `-java17-experimental.jar`. Arquivos JAR,
relatórios e SHA256 são salvos em `clients/mod/release` e
`clients/plugin/release` no PC Windows. Arquivos de Java 21 antigos
continuam preservados, mas não devem ser instalados como mods Java 17.

## Versões ainda pendentes

**Fabric 1.14–1.20.2:** portar APIs e mixins por geração, recompilar
para a JVM correspondente (Java 8, 16 ou 17), inicializar o jogo e testar
conexões reais. Fabric 1.8–1.13 **fora de escopo**.

**Paper 1.8–1.20.2:** requires porto do núcleo e autenticação para
Java 8/11/16/17, dependendo da versão histórica do servidor. Não é correto
distribuir um mesmo JAR moderno renomeado para essas versões.

**Paper 1.20.5:** corrigir resolução das dependências históricas, compilar,
executar o servidor de teste e validar.

Versões 1.21+/26.x existentes foram preservadas, mas precisam de uma matriz
de testes contínua após mudanças compartilhadas.

O catálogo JSON enumera **75 releases estáveis reais** entre 1.8 e 26.3
consultadas ao manifesto oficial da Mojang. Não indica 75 JARs funcionais.

Fontes de compatibilidade:
https://docs.fabricmc.net/players/faq
https://docs.papermc.io/paper/getting-started/

## Segurança e publicação

Apenas builds locais e PR em rascunho; **sem merge, sem release**.
Preservar autenticação e isolamento de rede, não alterar portas do Lucas Dev MCP.

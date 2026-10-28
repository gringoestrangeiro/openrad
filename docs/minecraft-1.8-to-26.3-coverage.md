# OpenRad: Minecraft Java 1.8 ate 26.3 - cobertura honesta

Branch de desenvolvimento a partir da tag oficial v2.1.5. Nao publicar release, nao dar merge sem revisao.

## Resultados comprovados

- Fabric 1.20.5: build/test de core e adapter concluidos e verificacao do JAR.
- Fabric 1.20.6: build/test de core e adapter concluidos e verificacao do JAR.
- Paper 1.20.6: compilacao Java 21, test suite (150 testes, 1 ignorado), verificacao de classes e metadados. Um teste dependia indevidamente de 900 ms fixos no Windows e foi corrigido na branch de testes.
- Builds publicados da 2.1.5: Fabric 1.21 ate 1.21.11 e 26.1 ate 26.3. Paper: linha 1.21 e 26.1.1/26.1.2/26.2/26.3.
- Paper 1.20.5: nao validado. O artefato historico da API depende de adventure-bom 4.17.0-SNAPSHOT indisponivel no repositorio configurado.

ATENCAO: nenhum novo alvo teve entrada em jogo nem multiplayer real validado. Um JAR compilado nao garante que as conexoes funcionam com jogadores.

## Limites de compatibilidade

- Fabric oficial tem suporte a partir de Minecraft 1.14 (fonte: https://docs.fabricmc.net/players/faq).
  Nao e possivel fornecer mod Fabric oficial para 1.8-1.13 sem outro loader ou runtime nao oficial.
  A decisao de NAO adicionar loaders novos foi respeitada.
- 1.14-1.20.4 no cliente: requer adaptadores por geracao do Minecraft e port do Java21
  para Java8/Java16/Java17, alem de mixins e mappings especificos.
- Paper 1.8-1.20.5: requer compilacao contra APIs historicas e port do runtime
  moderno Java21 para as JVMs usadas em servidores antigos. Nao trocar o nome do JAR para fingir compatibilidade.
- Paper 26.1 sem correcoes (unpatched) nao e suportado pelo plugin publicado.
- Nenhuma release ou PR foi criada automaticamente.

## Catalogo oficial completo

O utilitario tools/minecraft_version_coverage.py consulta o manifesto oficial da Mojang,
filtra apenas releases (sem snapshots) e gera JSON entre 1.8 e 26.3:

    python tools/minecraft_version_coverage.py --output reports/minecraft-1.8-to-26.3.json
    python -m unittest discover -s tests -p test_minecraft_version_coverage.py -v

O campo published-build-verified so sinaliza JAR e testes de compilacao do release 2.1.5.
O campo development-build-verified nao significa compatibilidade de runtime.
Os status requires-... sao tarefas pendentes, nao builds.

## Compilacao dos alvos estendidos

Fabric 1.20.5/1.20.6:

    cd clients/mod
    ./build-full-range.ps1 -Targets 1.20.5,1.20.6

Paper 1.20.6:

    cd clients/plugin
    ./scripts/build-paper-targets.ps1 -Targets 1.20.6

Os scripts validam metadata/bytecode e guardam resultados na pasta release do modulo.
Os artefatos de desenvolvimento nao fazem parte da release oficial v2.1.5.
Instale somente o JAR correspondente a versao e ao loader do jogo.

## Trabalho antigo preservado

O codigo experimental dos proxies Velocity e BungeeCord continua em:
C:\Users\lucas\Downloads\OpenRad_Multiplatform_20261027

Esse diretorio nao foi excluido ou modificado durante esta expansao.
A nova branch so altera cobertura de versoes do mod Fabric e do plugin Paper.

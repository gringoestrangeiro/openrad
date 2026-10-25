# OpenRad Client Mod ? preparado para a branch 2.1.5

Mod Fabric (cliente) com JAR por vers?o Minecraft, importado do prot?tipo local e integrado ao monorepo, com o n?cleo de protocolo atualizado para a mesma implementa??o do plugin Paper **2.1.5**. Nenhuma identidade, perfil, configura??o de usu?rio ou bin?rio de teste foi importado.

- Fabric Minecraft **1.21?1.21.11**, Java 21, com build separado por vers?o.
- Fabric Minecraft **26.1?26.3**, Java 25, adaptado no projeto `modern/`.
- O mod usa IPs VPN virtualizados `26.x.x.x` dentro do Minecraft, sem criar TAP/TUN ou rotas de rede do host.
- Requer Fabric Loader e Fabric API da vers?o exata de Minecraft; instalar apenas **um** JAR OpenRad por perfil.
- Conex?o autom?tica ? desativada por padr?o; usar perfil e redes explicitamente escolhidas.

**Estado: alpha, c?digo de desenvolvimento.** O hist?rico de builds pr?-integra??o n?o certifica os novos JARs 2.1.5 nem jogabilidade, conex?o com Radmin oficial ou todas as vers?es. Verificar mixins, carregamento, login, mundo e multiplayer em inst?ncias isoladas antes de publicar. Consulte o hist?rico t?cnico em `docs/COMPATIBILITY-1.21-26.3.md` (matriz original pr?-integra??o).

Compila??o local de refer?ncia: `./gradlew :core:test :fabric:build :fabric:test -PopenradMinecraftVersion=1.21.1` em `clients/mod/`; gera??o 26.x em `clients/mod/modern/` usando o Gradle wrapper da pr?pria pasta. Para matriz de 17 vers?es, executar `build-full-range.ps1`; os resultados devem ser recalculados antes da publica??o.

# OpenRad Client Mod — preparado para a branch 2.1.5

Mod Fabric (cliente) com JAR por versão Minecraft, importado do protótipo local e integrado ao monorepo, com o núcleo de protocolo atualizado para a mesma implementação do plugin Paper **2.1.5**. Nenhuma identidade, perfil, configuração de usuário ou binário de teste foi importado.

- Fabric Minecraft **1.21–1.21.11**, Java 21, com build separado por versão.
- Fabric Minecraft **26.1–26.3**, Java 25, adaptado no projeto `modern/`.
- O mod usa IPs VPN virtualizados `26.x.x.x` dentro do Minecraft, sem criar TAP/TUN ou rotas de rede do host.
- Requer Fabric Loader e Fabric API da versão exata de Minecraft; instalar apenas **um** JAR OpenRad por perfil.
- Conexão automática é desativada por padrão; usar perfil e redes explicitamente escolhidas.

**Estado: alpha, código de desenvolvimento.** O histórico de builds pré-integração não certifica os novos JARs 2.1.5 nem jogabilidade, conexão com Radmin oficial ou todas as versões. Verificar mixins, carregamento, login, mundo e multiplayer em instâncias isoladas antes de publicar. Consulte o histórico técnico em `docs/COMPATIBILITY-1.21-26.3.md` (matriz original pré-integração).

Compilação local de referência: `./gradlew :core:test :fabric:build :fabric:test -PopenradMinecraftVersion=1.21.1` em `clients/mod/`; geração 26.x em `clients/mod/modern/` usando o Gradle wrapper da própria pasta. Para matriz de 17 versões, executar `build-full-range.ps1`; os resultados devem ser recalculados antes da publicação.

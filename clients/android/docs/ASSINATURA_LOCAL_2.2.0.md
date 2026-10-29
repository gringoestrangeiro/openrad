# Assinatura local do OpenRad Android 2.2.0

O GitHub Actions gera **somente o APK sem assinatura**; ele nao recebe chaves privadas nem senhas. A assinatura de producao e feita exclusivamente no Windows, com o **keystore original**, por um responsavel autorizado. Nao use a chave SSH do GitHub ou a debug keystore.

1. No GitHub Actions, baixe o artefato `openrad-2.2.0-android-UNSIGNED-for-local-signing` e extraia `openrad-2.2.0-android-UNSIGNED.apk` e `SHA256SUMS` para a pasta `dist/android-local-signing/` do projeto.
2. No **Android Studio > Settings > Android SDK > SDK Tools**, instale Android SDK Build-Tools **34.0.0**, se ainda nao estiver instalado. Java 17 ou posterior e `gh` CLI tambem sao necessarios.
3. Em PowerShell na raiz do repositorio, execute:
   
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sign-android-local.ps1 -Keystore "D:\caminho\privado\chave-original.jks" -Alias "alias-original"
   ```

   O script pede **duas senhas no proprio terminal**, ocultando a digitacao. Elas sao entregues ao assinador via entrada padrao; nao coloque senhas em comandos, tickets, commits, arquivos compartilhados ou no chat.

4. O script baixa o APK oficial v2.0.1 do repositorio, verifica o certificado antigo, o pacote `com.openrad.mobile`, `versionCode=7`, `versionName=2.2.0`, a assinatura v2/v3 e a integridade SHA-256. Ele **bloqueia** a saida se o certificado do APK novo for diferente.
5. So apos todas as verificacoes, o resultado vai para `dist/android-release-signed/openrad-2.2.0-android.apk` com `SHA256SUMS`. Teste a atualizacao no dispositivo antes de publicar a release final.

## Estado da chave

O keystore que assinou a v2.0.1 **nao pode ser recuperado de um APK publico**, nem pode ser substituido por uma nova chave sem comprometer a compatibilidade das atualizacoes Android anteriores. Se o responsavel nao tiver o arquivo original e o alias/senhas, esta trilha de assinatura local fica bloqueada. Compilar sem assinatura e uma etapa de preparacao, nao uma validacao de release oficial.

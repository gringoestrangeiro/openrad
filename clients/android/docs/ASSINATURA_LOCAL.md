# Assinatura local do OpenRad Android 2.4.0

O GitHub Actions gera **somente o APK sem assinatura**; ele nao recebe chaves privadas nem senhas. A assinatura de producao e feita exclusivamente no Windows, com o **novo keystore de producao 2.4.0**, por um responsavel autorizado. Nao use a chave SSH do GitHub ou a debug keystore.

1. No GitHub Actions, baixe o artefato `openrad-2.4.0-android-UNSIGNED-for-local-signing` e extraia `openrad-2.4.0-android-UNSIGNED.apk` e `SHA256SUMS` para a pasta `dist/android-local-signing/` do projeto.
2. No **Android Studio > Settings > Android SDK > SDK Tools**, instale Android SDK Build-Tools **34.0.0**, se ainda nao estiver instalado. Java 17 ou posterior e `gh` CLI tambem sao necessarios.
3. Em PowerShell na raiz do repositorio, execute:
   
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\sign-android-local.ps1 -Keystore "D:\caminho\privado\chave-2.4.0.p12" -Alias "openrad-release"
   ```

   Para garantir que o APK sem assinatura corresponde ao commit que sera lancado, acrescente `-ExpectedCommit <SHA completo de 40 caracteres>`: o script compara com a linha `Source commit` de `README-FIRST.txt` (extraido junto do APK) e recusa um APK construido de outro commit. O script tambem recusa um `-OutputApk` que seja o keystore ou qualquer arquivo de entrada.

   Tanto o APK de saida quanto o manifesto `SHA256SUMS` sao protegidos contra colisao com qualquer entrada. Use caminhos sem junctions ou links simbolicos para entradas e saidas: o assinador recusa reparse points, inclusive nos diretorios ancestrais. A regressao sintetica pode ser executada com `./scripts/test-sign-android-paths.ps1`, sem chaves ou SDK.

   O script pede **duas senhas no proprio terminal**, ocultando a digitacao. Elas sao entregues ao assinador via entrada padrao; nao coloque senhas em comandos, tickets, commits, arquivos compartilhados ou no chat.

4. O script baixa o APK oficial v2.3.3 do repositorio, verifica o certificado antigo, o pacote `com.openrad.mobile`, `versionCode=10`, `versionName=2.4.0`, a assinatura v2/v3 e a integridade SHA-256. Ele exige o certificado legado conhecido e o novo certificado fixado para 2.4.0.
5. So apos todas as verificacoes, o resultado vai para `dist/android-release-signed/openrad-2.4.0-android.apk` com `SHA256SUMS`. Teste uma instalacao limpa no dispositivo antes de publicar a release final.

## Estado da chave

A chave privada anterior foi perdida. A 2.4.0 usa uma nova chave RSA de producao; nao e uma atualizacao compativel com instalacoes anteriores. Preserve dados importantes antes de desinstalar.

Certificado SHA-256 da nova chave: `d640c7714a7998077b102736d0598d367457e6babbc9774b0051f12294848221`. Guarde o keystore e a senha em backups separados, criptografados e recuperaveis; nao os envie ao GitHub. A perda dessa chave exigiria outra instalacao limpa.

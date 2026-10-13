#!/usr/bin/env bash
# Compila o APK de debug. Uso: ./scripts/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
  echo "ERRO: SDK não encontrado. Rode primeiro: ./scripts/setup-android-sdk.sh" >&2
  exit 1
fi

if [ ! -x ./gradlew ]; then
  echo "ERRO: gradlew não encontrado. Rode com Gradle instalado ou regenere o wrapper." >&2
  exit 1
fi

./gradlew :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"

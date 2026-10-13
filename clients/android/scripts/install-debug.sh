#!/usr/bin/env bash
# Instala o APK debug no aparelho/emulador conectado. Uso: ./scripts/install-debug.sh
set -euo pipefail
cd "$(dirname "$0")/.."
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "APK não encontrado, compilando…"
  ./scripts/build.sh
fi
adb install -r "$APK"

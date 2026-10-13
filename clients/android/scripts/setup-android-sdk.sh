#!/usr/bin/env bash
# Instala o Android SDK (uma vez). Idempotente: pula o que já existe.
# Uso: ./scripts/setup-android-sdk.sh
set -euo pipefail

ANDROID_HOME_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_VERSION="11076708"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
PACKAGES="platform-tools platforms;android-34 build-tools;34.0.0"

echo "==> Android SDK em: $ANDROID_HOME_DIR"
mkdir -p "$ANDROID_HOME_DIR"

if [ ! -x "$ANDROID_HOME_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Baixando commandlinetools (~150 MB)…"
  TMP="$(mktemp -d)"
  curl -fSL "$CMDLINE_URL" -o "$TMP/cmdtools.zip"
  mkdir -p "$ANDROID_HOME_DIR/cmdline-tools"
  unzip -q "$TMP/cmdtools.zip" -d "$TMP/cmdtools"
  rm -rf "$ANDROID_HOME_DIR/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME_DIR/cmdline-tools/latest"
  mv "$TMP/cmdtools/cmdline-tools/"* "$ANDROID_HOME_DIR/cmdline-tools/latest/"
  rm -rf "$TMP"
else
  echo "==> commandlinetools já instalado, pulando download."
fi

export ANDROID_HOME="$ANDROID_HOME_DIR"
export PATH="$ANDROID_HOME_DIR/cmdline-tools/latest/bin:$ANDROID_HOME_DIR/platform-tools:$PATH"

echo "==> Aceitando licenças…"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "==> Instalando: $PACKAGES"
# shellcheck disable=SC2086
sdkmanager $PACKAGES

# local.properties para o Gradle (não commitado, ver .gitignore)
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
echo "sdk.dir=$ANDROID_HOME_DIR" > "$SCRIPT_DIR/local.properties"
echo "==> local.properties gravado."

echo "==> sdkmanager --list_installed:"
sdkmanager --list_installed | tail -n 15
echo "OK. Agora rode: ./scripts/build.sh"

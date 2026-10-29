#!/usr/bin/env bash
# Сборка MSI-инсталлятора Windows-версии.
#
# jpackage для MSI требует WiX Toolset 3.14 (v4 не поддерживается). Портативные
# бинарники кладутся в ../tools/wix — установка в систему не нужна, но Gradle
# должен видеть их в PATH и в переменной WIX (иначе light.exe не находит
# расширения WixUtilExtension и падает с кодом 311).
#
# Запуск: bash tools/build-msi.sh
set -euo pipefail

cd "$(dirname "$0")/.."

WIX_DIR="${WIX_DIR:-$(cd ../tools/wix 2>/dev/null && pwd || true)}"
JDK_DIR="${SB_JDK:-$(cd ../tools/jdk21 2>/dev/null && pwd || true)}"

if [[ -z "$WIX_DIR" || ! -f "$WIX_DIR/light.exe" ]]; then
  echo "WiX не найден. Скачайте wix314-binaries.zip и распакуйте в ../tools/wix:" >&2
  echo "  https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" >&2
  exit 1
fi

if [[ -z "$JDK_DIR" || ! -f "$JDK_DIR/bin/jpackage.exe" ]]; then
  echo "Нужен полный JDK с jpackage (JBR из Android Studio не подходит):" >&2
  echo "  положите JDK 21 в ../tools/jdk21 или задайте SB_JDK" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$WIX_DIR:$PATH"
export WIX="$WIX_DIR"

./gradlew --stop >/dev/null 2>&1 || true
./gradlew packageMsi

MSI=$(ls -1 build/compose/binaries/main/msi/*.msi 2>/dev/null | head -1 || true)
if [[ -n "$MSI" ]]; then
  echo "готово: $MSI"
else
  echo "MSI не появился — смотрите build/compose/logs/packageMsi/" >&2
  exit 1
fi

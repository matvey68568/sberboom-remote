#!/usr/bin/env bash
# Сборка MSI-установщика Windows-версии (WinUI 3, C#/.NET).
#
# Нужны: собранный publish в ../dist (включая .xbf — они не попадают в publish
# автоматически) и WiX Toolset 3.14 в ../../tools/wix (портативные бинарники,
# установка в систему не требуется).
#
# Запуск: bash installer/build-msi.sh
set -euo pipefail

cd "$(dirname "$0")/.."

WIX_DIR="${WIX_DIR:-$(cd ../tools/wix 2>/dev/null && pwd || true)}"
# native-инструментам (heat/candle/light) нужны пути Windows, а не MSYS
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
DIST="$(winpath "$(pwd)/dist")"
WIX_DIR="$(winpath "$WIX_DIR")"
cd "$(dirname "$0")/.."


if [[ -z "$WIX_DIR" || ! -f "$WIX_DIR/light.exe" ]]; then
  echo "WiX не найден. Скачайте wix314-binaries.zip в ../tools/wix:" >&2
  echo "  https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" >&2
  exit 1
fi

if [[ ! -f "$DIST/SberBoomRemote.exe" ]]; then
  echo "Нет $DIST/SberBoomRemote.exe — сначала соберите приложение:" >&2
  echo "  dotnet publish -c Release -o dist && cp bin/Release/net9.0-windows10.0.26100.0/*.xbf dist/" >&2
  exit 1
fi

# .xbf (скомпилированный XAML) публикация не копирует — без них приложение не стартует
for xbf in bin/Release/net9.0-windows10.0.26100.0/*.xbf; do
  cp -f "$xbf" "$DIST/"
done

echo "== heat: список файлов =="
"$WIX_DIR/heat.exe" dir "$DIST" -cg AppFiles -dr INSTALLFOLDER -gg -g1 -sfrag -srd \
  -sreg -scom -var var.SourceDir -out "$(winpath "$(pwd)/installer/Files.wxs")"

echo "== candle =="
"$WIX_DIR/candle.exe" -nologo -arch x64 -dSourceDir="$DIST" \
  -out "$(winpath "$(pwd)/installer/installer.wixobj")" "$(winpath "$(pwd)/installer/installer.wxs")"
"$WIX_DIR/candle.exe" -nologo -arch x64 -dSourceDir="$DIST" \
  -out "$(winpath "$(pwd)/installer/Files.wixobj")" "$(winpath "$(pwd)/installer/Files.wxs")"

VERSION=$(grep -oE '<Version>[0-9]+\.[0-9]+\.[0-9]+' SberBoom.WinUI.csproj | head -1 | sed 's/<Version>//')
OUT="../SberBoomWinUI3-${VERSION}-x64.msi"

echo "== light =="
"$WIX_DIR/light.exe" -nologo -ext WixUIExtension -cultures:ru-ru \
  -sice:ICE38 -sice:ICE43 -sice:ICE57 -sice:ICE61 -sice:ICE64 -sice:ICE91 \
  -out "$(winpath "$(pwd)/../$(basename "$OUT")")" "$(winpath "$(pwd)/installer/installer.wixobj")" "$(winpath "$(pwd)/installer/Files.wixobj")"

echo "готово: $(cd .. && pwd)/$(basename "$OUT")"

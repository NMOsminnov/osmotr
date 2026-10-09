#!/usr/bin/env bash
# Из WSL: собрать установщик на Windows, очистить Windows и iPhone до «чистых» и запустить.
#   installer/tools/win-test.sh [путь к Osmotr.ipa]
# Сборка — в C:\Users\<я>\osmotr-build (собирать из Windows с диска WSL медленно); нужны Rust и
# C++ Build Tools на Windows (winget: Rustlang.Rustup, Microsoft.VisualStudio.2022.BuildTools).
set -eo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
user=$(cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r')
W=/mnt/c/Users/$user/osmotr-build
WW="C:\\Users\\$user\\osmotr-build"
mkdir -p "$W/bundle"
rsync -a --delete --exclude target --exclude app/gen "$here/" "$W/installer/"
[ -n "$1" ] && cp "$1" "$W/bundle/Osmotr.ipa"
[ -f "$W/bundle/Osmotr.ipa" ] || { echo "Нет $W/bundle/Osmotr.ipa — передайте путь к IPA"; exit 1; }
if [ ! -f "$W/bundle/ipatool.exe" ]; then
  curl -fsSL -o "$W/bundle/ipatool.tar.gz" https://github.com/majd/ipatool/releases/download/v2.6.0/ipatool-2.6.0-windows-amd64.tar.gz
  mkdir -p "$W/bundle/it" && tar -xzf "$W/bundle/ipatool.tar.gz" -C "$W/bundle/it" && cp "$(find "$W/bundle/it" -name '*.exe' | head -1)" "$W/bundle/ipatool.exe"
fi
cd /mnt/c
powershell.exe -NoProfile -Command "\$env:Path += \";\$env:USERPROFILE\\.cargo\\bin\"; \$env:OSMOTR_IPA='$WW\\bundle\\Osmotr.ipa'; \$env:IPATOOL_EXE='$WW\\bundle\\ipatool.exe'; Set-Location '$WW\\installer'; cargo build --release -p osmotr-setup-app --features bundled 2>&1 | Select-String -Pattern '^error' -Context 0,8; cargo build --release -p osmotr-setup --bin osmotr-setup-cli 2>&1 | Select-String -Pattern '^error' -Context 0,8; if (-not (Test-Path target\\release\\osmotr-setup-app.exe)) { exit 1 }"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$WW\\installer\\tools\\test.ps1" -Exe "$WW\\installer\\target\\release\\osmotr-setup-app.exe" -Cli "$WW\\installer\\target\\release\\osmotr-setup-cli.exe"

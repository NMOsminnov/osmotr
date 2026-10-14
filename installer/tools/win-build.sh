#!/usr/bin/env bash
# Из WSL: только собрать установщик на Windows — без очистки Windows и iPhone (её делает
# win-test.sh; не запускать, пока кто-то ставит: однажды очистка закрыла установку посреди
# проверки). IPA — только из облака (нужен Mac): путь первым аргументом.
#   installer/tools/win-build.sh <Osmotr.ipa> [имя на рабочем столе]
# Сборка — в C:\Users\<я>\osmotr-build; нужны Rust и C++ Build Tools на Windows.
set -eo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
ipa=${1:?путь к Osmotr.ipa}
name=${2:-Установка Осмотра.exe}
user=$(cmd.exe /c "echo %USERNAME%" 2>/dev/null | tr -d '\r')
W=/mnt/c/Users/$user/osmotr-build
WW="C:\\Users\\$user\\osmotr-build"
mkdir -p "$W/bundle"
rsync -a --delete --exclude target --exclude app/gen "$here/" "$W/installer/"
cp "$ipa" "$W/bundle/Osmotr.ipa"
if [ ! -f "$W/bundle/ipatool.exe" ]; then
  curl -fsSL -o "$W/bundle/ipatool.tar.gz" https://github.com/majd/ipatool/releases/download/v2.6.0/ipatool-2.6.0-windows-amd64.tar.gz
  mkdir -p "$W/bundle/it" && tar -xzf "$W/bundle/ipatool.tar.gz" -C "$W/bundle/it" && cp "$(find "$W/bundle/it" -name '*.exe' | head -1)" "$W/bundle/ipatool.exe"
fi
cd /mnt/c
powershell.exe -NoProfile -Command "\$env:Path += \";\$env:USERPROFILE\\.cargo\\bin\"; \$env:OSMOTR_IPA='$WW\\bundle\\Osmotr.ipa'; \$env:IPATOOL_EXE='$WW\\bundle\\ipatool.exe'; Set-Location '$WW\\installer'; cargo test -q -p osmotr-setup 2>&1 | Select-String -Pattern 'test result|^error|FAILED' ; cargo build --release -p osmotr-setup-app --features bundled 2>&1 | Select-String -Pattern '^error' -Context 0,8; if (-not (Test-Path target\\release\\osmotr-setup-app.exe)) { exit 1 }"
desk=$(powershell.exe -NoProfile -Command "[Console]::OutputEncoding=[Text.Encoding]::UTF8; [Environment]::GetFolderPath('Desktop')" | tr -d '\r')
cp "$W/installer/target/release/osmotr-setup-app.exe" "$(wslpath "$desk")/$name"
ls -la "$(wslpath "$desk")/$name"

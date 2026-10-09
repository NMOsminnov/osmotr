# Проверка установщика на чистом: очистка (clean.ps1) и запуск свежего «Установка Осмотра.exe».
#
#   powershell -ExecutionPolicy Bypass -File test.ps1 -Exe путь\osmotr-setup-app.exe -Cli путь\osmotr-setup-cli.exe
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [string]$Cli = ""
)
& (Join-Path $PSScriptRoot 'clean.ps1') -Cli $Cli
if ($LASTEXITCODE -ne 0) { Write-Host 'Очистка не до конца — установку не запускаем.'; exit 1 }
$desk = [Environment]::GetFolderPath('Desktop')
$target = Join-Path $desk 'Установка Осмотра.exe'
Copy-Item $Exe $target -Force
$p = Start-Process $target -PassThru
Write-Host "Установщик запущен: PID $($p.Id)"

# Вернуть Windows (и подключённый iPhone) к состоянию «установщика не было» — каждая проверка
# установщика идёт на чистом (автор, 09.10.2026: «каждый раз ставим на чистую… скрипт клинер и установка»).
#
#   powershell -ExecutionPolicy Bypass -File clean.ps1 [-Cli путь\osmotr-setup-cli.exe] [-KeepPhone]
#
# Что снимается:
#   • запущенный установщик (и его окно WebView2);
#   • с iPhone (если подключён и знаком) — «Осмотр», SideStore, LocalDevVPN;
#   • драйверы Apple (Apple Mobile Device Support, Apple Application Support) — тихо;
#   • записи пары с телефонами (C:\ProgramData\Apple\Lockdown) — iPhone снова спросит «Доверять?»;
#   • папка установщика %LOCALAPPDATA%\OsmotrSetup — скачанный iTunes, вход Apple ID, сертификат;
#   • данные окна установщика %LOCALAPPDATA%\kg.osmotr.setup.
# Права администратора — один запрос Windows («Да»).
param(
    [string]$Cli = "",
    [switch]$KeepPhone,
    [switch]$Elevated
)
$ErrorActionPreference = 'Continue'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    # Телефон — до повышения прав: служба Apple ещё стоит, приложения снимаются обычным пользователем.
    Get-Process | Where-Object { $_.ProcessName -like 'osmotr-setup*' -or $_.ProcessName -like 'Установка Осмотра*' } | Stop-Process -Force -ErrorAction SilentlyContinue
    if (-not $KeepPhone -and $Cli -and (Test-Path $Cli)) { & $Cli clean-phone }
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Elevated', '-KeepPhone')
    $p = Start-Process powershell -Verb RunAs -ArgumentList $argList -Wait -PassThru
    # Повышенная часть идёт в своём окне — её журнал показываем здесь.
    $log = Join-Path $env:TEMP 'osmotr-clean.log'
    if (Test-Path $log) { Get-Content $log -Encoding UTF8 | Where-Object { $_ -notmatch '^\*{5,}|^(Windows PowerShell|Start time|End time|Username|RunAs|Configuration|Machine|Host Application|Process ID|PSVersion|PSEdition|PSCompatible|BuildVersion|CLRVersion|WSManStack|PSRemoting|SerializationVersion|Transcript)' } }
    exit $p.ExitCode
}

Start-Transcript -Path (Join-Path $env:TEMP 'osmotr-clean.log') -Force | Out-Null

Write-Host '— Драйверы Apple'
$keys = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
Get-ItemProperty $keys -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -match '^Apple (Mobile Device Support|Application Support)' -and $_.PSChildName -match '^\{' } |
    ForEach-Object {
        Write-Host "   удаляем $($_.DisplayName)"
        Start-Process msiexec -ArgumentList @('/x', $_.PSChildName, '/qn', '/norestart') -Wait
    }

# Служба исчезает не сразу после удаления — подождать, иначе проверка в конце врёт «не до конца».
for ($i = 0; $i -lt 30 -and (Get-Service 'Apple Mobile Device Service' -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }

Write-Host '— Знакомства с телефонами'
Remove-Item 'C:\ProgramData\Apple\Lockdown\*' -Force -Recurse -ErrorAction SilentlyContinue

Write-Host '— Папка установщика и данные его окна'
$local = [Environment]::GetFolderPath('LocalApplicationData')
$work = Join-Path $local 'OsmotrSetup'
foreach ($d in @($work, (Join-Path $local 'kg.osmotr.setup'))) {
    for ($i = 0; $i -lt 5 -and (Test-Path $d); $i++) { Remove-Item $d -Force -Recurse -ErrorAction SilentlyContinue; if (Test-Path $d) { Start-Sleep 1 } }
}

$left = @()
if (Get-Service 'Apple Mobile Device Service' -ErrorAction SilentlyContinue) { $left += 'служба Apple Mobile Device осталась' }
if (Test-Path $work) { $left += "папка $work осталась" }
if ($left) { Write-Host ('Не до конца: ' + ($left -join '; ')); Stop-Transcript | Out-Null; exit 1 }
Write-Host 'Чисто.'
Stop-Transcript | Out-Null

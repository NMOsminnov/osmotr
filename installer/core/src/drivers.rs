//! Драйверы Apple на Windows: без них компьютер не видит iPhone. Ставим не весь iTunes, а две его
//! части — Apple Application Support и Apple Mobile Device Support (служба, через которую идёт
//! вся связь с телефоном). Берём из установщика iTunes с сайта Apple: он распаковывается ключом
//! `/extract`, части ставятся тихо одним запросом прав администратора.

use std::path::Path;
#[cfg(windows)]
use std::time::Duration;

use crate::{Failure, R, phone};

/// Установщик iTunes (64 бит) — сайт Apple перенаправляет на свежий выпуск.
pub const ITUNES_URL: &str = "https://www.apple.com/itunes/download/win64";

/// Служба Apple уже работает — ставить нечего.
pub async fn ready() -> bool {
    phone::service_ready().await
}

/// Поставить драйверы; [progress] — что сейчас делается.
pub async fn install(work: &Path, progress: impl Fn(&str, f32)) -> R<()> {
    #[cfg(not(windows))]
    {
        let _ = (work, &progress);
        return Err(Failure::new("Нет службы для связи с iPhone", "запустите usbmuxd (на Linux — пакет usbmuxd)"));
    }
    #[cfg(windows)]
    {
        // Служба есть, но остановлена — запустить (тоже с правами администратора).
        if service_exists().await {
            progress("Запускаем службу Apple", 0.9);
            elevated("Start-Service -Name 'Apple Mobile Device Service'").await?;
            return wait_ready().await;
        }
        let dir = work.join("itunes");
        tokio::fs::create_dir_all(&dir).await.map_err(|e| Failure::new("Не создали папку для драйверов", e))?;
        let setup = dir.join("iTunes64Setup.exe");
        if !setup.exists() {
            crate::apps::download(ITUNES_URL, &setup, |p| progress("Скачиваем драйверы Apple", p * 0.8)).await?;
        }
        progress("Распаковываем драйверы", 0.82);
        let st = tokio::process::Command::new(&setup).arg("/extract").current_dir(&dir).status().await
            .map_err(|e| Failure::new("Установщик драйверов не запустился", e))?;
        let msi = |name: &str| dir.join(name);
        if !st.success() || !msi("AppleMobileDeviceSupport64.msi").exists() {
            return Err(Failure::new("Не распаковали драйверы Apple — поставьте iTunes с сайта apple.com и запустите установку снова",
                format!("/extract → {st:?}")));
        }
        progress("Ставим драйверы Apple — Windows спросит разрешение, нажмите «Да»", 0.85);
        let script = format!(
            "$ErrorActionPreference='Stop'; \
             foreach ($m in @('{a}','{b}')) {{ if (Test-Path $m) {{ $p = Start-Process msiexec -ArgumentList @('/i', ('\"' + $m + '\"'), '/qn', '/norestart') -Wait -PassThru; if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) {{ exit $p.ExitCode }} }} }}",
            a = msi("AppleApplicationSupport64.msi").display(), b = msi("AppleMobileDeviceSupport64.msi").display());
        elevated(&script).await?;
        progress("Ждём службу Apple", 0.97);
        wait_ready().await
    }
}

#[cfg(windows)]
async fn service_exists() -> bool {
    tokio::process::Command::new("sc").args(["query", "Apple Mobile Device Service"]).creation_flags(0x0800_0000)
        .output().await.map(|o| o.status.success()).unwrap_or(false)
}

/// Выполнить PowerShell-команду с правами администратора (одно окно «Разрешить изменения?»).
#[cfg(windows)]
async fn elevated(script: &str) -> R<()> {
    use base64::Engine;
    let utf16: Vec<u8> = script.encode_utf16().flat_map(|c| c.to_le_bytes()).collect();
    let encoded = base64::engine::general_purpose::STANDARD.encode(utf16);
    let outer = format!(
        "$p = Start-Process powershell -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList @('-NoProfile','-EncodedCommand','{encoded}'); exit $p.ExitCode");
    let st = tokio::process::Command::new("powershell").args(["-NoProfile", "-Command", &outer]).creation_flags(0x0800_0000)
        .status().await.map_err(|e| Failure::new("Не запустилась установка драйверов", e))?;
    if st.success() { Ok(()) } else {
        Err(Failure::new("Драйверы не поставились — в окне Windows нужно нажать «Да»", format!("{st:?}")))
    }
}

#[cfg(windows)]
async fn wait_ready() -> R<()> {
    for _ in 0..60 {
        if ready().await { return Ok(()) }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err(Failure::new("Служба Apple не запустилась — перезагрузите компьютер и запустите установку снова", "Apple Mobile Device Service"))
}

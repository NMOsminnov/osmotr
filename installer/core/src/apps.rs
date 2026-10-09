//! Приложения на телефон: SideStore (последний выпуск с GitHub), «Осмотр» (IPA, что лежит рядом с
//! установщиком или внутри него) и LocalDevVPN из App Store (через ipatool — тем же Apple ID).

use std::path::{Path, PathBuf};

use futures::StreamExt;
use idevice::{IdeviceService, afc::AfcClient, installation_proxy::InstallationProxyClient};
use tokio::io::AsyncWriteExt;

use crate::{Explain, Failure, R, account::Signer, phone::Phone};

pub const SIDESTORE_URL: &str = "https://github.com/SideStore/SideStore/releases/latest/download/SideStore.ipa";
/// LocalDevVPN в App Store (apps.apple.com/app/localdevvpn/id6755608044) — без него SideStore
/// не продлевает подпись (docs.sidestore.io).
pub const LOCALDEVVPN_ID: i64 = 6755608044;

/// Скачать [url] в [dest]; [progress] — доля 0…1.
pub async fn download(url: &str, dest: &Path, progress: impl Fn(f32)) -> R<()> {
    let client = reqwest::Client::builder().user_agent("osmotr-setup").build().explain("Нет интернета")?;
    let resp = client.get(url).send().await.explain("Нет интернета — не скачали файл")?;
    if !resp.status().is_success() {
        return Err(Failure::new("Сервер не отдал файл", format!("{url}: {}", resp.status())));
    }
    let total = resp.content_length().unwrap_or(0);
    let tmp = dest.with_extension("part");
    let mut out = tokio::fs::File::create(&tmp).await.explain("Не записали файл на диск")?;
    let mut got = 0u64;
    let mut body = resp.bytes_stream();
    while let Some(chunk) = body.next().await {
        let chunk = chunk.explain("Скачивание прервалось — проверьте интернет")?;
        out.write_all(&chunk).await.explain("Не записали файл на диск")?;
        got += chunk.len() as u64;
        if total > 0 { progress(got as f32 / total as f32) }
    }
    out.flush().await.explain("Не записали файл на диск")?;
    drop(out);
    tokio::fs::rename(&tmp, dest).await.explain("Не записали файл на диск")?;
    Ok(())
}

/// Подписать Apple ID человека и поставить на телефон.
pub async fn sign_and_install(signer: &mut Signer, phone: &Phone, ipa: &Path, progress: impl Fn(f32)) -> R<()> {
    let provider = phone.provider()?;
    let cb = |p: f32| { progress(p); std::future::ready(()) };
    signer.install_app(&provider, ipa.to_path_buf(), false, Some(cb), None).await
        .map_err(|e| install_failure(&format!("{e:?}")))?;
    Ok(())
}

/// Отказ установки — по-человечески.
fn install_failure(detail: &str) -> Failure {
    let low = detail.to_lowercase();
    let text = if low.contains("maximum number of apps") || low.contains("3 apps") || low.contains("app id") && low.contains("limit") {
        "На этом Apple ID уже 3 приложения, подписанных бесплатно, — удалите лишнее с iPhone"
    } else if low.contains("devicelocked") || low.contains("locked") {
        "Разблокируйте iPhone"
    } else if low.contains("space") || low.contains("disk") {
        "На iPhone не хватает места"
    } else {
        "Не получилось поставить приложение"
    };
    Failure::new(text, detail)
}

/// SideStore на телефоне: идентификатор (у каждого Apple ID свой — с приставкой команды).
pub async fn sidestore_id(phone: &Phone) -> R<Option<String>> {
    Ok(crate::phone::installed(phone).await?.into_iter().find(|(_, n)| n == "SideStore").map(|(id, _)| id))
}

/// Установлено ли приложение с подписью [name].
pub async fn has_app(phone: &Phone, name: &str) -> R<bool> {
    Ok(crate::phone::installed(phone).await?.iter().any(|(_, n)| n == name))
}

/// Где лежит ipatool (рядом с установщиком).
pub fn ipatool(dir: &Path) -> PathBuf {
    dir.join(if cfg!(windows) { "ipatool.exe" } else { "ipatool" })
}

/// Итог входа в App Store.
pub enum StoreLogin {
    Ok,
    /// Нужен код подтверждения (Apple прислала новый).
    NeedCode,
}

/// Войти в App Store (ipatool) — тем же Apple ID; [code] — второй код подтверждения, если спросят.
pub async fn store_login(tool: &Path, home: &Path, email: &str, password: &str, code: Option<&str>) -> R<StoreLogin> {
    let mut args = vec!["auth", "login", "--non-interactive", "--keychain-passphrase", "osmotr", "-e", email, "-p", password];
    if let Some(c) = code { args.push("--auth-code"); args.push(c); }
    let out = run(tool, home, &args).await?;
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr)).to_lowercase();
    if out.status.success() { return Ok(StoreLogin::Ok) }
    if text.contains("2fa") || text.contains("auth code") || text.contains("auth-code") { return Ok(StoreLogin::NeedCode) }
    Err(Failure::new("App Store не пустил войти", text.trim()))
}

/// Скачать LocalDevVPN из App Store (с «покупкой» бесплатной лицензии на этот Apple ID).
pub async fn store_download(tool: &Path, home: &Path, app_id: i64, dest: &Path) -> R<()> {
    let id = app_id.to_string();
    let path = dest.to_string_lossy().to_string();
    let out = run(tool, home, &["download", "--non-interactive", "--keychain-passphrase", "osmotr", "--purchase", "-i", &id, "-o", &path]).await?;
    if out.status.success() && dest.exists() { return Ok(()) }
    Err(Failure::new("Не скачали LocalDevVPN из App Store", String::from_utf8_lossy(&out.stderr).trim()))
}

async fn run(tool: &Path, home: &Path, args: &[&str]) -> R<std::process::Output> {
    let mut cmd = tokio::process::Command::new(tool);
    cmd.args(args).env("HOME", home).env("USERPROFILE", home).kill_on_drop(true);
    #[cfg(windows)]
    { cmd.creation_flags(0x0800_0000); }  // без чёрного окна консоли
    cmd.output().await.explain("Не запустился ipatool")
}

/// Поставить уже подписанное (из App Store) приложение как есть: на телефон по кабелю и
/// установка — телефон проверит лицензию своего Apple ID.
pub async fn install_store_ipa(phone: &Phone, ipa: &Path) -> R<()> {
    let provider = phone.provider()?;
    let mut afc = AfcClient::connect(&provider).await.explain("Телефон не открыл папку для установки")?;
    let _ = afc.mk_dir("/PublicStaging").await;
    let remote = "/PublicStaging/osmotr-store.ipa";
    let bytes = tokio::fs::read(ipa).await.explain("Не прочитали скачанное приложение")?;
    let mut f = afc.open(remote, idevice::afc::opcode::AfcFopenMode::WrOnly).await.explain("Не передали приложение на телефон")?;
    f.write_entire(&bytes).await.explain("Не передали приложение на телефон")?;
    f.close().await.explain("Не передали приложение на телефон")?;
    let mut ip = InstallationProxyClient::connect(&provider).await.explain("Телефон не принял установку")?;
    ip.install(remote, None).await.map_err(|e| {
        let d = format!("{e:?}");
        let text = if d.to_lowercase().contains("license") || d.contains("ApplicationVerificationFailed") {
            "iPhone не принял LocalDevVPN: телефон вошёл в App Store под другим Apple ID"
        } else { "Не поставили LocalDevVPN" };
        Failure::new(text, d)
    })?;
    Ok(())
}

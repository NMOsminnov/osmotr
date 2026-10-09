//! iPhone по кабелю: найти, познакомиться («Доверять этому компьютеру»), спросить версию,
//! файл пары для SideStore, режим разработчика и доверие разработчику (служба AMFI).

use std::time::Duration;

use idevice::{
    IdeviceError, IdeviceService,
    amfi::AmfiClient,
    house_arrest::HouseArrestClient,
    installation_proxy::InstallationProxyClient,
    lockdown::LockdownClient,
    provider::UsbmuxdProvider,
    remote_pairing::{RemotePairingLockdownService, RpPairingFile},
    usbmuxd::{Connection, UsbmuxdAddr, UsbmuxdConnection, UsbmuxdDevice},
};

use crate::{Explain, Failure, R};

/// Имя, под которым программа знакомится с телефоном (видно в «Доверять ли…»).
pub const LABEL: &str = "osmotr-setup";

/// Подключённый телефон: как к нему обращаться и что о нём известно.
#[derive(Clone)]
pub struct Phone {
    pub udid: String,
    pub name: String,
    pub version: String,
    device: UsbmuxdDevice,
}

impl Phone {
    pub fn provider(&self) -> R<UsbmuxdProvider> {
        let addr = UsbmuxdAddr::from_env_var().explain("Не нашли службу Apple Mobile Device")?;
        Ok(self.device.to_provider(addr, LABEL))
    }

    /// iOS не ниже [major].[minor].
    pub fn ios_at_least(&self, major: u32, minor: u32) -> bool {
        let mut p = self.version.split('.').map(|s| s.chars().take_while(|c| c.is_ascii_digit()).collect::<String>().parse::<u32>().unwrap_or(0));
        (p.next().unwrap_or(0), p.next().unwrap_or(0)) >= (major, minor)
    }
}

pub async fn usbmux() -> R<UsbmuxdConnection> {
    UsbmuxdConnection::default().await.explain("Не отвечает служба Apple Mobile Device")
}

/// Служба Apple для связи с iPhone работает (драйверы стоят).
pub async fn service_ready() -> bool {
    usbmux().await.is_ok()
}

/// Первый iPhone на кабеле; нет — None.
pub async fn find_usb() -> R<Option<UsbmuxdDevice>> {
    let mut mux = usbmux().await?;
    let devs = mux.get_devices().await.explain("Не получили список устройств")?;
    Ok(devs.into_iter().find(|d| matches!(d.connection_type, Connection::Usb)))
}

/// iPhone на шине USB, даже если служба Apple его ещё не видит: при первом подключении Windows
/// сама ставит ему драйвер — до минуты, и всё это время программа иначе молча ждала бы.
pub async fn usb_present() -> bool {
    #[cfg(windows)]
    {
        let out = tokio::process::Command::new("powershell")
            .args(["-NoProfile", "-Command", "if (Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -match 'VID_05AC' }) { 'yes' }"])
            .creation_flags(0x0800_0000).output().await;
        return out.map(|o| String::from_utf8_lossy(&o.stdout).contains("yes")).unwrap_or(false);
    }
    #[cfg(not(windows))]
    false
}

/// Есть ли уже знакомство (запись пары) с этим телефоном.
pub async fn paired(udid: &str) -> bool {
    match usbmux().await {
        Ok(mut mux) => mux.get_pair_record(udid).await.is_ok(),
        Err(_) => false,
    }
}

/// Чем кончилась попытка познакомиться.
pub enum PairOutcome {
    Paired,
    /// Телефон заблокирован — разблокировать и смотреть на экран.
    Locked,
    /// Нажали «Не доверять».
    Denied,
}

/// Познакомиться с телефоном: на iPhone появляется «Доверять этому компьютеру?» — ждём, пока
/// человек нажмёт и введёт код; запись пары сохраняем в службе Apple (её же читают все шаги).
pub async fn pair(device: &UsbmuxdDevice) -> R<PairOutcome> {
    let mut mux = usbmux().await?;
    let buid = mux.get_buid().await.explain("Служба Apple не назвала идентификатор компьютера")?;
    let addr = UsbmuxdAddr::from_env_var().explain("Не нашли службу Apple Mobile Device")?;
    let provider = device.to_provider(addr, LABEL);
    let mut lc = LockdownClient::connect(&provider).await.explain("Телефон не отвечает по кабелю")?;
    let host_id = uuid::Uuid::new_v4().to_string().to_uppercase();
    match lc.pair(host_id, buid, Some("Осмотр — установка")).await {
        Ok(mut record) => {
            record.udid = Some(device.udid.clone());
            let bytes = record.serialize().explain("Не записали знакомство с телефоном")?;
            mux.save_pair_record(&device.udid, bytes).await.explain("Служба Apple не сохранила знакомство с телефоном")?;
            Ok(PairOutcome::Paired)
        }
        Err(IdeviceError::PasswordProtected) => Ok(PairOutcome::Locked),
        Err(IdeviceError::UserDeniedPairing) => Ok(PairOutcome::Denied),
        Err(e) => Err(Failure::new("Не получилось познакомиться с телефоном", format!("{e:?}"))),
    }
}

/// Имя и версия iOS — уже после знакомства.
pub async fn describe(device: UsbmuxdDevice) -> R<Phone> {
    let addr = UsbmuxdAddr::from_env_var().explain("Не нашли службу Apple Mobile Device")?;
    let provider = device.to_provider(addr, LABEL);
    let mut lc = LockdownClient::connect(&provider).await.explain("Телефон не отвечает по кабелю")?;
    let name = lc.get_value(Some("DeviceName"), None).await.ok().and_then(|v| v.as_string().map(str::to_string)).unwrap_or_else(|| "iPhone".into());
    let version = lc.get_value(Some("ProductVersion"), None).await.explain("Телефон не назвал версию iOS")?
        .as_string().unwrap_or("0").to_string();
    Ok(Phone { udid: device.udid.clone(), name, version, device })
}

/// Файл пары для SideStore: с ним SideStore сам продлевает подпись по Wi-Fi, без компьютера.
/// Как у iLoader: запись пары (и Wi-Fi-отладка), а с iOS 17.4 — ещё «удалённая» пара.
pub async fn sidestore_pairing(phone: &Phone) -> R<Vec<u8>> {
    let provider = phone.provider()?;
    let mut mux = usbmux().await?;
    let mut record = mux.get_pair_record(&phone.udid).await.explain("Нет знакомства с телефоном")?;
    record.udid = Some(phone.udid.clone());
    let mut lc = LockdownClient::connect(&provider).await.explain("Телефон не отвечает по кабелю")?;
    lc.start_session(&record).await.explain("Телефон не открыл сеанс")?;
    lc.set_value("EnableWifiDebugging", true.into(), Some("com.apple.mobile.wireless_lockdown")).await
        .explain("Телефон не включил связь по Wi-Fi для SideStore")?;
    let lockdown = plist::Value::from_reader_xml(std::io::Cursor::new(record.serialize().explain("Запись пары не читается")?))
        .explain("Запись пары не читается")?;
    let mut dict = lockdown.as_dictionary().cloned().unwrap_or_default();
    if phone.ios_at_least(17, 4) {
        let service = RemotePairingLockdownService::connect(&provider).await.explain("Телефон не дал удалённую пару")?;
        let host = format!("osmotr-{}", &uuid::Uuid::new_v4().simple().to_string()[..6]);
        let mut client = service.into_client(&host).explain("Телефон не дал удалённую пару")?;
        let mut rp = RpPairingFile::generate(&host);
        client.connect(&mut rp, async || "000000".to_string()).await.explain("Телефон не дал удалённую пару")?;
        let rp = plist::Value::from_reader_xml(std::io::Cursor::new(rp.to_bytes())).explain("Удалённая пара не читается")?;
        if let Some(extra) = rp.as_dictionary() { for (k, v) in extra { dict.insert(k.clone(), v.clone()); } }
    }
    let mut out = Vec::new();
    plist::Value::Dictionary(dict).to_writer_xml(&mut out).explain("Файл пары не записался")?;
    Ok(out)
}

/// Положить файл пары в «Документы» SideStore — туда, где он его ищет.
pub async fn place_in_sidestore(phone: &Phone, bundle_id: &str, pairing: &[u8]) -> R<()> {
    let provider = phone.provider()?;
    let ha = HouseArrestClient::connect(&provider).await.explain("Телефон не открыл папку SideStore")?;
    let mut afc = ha.vend_documents(bundle_id.to_string()).await.explain("Телефон не открыл папку SideStore")?;
    let mut f = afc.open("/Documents/ALTPairingFile.mobiledevicepairing", idevice::afc::opcode::AfcFopenMode::Wr).await
        .explain("Не записали файл пары в SideStore")?;
    f.write_entire(pairing).await.explain("Не записали файл пары в SideStore")?;
    f.close().await.explain("Не записали файл пары в SideStore")?;
    Ok(())
}

/// Установленные приложения: идентификатор → подпись на экране.
pub async fn installed(phone: &Phone) -> R<Vec<(String, String)>> {
    let provider = phone.provider()?;
    let mut ip = InstallationProxyClient::connect(&provider).await.explain("Телефон не дал список приложений")?;
    let apps = ip.get_apps(Some("User"), None).await.explain("Телефон не дал список приложений")?;
    Ok(apps.into_iter().map(|(id, v)| {
        let name = v.as_dictionary().and_then(|d| d.get("CFBundleDisplayName").or(d.get("CFBundleName"))).and_then(|x| x.as_string()).unwrap_or("").to_string();
        (id, name)
    }).collect())
}

/// Удалить приложение с телефона (для проверок на «чистом»).
pub async fn uninstall(phone: &Phone, bundle_id: &str) -> R<()> {
    let provider = phone.provider()?;
    let mut ip = InstallationProxyClient::connect(&provider).await.explain("Телефон не дал удалить приложение")?;
    ip.uninstall(bundle_id, None).await.explain("Телефон не удалил приложение")
}

async fn amfi(phone: &Phone) -> R<AmfiClient> {
    AmfiClient::connect(&phone.provider()?).await.explain("Телефон не ответил о режиме разработчика")
}

/// Включён ли режим разработчика.
pub async fn dev_mode_on(phone: &Phone) -> R<bool> {
    amfi(phone).await?.get_developer_mode_status().await.explain("Телефон не ответил о режиме разработчика")
}

/// Включить режим разработчика без человека — iOS даёт, только если на телефоне нет кода-пароля
/// (тогда телефон сам перезагрузится). Иначе — Err: показать пункт в настройках ([reveal_dev_mode]).
pub async fn enable_dev_mode(phone: &Phone) -> R<()> {
    amfi(phone).await?.enable_developer_mode().await.explain("Телефон не включил режим разработчика сам")
}

/// Показать пункт «Режим разработчика» в «Настройки → Конфиденциальность и безопасность».
pub async fn reveal_dev_mode(phone: &Phone) -> R<()> {
    amfi(phone).await?.reveal_developer_mode_option_in_ui().await.explain("Телефон не показал пункт «Режим разработчика»")
}

/// После перезагрузки — окно «Включить режим разработчика?» на телефоне.
pub async fn accept_dev_mode(phone: &Phone) -> R<()> {
    amfi(phone).await?.accept_developer_mode().await.explain("Телефон не показал подтверждение режима разработчика")
}

/// Доверять разработчику (профиль подписи с [profile_uuid]) — без похода в настройки.
pub async fn trust_signer(phone: &Phone, profile_uuid: &str) -> R<bool> {
    amfi(phone).await?.trust_app_signer(profile_uuid.to_string()).await.explain("Телефон не принял доверие разработчику")
}

/// UUID профилей подписи на телефоне (для [trust_signer]).
pub async fn profiles(phone: &Phone) -> R<Vec<String>> {
    let provider = phone.provider()?;
    let mut mis = idevice::misagent::MisagentClient::connect(&provider).await.explain("Телефон не дал профили")?;
    let all = mis.copy_all().await.explain("Телефон не дал профили")?;
    Ok(all.into_iter().filter_map(|der| profile_uuid(&der)).collect())
}

/// UUID из профиля подписи (это PKCS#7 с plist внутри — ищем ключ UUID в тексте).
fn profile_uuid(der: &[u8]) -> Option<String> {
    let start = der.windows(6).position(|w| w == b"<?xml ")?;
    let end = der.windows(8).rposition(|w| w == b"</plist>")? + 8;
    let v = plist::Value::from_reader_xml(std::io::Cursor::new(&der[start..end])).ok()?;
    v.as_dictionary()?.get("UUID")?.as_string().map(str::to_string)
}

/// Подождать: телефон пропал с кабеля (перезагрузка) и вернулся.
/// Служба Apple даёт вернувшемуся телефону новый номер подключения — старый [Phone] после
/// перезагрузки или переподключения кабеля уже не достучится; работать дальше — с возвращённым.
pub async fn wait_back(udid: &str, limit: Duration) -> Option<Phone> {
    let start = std::time::Instant::now();
    while start.elapsed() < limit {
        if let Ok(Some(d)) = find_usb().await && d.udid == udid && let Ok(p) = describe(d).await { return Some(p); }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
    None
}

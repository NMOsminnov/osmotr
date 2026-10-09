//! Ход установки от начала до «Готово». Каждый шаг: сделать сам, а где нужен человек — показать
//! подсказку и ждать, пока телефон сам скажет, что сделано. Не вышло то, без чего можно
//! обойтись (VPN, доверие разработчику), — идём дальше и в конце говорим, что сделать руками.

use std::{path::PathBuf, sync::Arc, time::Duration};

use tokio::sync::{Mutex, mpsc};

use crate::{
    Answer, Event, Failure, R, StepId, StepState, account, apps, drivers,
    phone::{self, PairOutcome, Phone},
};

/// Связь шагов с окном: события туда, ответы оттуда; [work] — папка установщика.
#[derive(Clone)]
pub struct Ctx {
    tx: mpsc::UnboundedSender<Event>,
    answers: Arc<Mutex<mpsc::UnboundedReceiver<Answer>>>,
    pub work: PathBuf,
    /// «Осмотр» — IPA рядом с установщиком или распакованный из него.
    pub osmotr_ipa: PathBuf,
}

impl Ctx {
    pub fn new(tx: mpsc::UnboundedSender<Event>, answers: mpsc::UnboundedReceiver<Answer>, work: PathBuf, osmotr_ipa: PathBuf) -> Self {
        Self { tx, answers: Arc::new(Mutex::new(answers)), work, osmotr_ipa }
    }

    pub fn send(&self, e: Event) { let _ = self.tx.send(e); }

    pub fn step(&self, id: StepId, state: StepState, note: impl Into<String>) {
        self.send(Event::Step { id, state, note: note.into() });
    }

    pub fn hint(&self, title: &str, text: &str, image: Option<&str>) {
        self.send(Event::Hint { title: title.into(), text: text.into(), image: image.map(str::to_string) });
    }

    pub fn hint_done(&self) { self.send(Event::HintDone) }

    /// Apple ID и пароль; [error] — почему прошлый раз не вышло.
    pub async fn ask_credentials(&self, error: Option<String>) -> Option<(String, String)> {
        self.send(Event::AskCredentials { error });
        loop {
            match self.answers.lock().await.recv().await? {
                Answer::Credentials { email, password } => return Some((email, password)),
                _ => continue,
            }
        }
    }

    /// Код подтверждения; None — прислать ещё раз.
    pub async fn ask_code(&self, to: &str, error: Option<String>) -> Option<String> {
        self.send(Event::AskCode { to: to.into(), error });
        loop {
            match self.answers.lock().await.recv().await? {
                Answer::Code { code } => return Some(code.trim().to_string()),
                Answer::Resend => return None,
                _ => continue,
            }
        }
    }
}

/// Вся установка. Ошибка — уже показана окну событием [Event::Failed].
pub async fn run(ctx: Ctx) -> R<()> {
    let result = steps(&ctx).await;
    match &result {
        Ok(left) => ctx.send(Event::Finished { left: left.clone() }),
        Err(f) => ctx.send(Event::Failed { text: f.text.clone(), detail: f.detail.clone() }),
    }
    result.map(|_| ())
}

async fn steps(ctx: &Ctx) -> R<Vec<String>> {
    for id in StepId::ALL { ctx.step(id, StepState::Waiting, "") }
    let mut left = Vec::new();

    // 1. Драйверы.
    ctx.step(StepId::Drivers, StepState::Running, "Проверяем");
    if !drivers::ready().await {
        drivers::install(&ctx.work, |note, _| ctx.step(StepId::Drivers, StepState::Running, note)).await
            .inspect_err(|_| ctx.step(StepId::Drivers, StepState::Failed, ""))?;
    }
    ctx.step(StepId::Drivers, StepState::Done, "");

    // 2. Телефон на кабеле.
    ctx.step(StepId::Phone, StepState::Running, "Ищем iPhone");
    let mut tick = 0u32;
    let device = loop {
        if let Some(d) = phone::find_usb().await? { break d }
        // Раз в 3 с — не готовит ли Windows драйвер уже подключённому iPhone.
        if tick % 3 == 0 {
            if phone::usb_present().await {
                ctx.hint("Windows готовит iPhone", "iPhone подключён — Windows настраивает его в первый раз. Это до минуты. Разблокируйте телефон и подождите.", Some("unlock"));
                ctx.step(StepId::Phone, StepState::Running, "Windows готовит iPhone");
            } else {
                ctx.hint("Подключите iPhone", "Подключите iPhone к компьютеру кабелем и разблокируйте его.", Some("cable"));
                ctx.step(StepId::Phone, StepState::Running, "Ищем iPhone");
            }
        }
        tick += 1;
        tokio::time::sleep(Duration::from_secs(1)).await;
    };
    ctx.hint_done();
    ctx.step(StepId::Phone, StepState::Done, "");

    // 3. Доверие компьютеру.
    ctx.step(StepId::Trust, StepState::Running, "Знакомимся с телефоном");
    if !phone::paired(&device.udid).await {
        loop {
            ctx.hint("На iPhone нажмите «Доверять»",
                "На экране iPhone появится вопрос «Доверять этому компьютеру?». Нажмите «Доверять» и введите код-пароль телефона.", Some("trust"));
            match phone::pair(&device).await? {
                PairOutcome::Paired => break,
                PairOutcome::Locked => {
                    ctx.hint("Разблокируйте iPhone", "Разблокируйте iPhone — тогда на нём появится вопрос «Доверять этому компьютеру?».", Some("unlock"));
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
                PairOutcome::Denied => {
                    ctx.hint("Нажали «Не доверять»", "Отключите кабель от iPhone и подключите снова — вопрос появится ещё раз. Нажмите «Доверять».", Some("trust"));
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
            }
        }
    }
    ctx.hint_done();
    let phone = phone::describe(device).await?;
    ctx.step(StepId::Trust, StepState::Done, format!("{} · iOS {}", phone.name, phone.version));

    // 4. Apple ID.
    ctx.step(StepId::AppleId, StepState::Running, "Введите Apple ID");
    let store = ctx.work.join("account");
    let mut error = None;
    let (email, password, mut signer) = loop {
        let Some((email, password)) = ctx.ask_credentials(error.take()).await else {
            return Err(Failure::new("Установку закрыли", "окно не ответило"));
        };
        ctx.step(StepId::AppleId, StepState::Running, "Входим");
        match account::login(ctx, &email, &password, &store).await {
            Ok(s) => break (email, password, s),
            Err(f) => { error = Some(f.text.clone()); ctx.step(StepId::AppleId, StepState::Running, f.text) }
        }
    };
    ctx.step(StepId::AppleId, StepState::Done, email.clone());

    // 5. SideStore + файл пары.
    ctx.step(StepId::SideStore, StepState::Running, "Скачиваем");
    let ss_ipa = ctx.work.join("SideStore.ipa");
    apps::download(apps::SIDESTORE_URL, &ss_ipa, |g, t| ctx.step(StepId::SideStore, StepState::Running, format!("Скачиваем — {}", apps::of(g, t)))).await?;
    ctx.step(StepId::SideStore, StepState::Running, "Подписываем и ставим");
    apps::sign_and_install(&mut signer, &phone, &ss_ipa, |p| ctx.step(StepId::SideStore, StepState::Running, format!("Ставим — {} %", (p * 100.0) as i32))).await?;
    match apps::sidestore_id(&phone).await? {
        Some(id) => {
            ctx.step(StepId::SideStore, StepState::Running, "Связываем SideStore с телефоном");
            let pairing = phone::sidestore_pairing(&phone).await?;
            phone::place_in_sidestore(&phone, &id, &pairing).await?;
            ctx.step(StepId::SideStore, StepState::Done, "");
        }
        None => return Err(Failure::new("SideStore не появился на телефоне", "нет в списке приложений")),
    }

    // 6. «Осмотр».
    ctx.step(StepId::Osmotr, StepState::Running, "Подписываем и ставим");
    if !ctx.osmotr_ipa.exists() { return Err(Failure::new("Рядом с установщиком нет «Осмотра»", ctx.osmotr_ipa.display())) }
    apps::sign_and_install(&mut signer, &phone, &ctx.osmotr_ipa, |p| ctx.step(StepId::Osmotr, StepState::Running, format!("Ставим — {} %", (p * 100.0) as i32))).await?;
    ctx.step(StepId::Osmotr, StepState::Done, "");

    // 7. LocalDevVPN из App Store — тем же Apple ID. Не вышло — человек поставит сам.
    ctx.step(StepId::Vpn, StepState::Running, "Проверяем");
    if apps::has_app(&phone, "LocalDevVPN").await.unwrap_or(false) {
        ctx.step(StepId::Vpn, StepState::Done, "Уже стоит");
    } else {
        match vpn(ctx, &phone, &email, &password).await {
            Ok(()) => ctx.step(StepId::Vpn, StepState::Done, ""),
            Err(f) => {
                ctx.step(StepId::Vpn, StepState::Manual, f.text.clone());
                left.push("Поставьте из App Store бесплатное приложение LocalDevVPN.".into());
            }
        }
    }
    left.push("Откройте LocalDevVPN, нажмите «Подключить» и разрешите добавить VPN (введите код телефона).".into());

    // 8. Режим разработчика.
    ctx.step(StepId::DevMode, StepState::Running, "Проверяем");
    if !phone::dev_mode_on(&phone).await.unwrap_or(false) {
        dev_mode(ctx, &phone).await?;
    }
    ctx.hint_done();
    ctx.step(StepId::DevMode, StepState::Done, "");

    // 9. Доверие разработчику — без настроек, если телефон даст; иначе — подсказка в конце.
    ctx.step(StepId::Signer, StepState::Running, "Проверяем");
    let trusted = match phone::profiles(&phone).await {
        Ok(ids) if !ids.is_empty() => {
            let mut ok = false;
            for id in ids { if phone::trust_signer(&phone, &id).await.unwrap_or(false) { ok = true } }
            ok
        }
        _ => false,
    };
    if trusted {
        ctx.step(StepId::Signer, StepState::Done, "");
    } else {
        ctx.step(StepId::Signer, StepState::Manual, "Сделать на телефоне");
        left.insert(0, format!("Настройки → Основные → VPN и управление устройством → {email} → «Доверять»."));
    }
    left.push("Откройте SideStore и войдите тем же Apple ID — он сам будет продлевать «Осмотр» раз в неделю (нужен Wi-Fi и включённый LocalDevVPN).".into());
    Ok(left)
}

/// LocalDevVPN: войти в App Store (ipatool) тем же Apple ID, скачать, поставить по кабелю.
async fn vpn(ctx: &Ctx, phone: &Phone, email: &str, password: &str) -> R<()> {
    let tool = apps::ipatool(&ctx.work);
    if !tool.exists() { return Err(Failure::new("Нет ipatool рядом с установщиком", tool.display())) }
    let home = ctx.work.join("store");
    tokio::fs::create_dir_all(&home).await.map_err(|e| Failure::new("Не создали папку", e))?;
    ctx.step(StepId::Vpn, StepState::Running, "Входим в App Store");
    if let apps::StoreLogin::NeedCode = apps::store_login(&tool, &home, email, password, None).await? {
        let mut error = None;
        loop {
            let Some(code) = ctx.ask_code("на ваши устройства Apple — для App Store", error.take()).await else { continue };
            match apps::store_login(&tool, &home, email, password, Some(&code)).await? {
                apps::StoreLogin::Ok => break,
                apps::StoreLogin::NeedCode => error = Some("Код не подошёл — проверьте и введите ещё раз".to_string()),
            }
        }
    }
    ctx.step(StepId::Vpn, StepState::Running, "Скачиваем из App Store");
    let ipa = ctx.work.join("LocalDevVPN.ipa");
    apps::store_download(&tool, &home, apps::LOCALDEVVPN_ID, &ipa).await?;
    ctx.step(StepId::Vpn, StepState::Running, "Ставим");
    apps::install_store_ipa(phone, &ipa).await
}

/// Режим разработчика: без кода-пароля телефон включит сам; с кодом — показываем пункт в
/// настройках и ведём; после перезагрузки — окно подтверждения; ждём, пока станет включён.
async fn dev_mode(ctx: &Ctx, phone: &Phone) -> R<()> {
    if phone::enable_dev_mode(phone).await.is_err() {
        let _ = phone::reveal_dev_mode(phone).await;
        ctx.hint("Включите «Режим разработчика»",
            "На iPhone: Настройки → Конфиденциальность и безопасность → Режим разработчика (в самом низу) → включите → «Перезагрузить». После перезагрузки нажмите «Включить» и введите код телефона.",
            Some("devmode"));
    } else {
        ctx.hint("iPhone перезагружается", "Подождите — телефон включит режим разработчика и перезагрузится. Потом нажмите «Включить» и введите код телефона.", Some("devmode"));
    }
    ctx.step(StepId::DevMode, StepState::Running, "Ждём, пока включите");
    let start = std::time::Instant::now();
    let mut asked_accept = false;
    loop {
        if start.elapsed() > Duration::from_secs(20 * 60) {
            return Err(Failure::new("Не дождались режима разработчика — включите его и запустите установку снова", "20 минут"));
        }
        match phone::dev_mode_on(phone).await {
            Ok(true) => return Ok(()),
            Ok(false) => {}
            // Пропал с кабеля — перезагружается; вернулся — показать подтверждение.
            Err(_) => {
                ctx.step(StepId::DevMode, StepState::Running, "iPhone перезагружается");
                if phone::wait_back(&phone.udid, Duration::from_secs(300)).await && !asked_accept {
                    asked_accept = true;
                    let _ = phone::accept_dev_mode(phone).await;
                    ctx.hint("Нажмите «Включить»", "На iPhone появится вопрос о режиме разработчика — нажмите «Включить» и введите код телефона.", Some("devmode"));
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

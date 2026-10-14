//! Телеметрия установщика — что происходило, шаг за шагом, без личных данных (автор, 14.10.2026:
//! «Надо ещё проблемы с Apple ID решить… Телеметрия для неё в первую очередь… Личные данные не
//! включаем в телеметрию»; правило chuzhie-uchetki-ne-trogat).
//!
//! Журнал — строками в папке установщика (`telemetry/log.txt`): начало сеанса (версия установщика,
//! «Осмотра» внутри, Windows), каждый шаг (начало, итог, длительность), подсказки человеку, связь с
//! серверами Apple и сервером подписи перед входом (доступен ли, за сколько, какая ошибка), фазы
//! входа (подпись запросов, вход, второй фактор — каким путём, доступ к подписи приложений), отказ
//! с подробностями. Ответы человека (Apple ID, пароль, коды) в журнал не попадают вовсе — их
//! получает только вход; каждая строка ещё и проходит [clean]: адреса почты, номера телефонов.
//! Имя телефона не пишется (в нём бывает имя владельца), идентификатор устройства — тоже: только
//! модель и версия iOS. [save] — файл «Осмотр — диагностика установки» для отправки разработчику.

use std::{
    collections::HashMap,
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, Instant},
};

use crate::{Event, StepId, StepState};

struct Log {
    file: PathBuf,
    started: Instant,
    running: HashMap<StepId, Instant>,
}

/// Журнал сеанса; новый сеанс (повтор установки, тест) — заменяет прежний.
static LOG: Mutex<Option<Log>> = Mutex::new(None);

/// Начать сеанс в папке установщика [work]: что за установщик и что внутри.
pub fn init(work: &Path, about: &str) {
    let dir = work.join("telemetry");
    let _ = std::fs::create_dir_all(&dir);
    let file = dir.join("log.txt");
    // Не копить без конца: дорос до мегабайта — прежний в «log.1.txt».
    if std::fs::metadata(&file).map(|m| m.len() > 1 << 20).unwrap_or(false) {
        let _ = std::fs::rename(&file, dir.join("log.1.txt"));
    }
    if let Ok(mut log) = LOG.lock() { *log = Some(Log { file, started: Instant::now(), running: HashMap::new() }); }
    line(&format!("── сеанс: {about} · {} · {}", system(), std::env::consts::ARCH));
    // Падение самой программы — в журнал, затем как обычно.
    let prior = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        line(&format!("ПАДЕНИЕ программы: {info}"));
        prior(info);
    }));
    // Как шёл вход и подпись внутри библиотек (isideload, idevice): уровень «инфо» и выше — каждая
    // строка через clean (адрес Apple ID в «Logging in to Apple ID: …» станет «<почта>»).
    // Вход (isideload) — подробно, уровень «отладка»: запросы к Apple, ответы и коды — «нужен полный
    // лог ошибки» (автор, 14.10.2026); прочие библиотеки — «инфо» (связь с телефоном шумит).
    use tracing_subscriber::{filter::Targets, layer::SubscriberExt, util::SubscriberInitExt};
    let _ = tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer().with_ansi(false).without_time().with_writer(|| Lib(Vec::new())))
        .with(Targets::new().with_default(tracing::Level::INFO).with_target("isideload", tracing::Level::DEBUG))
        .try_init();
}

/// Строки библиотек — в журнал с пометкой «библиотека».
struct Lib(Vec<u8>);
impl Write for Lib {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> { self.0.extend_from_slice(b); Ok(b.len()) }
    fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
}
impl Drop for Lib {
    fn drop(&mut self) {
        let text = String::from_utf8_lossy(&self.0);
        for l in text.lines().filter(|l| !l.trim().is_empty()) { line(&format!("библиотека: {}", l.trim())); }
    }
}

/// Система: на Windows — номер сборки («Windows 10.0.22631»), иначе — имя.
fn system() -> String {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // Без окна консоли (CREATE_NO_WINDOW): программа с окном, мелькание чёрного окна пугает.
        if let Ok(out) = std::process::Command::new("cmd").args(["/c", "ver"]).creation_flags(0x0800_0000).output() {
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !v.is_empty() { return v; }
        }
    }
    std::env::consts::OS.to_string()
}

/// Строка журнала: время, секунды от начала сеанса, текст без личного.
pub fn line(text: &str) {
    let Ok(guard) = LOG.lock() else { return };
    let Some(log) = guard.as_ref() else { return };
    let secs = log.started.elapsed().as_secs_f32();
    let stamp = now();
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&log.file) {
        let _ = writeln!(f, "{stamp} +{secs:7.1}s  {}", clean(text).replace('\n', " ⏎ "));
    }
}

/// Событие окну — в журнал словами; длительность шага — от «идёт» до итога.
pub fn event(e: &Event) {
    let text = describe(e);
    if let Event::Step { id, state, .. } = e {
        let took = match LOG.lock() {
            Ok(mut guard) => match guard.as_mut() {
                Some(log) => match state {
                    StepState::Running => { log.running.entry(*id).or_insert_with(Instant::now); None }
                    _ => log.running.remove(id).map(|t| t.elapsed()),
                },
                None => None,
            },
            Err(_) => None,
        };
        if let Some(d) = took { line(&format!("{text} ({})", secs(d))); return; }
    }
    line(&text);
}

/// Событие словами — без того, что ввёл человек и куда пришёл код.
pub fn describe(e: &Event) -> String {
    match e {
        Event::Step { id, state, note } => format!("шаг «{}»: {} {}", id.title(), state_name(*state), note).trim_end().to_string(),
        Event::Hint { title, .. } => format!("подсказка человеку: {title}"),
        Event::HintDone => "подсказка: сделано".into(),
        Event::AskCredentials { error } => match error {
            Some(e) => format!("снова спрашиваем Apple ID и пароль — прошлый раз: {e}"),
            None => "спрашиваем Apple ID и пароль".into(),
        },
        // Куда пришёл код — только путь (SMS или устройства Apple), без номера.
        Event::AskCode { to, error } => {
            let way = if to.starts_with("SMS") { "по SMS" } else { "на устройства Apple" };
            match error {
                Some(e) => format!("снова спрашиваем код {way} — {e}"),
                None => format!("спрашиваем код {way}"),
            }
        }
        Event::Finished { left } => format!("готово; осталось на телефоне: {}", if left.is_empty() { "ничего".into() } else { left.join("; ") }),
        Event::Failed { text, detail } => format!("ОСТАНОВИЛИСЬ: {text} — {detail}"),
    }
}

fn state_name(s: StepState) -> &'static str {
    match s {
        StepState::Waiting => "ждёт",
        StepState::Running => "идёт",
        StepState::Done => "готово",
        StepState::Manual => "руками",
        StepState::Failed => "не вышло",
    }
}

fn secs(d: Duration) -> String { format!("{:.1} с", d.as_secs_f32()) }

/// Связь с серверами входа и подписи — перед входом: доступен ли каждый (TCP 443) и за сколько.
/// «Apple не пустила» чаще всего — сеть или сервер подписи запросов, а не пароль.
pub async fn probe_servers() {
    for host in ["gsa.apple.com", "idmsa.apple.com", "developerservices2.apple.com", "ani.stikstore.app", "ani.sidestore.io"] {
        let t = Instant::now();
        let r = tokio::time::timeout(Duration::from_secs(6), tokio::net::TcpStream::connect((host, 443))).await;
        let what = match r {
            Ok(Ok(_)) => format!("доступен за {} мс", t.elapsed().as_millis()),
            Ok(Err(e)) => format!("НЕ ДОСТУПЕН: {e}"),
            Err(_) => "НЕ ОТВЕЧАЕТ 6 с".into(),
        };
        line(&format!("связь: {host} — {what}"));
    }
}

/// Без адресов почты и номеров телефонов. Числа короче семи цифр (коды ошибок Apple, время,
/// проценты) остаются; маски Apple вида «(•••) •••-••-56» не трогаются — в них номера уже нет.
pub fn clean(text: &str) -> String {
    // Цвета терминала из вывода программ (ipatool) — прочь: в журнале это мусор «[90m…[0m».
    let plain: String = {
        let mut out = String::with_capacity(text.len());
        let mut it = text.chars().peekable();
        while let Some(c) = it.next() {
            if c == '\u{1b}' && it.peek() == Some(&'[') {
                it.next();
                while let Some(&n) = it.peek() { it.next(); if n.is_ascii_alphabetic() { break; } }
            } else { out.push(c); }
        }
        out
    };
    // Имя пользователя Windows в путях («C:\\Users\\Иван Петров\\…») — у людей это имя и фамилия.
    let plain = users(&plain);
    let text = &tokens(&plain);
    // Почта: слово с «@» и точкой после него.
    let words: Vec<String> = text
        .split_inclusive(|c: char| c.is_whitespace() || "\"',;{}()[]<>".contains(c))
        .map(|w| {
            let core = w.trim_end_matches(|c: char| c.is_whitespace() || "\"',;{}()[]<>".contains(c));
            let at = core.find('@');
            if at.is_some_and(|i| core[i..].contains('.')) { format!("<почта>{}", &w[core.len()..]) } else { w.to_string() }
        })
        .collect();
    let out = words.concat();
    // Телефон: от семи цифр подряд, между ними пробелы, дефисы, скобки; «+» впереди — тоже.
    let chars: Vec<char> = out.chars().collect();
    let mut clean = String::with_capacity(out.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '+' || chars[i].is_ascii_digit() {
            let mut j = i + usize::from(chars[i] == '+');
            let mut digits = 0;
            let mut last_digit = i;
            while j < chars.len() && (chars[j].is_ascii_digit() || " -()".contains(chars[j])) {
                if chars[j].is_ascii_digit() { digits += 1; last_digit = j; }
                j += 1;
            }
            if digits >= 7 {
                clean.push_str("<номер>");
                i = last_digit + 1;
                continue;
            }
            // Не номер — переписать как есть до конца числа.
            let end = last_digit.max(i) + 1;
            clean.extend(&chars[i..end]);
            i = end;
            continue;
        }
        clean.push(chars[i]);
        i += 1;
    }
    clean
}

/// «…\\Users\\<имя>\\» и «/home/<имя>/» → «<пользователь>».
fn users(text: &str) -> String {
    let mut out = text.to_string();
    for marker in ["\\Users\\", "/Users/", "/home/"] {
        let mut from = 0;
        while let Some(i) = out[from..].find(marker) {
            let start = from + i + marker.len();
            let end = out[start..].find(['\\', '/']).map(|j| start + j).unwrap_or(out.len());
            if end > start && end - start < 64 {
                out.replace_range(start..end, "<пользователь>");
            }
            from = start + "<пользователь>".len().min(out.len() - start);
        }
    }
    out
}

/// Длинные токены (сеансы, ключи, отпечатки — от 24 знаков латиницы, цифр и «+/=_-» подряд) — в заглушку.
fn tokens(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    let flush = |run: &mut String, out: &mut String| {
        if run.chars().count() >= 24 && run.chars().any(|c| c.is_ascii_digit()) && run.chars().any(|c| c.is_ascii_alphabetic()) {
            out.push_str("<токен>");
        } else { out.push_str(run); }
        run.clear();
    };
    for c in text.chars() {
        if c.is_ascii_alphanumeric() || "+/=_-".contains(c) { run.push(c) } else { flush(&mut run, &mut out); out.push(c); }
    }
    flush(&mut run, &mut out);
    out
}

fn now() -> String {
    // Время без зависимостей: секунды от начала эпохи → «ГГГГ-ММ-ДД ЧЧ:ММ:СС» (UTC).
    let s = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) as i64;
    let (days, rest) = (s.div_euclid(86_400), s.rem_euclid(86_400));
    let (y, m, d) = civil(days);
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}Z", rest / 3600, rest % 3600 / 60, rest % 60)
}

/// Дни от 1970-01-01 → год, месяц, день (алгоритм Хиннанта).
fn civil(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(m <= 2), m, d)
}

/// Файл диагностики в папку [to] (рабочий стол): журнал этого и прошлых сеансов. Путь — в ответ.
pub fn save(work: &Path, to: &Path) -> std::io::Result<PathBuf> {
    let dir = work.join("telemetry");
    let mut text = String::from("Диагностика установки «Осмотра» на iPhone. Личных данных нет: адреса, телефоны, пароли и коды не пишутся.\n\n");
    for name in ["log.1.txt", "log.txt"] {
        if let Ok(t) = std::fs::read_to_string(dir.join(name)) { text.push_str(&t); }
    }
    let out = to.join(format!("Осмотр — диагностика установки {}.txt", now().replace([':', 'Z'], "").replace(' ', "_")));
    std::fs::write(&out, clean(&text))?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Журнал один на процесс — тесты с ним по очереди.
    static SERIAL: Mutex<()> = Mutex::new(());

    #[test]
    fn почта_и_телефоны_вычищаются_а_коды_ошибок_остаются() {
        let s = clean(r#"AuthFailed { code: -20101, account: "Person.Name@icloud.com", phone: "+996 555 123 456", alt: 0555 12-34-56, ec: -22421, at 87 % }"#);
        assert!(!s.to_lowercase().contains("person.name"), "{s}");
        assert!(!s.contains("555"), "{s}");
        assert!(s.contains("-20101") && s.contains("-22421") && s.contains("87 %"), "{s}");
    }

    #[test]
    fn ответы_человека_и_номер_для_кода_не_попадают() {
        let ask = describe(&Event::AskCode { to: "SMS на +996 555 123 456".into(), error: None });
        assert_eq!(ask, "спрашиваем код по SMS");
        let again = describe(&Event::AskCredentials { error: Some("Неверный Apple ID или пароль".into()) });
        assert!(again.contains("Неверный Apple ID или пароль") && !again.contains('@'), "{again}");
    }

    #[test]
    fn журнал_пишется_и_сохраняется_без_личного() {
        let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let work = std::env::temp_dir().join(format!("osmotr-tel-{}", std::process::id()));
        init(&work, "тест");
        event(&Event::Step { id: StepId::AppleId, state: StepState::Running, note: "входим".into() });
        event(&Event::Failed { text: "Apple не пустила войти".into(), detail: "Bad { account: a.b@icloud.com, -20209 }".into() });
        let out = save(&work, &work).unwrap();
        let t = std::fs::read_to_string(&out).unwrap();
        assert!(t.contains("шаг «Вход в Apple ID»: идёт входим"), "{t}");
        assert!(t.contains("ОСТАНОВИЛИСЬ: Apple не пустила войти") && t.contains("-20209"), "{t}");
        assert!(!t.contains("a.b@icloud.com"), "{t}");
        let _ = std::fs::remove_dir_all(&work);
    }

    #[test]
    fn токены_вычищаются_а_слова_и_коды_нет() {
        let s = clean("session X-Apple-ID-Session-Id: 3F2504E04F8911D39A0C0305E82C3301aa dsid=-20101 Logging in to Apple ID: a.b@icloud.com");
        assert!(s.contains("<токен>") && !s.contains("3F2504E0"), "{s}");
        assert!(s.contains("-20101") && s.contains("<почта>") && s.contains("Logging in to Apple ID"), "{s}");
    }

    #[test]
    fn строки_библиотеки_проходят_очистку() {
        let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let work = std::env::temp_dir().join(format!("osmotr-lib-{}", std::process::id()));
        init(&work, "тест");
        tracing::info!("Logging in to Apple ID: {}", "Person.Name@icloud.com");
        tracing::info!("SMS 2FA required");
        let t = std::fs::read_to_string(work.join("telemetry/log.txt")).unwrap();
        assert!(t.contains("библиотека:") && t.contains("SMS 2FA required"), "{t}");
        assert!(!t.to_lowercase().contains("person.name"), "{t}");
        let _ = std::fs::remove_dir_all(&work);
    }

    #[test]
    fn имя_пользователя_в_путях_вычищается() {
        let s = clean(r"├ C:\Users\Иван Петров\.cargo\registry\src\x.rs:707 и /home/osmin/x");
        assert!(!s.contains("Иван") && !s.contains("osmin"), "{s}");
        assert!(s.contains(r"C:\Users\<пользователь>\.cargo") && s.contains("/home/<пользователь>/x"), "{s}");
    }

    #[test]
    fn цвета_терминала_вычищаются() {
        let s = clean("\u{1b}[90m11:01am\u{1b}[0m \u{1b}[1m\u{1b}[31merr\u{1b}[0m\u{1b}[0m http 204");
        assert_eq!(s, "11:01am err http 204");
    }

    #[test]
    fn дата_по_календарю() {
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_740), (2026, 10, 14));
    }
}

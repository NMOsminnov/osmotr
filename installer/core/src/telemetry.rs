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
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

use crate::{Event, StepId, StepState};

struct Log {
    file: PathBuf,
    started: Instant,
    running: HashMap<StepId, Instant>,
}

static LOG: OnceLock<Mutex<Log>> = OnceLock::new();

/// Начать сеанс в папке установщика [work]: что за установщик и что внутри.
pub fn init(work: &Path, about: &str) {
    let dir = work.join("telemetry");
    let _ = std::fs::create_dir_all(&dir);
    let file = dir.join("log.txt");
    // Не копить без конца: дорос до мегабайта — прежний в «log.1.txt».
    if std::fs::metadata(&file).map(|m| m.len() > 1 << 20).unwrap_or(false) {
        let _ = std::fs::rename(&file, dir.join("log.1.txt"));
    }
    let _ = LOG.set(Mutex::new(Log { file, started: Instant::now(), running: HashMap::new() }));
    line(&format!("── сеанс: {about} · {} {}", std::env::consts::OS, std::env::consts::ARCH));
}

/// Строка журнала: время, секунды от начала сеанса, текст без личного.
pub fn line(text: &str) {
    let Some(log) = LOG.get() else { return };
    let Ok(log) = log.lock() else { return };
    let secs = log.started.elapsed().as_secs_f32();
    let stamp = now();
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&log.file) {
        let _ = writeln!(f, "{stamp} +{secs:7.1}s  {}", clean(text).replace('\n', " ⏎ "));
    }
}

/// Событие окну — в журнал словами; длительность шага — от «идёт» до итога.
pub fn event(e: &Event) {
    let text = describe(e);
    if let (Event::Step { id, state, .. }, Some(log)) = (e, LOG.get()) {
        if let Ok(mut log) = log.lock() {
            match state {
                StepState::Running => { log.running.entry(*id).or_insert_with(Instant::now); }
                _ => if let Some(t) = log.running.remove(id) {
                    drop(log);
                    line(&format!("{text} ({})", secs(t.elapsed())));
                    return;
                },
            }
        }
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
    for host in ["gsa.apple.com", "idmsa.apple.com", "developerservices2.apple.com", "ani.sidestore.io"] {
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
    fn дата_по_календарю() {
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_740), (2026, 10, 14));
    }
}

//! Вход в Apple ID человека — им подписываются SideStore и «Осмотр» (бесплатно, на 7 дней;
//! продлевает SideStore). Пароль никуда не пишется; сертификат и данные входа — в папке установщика.

use std::path::Path;

use futures::FutureExt;
use isideload::{
    anisette::remote_v3::RemoteV3AnisetteProvider,
    auth::apple_account::{AppleAccount, TwoFactorCallbackParams, TwoFactorCallbackResponse},
    dev::developer_session::DeveloperSession,
    sideload::{SideloaderBuilder, TeamSelection, builder::MaxCertsBehavior, sideloader::Sideloader},
    util::{callbacks::MaxCertsCallbackBox, fs_storage::FsStorage},
};

use crate::{Explain, Failure, R, flow::Ctx, telemetry};

pub type Signer = Sideloader<MaxCertsCallbackBox>;

/// Войти и получить подписчика. Код подтверждения спрашивается у человека через окно.
pub async fn login(ctx: &Ctx, email: &str, password: &str, store: &Path) -> R<Signer> {
    let ask = ctx.clone();
    let two_factor = move |p: TwoFactorCallbackParams| {
        let ask = ask.clone();
        async move {
            let to = if p.sms {
                p.numbers.iter().find(|n| Some(n.id) == p.selected_number_id).map(|n| format!("SMS на {}", n.number_with_dial_code))
                    .unwrap_or_else(|| "SMS".into())
            } else {
                "на ваши устройства Apple (iPhone, iPad или Mac)".into()
            };
            let error = p.last_error.clone().map(|_| "Код не подошёл — проверьте и введите ещё раз".to_string());
            Ok(match ask.ask_code(&to, error).await {
                Some(code) => TwoFactorCallbackResponse::SubmitCode(code),
                None => TwoFactorCallbackResponse::ResendCode,
            })
        }
        .boxed()
    };

    std::fs::create_dir_all(store).explain("Не создали папку установщика")?;
    // Телеметрия: связь с серверами — до входа; дальше — фазы входа (без адреса, пароля и кодов).
    telemetry::probe_servers().await;
    telemetry::line("вход: сервер подписи запросов (anisette)");
    let anisette = RemoteV3AnisetteProvider::default().explain("Нет связи с сервером подписи")?
        .set_storage(Box::new(FsStorage::new(store.join("anisette"))))
        .set_serial_number("2".into());
    let mut account = AppleAccount::builder(&email.trim().to_lowercase())
        .anisette_provider(anisette)
        .login(password, two_factor)
        .await
        .map_err(|e| login_failure(&format!("{e:?}"), email))?;
    telemetry::line("вход: Apple приняла вход");
    let session = DeveloperSession::from_account(&mut account).await
        .map_err(|e| Failure::new("Apple не открыла доступ к подписи приложений", without_personal(&format!("{e:?}"), email)))?;
    telemetry::line("вход: доступ к подписи приложений открыт");

    // Сертификатов у бесплатного Apple ID — два на всё; кончились — отозвать свой старый, а не
    // спрашивать человека, что такое сертификат.
    let max: MaxCertsBehavior<MaxCertsCallbackBox> = MaxCertsBehavior::Revoke;
    Ok(SideloaderBuilder::new(session, email.trim().to_lowercase())
        .team_selection(TeamSelection::First)
        .max_certs_behavior(max)
        .storage(Box::new(FsStorage::new(store.join("signing"))))
        .machine_name("Osmotr-Setup".into())
        .build())
}

/// Подробности ошибки — без личных данных: адрес Apple ID и номера телефонов вычищаются
/// до показа. Человек присылает подробности тому, кто разбирается, а чужие учётные
/// записи ему знать нельзя (правило автора, 13.10.2026).
fn without_personal(detail: &str, email: &str) -> String {
    let mut out = detail.to_string();
    let email = email.trim();
    if !email.is_empty() {
        for variant in [email.to_string(), email.to_lowercase()] {
            out = out.replace(&variant, "<apple-id>");
        }
    }
    // Любой другой адрес почты — слово с «@» и точкой — в заглушку.
    let words: Vec<String> = out
        .split_inclusive(|c: char| c.is_whitespace() || "\"',;{}()[]<>".contains(c))
        .map(|w| {
            let core = w.trim_end_matches(|c: char| c.is_whitespace() || "\"',;{}()[]<>".contains(c));
            if core.contains('@') && core.contains('.') { format!("<почта>{}", &w[core.len()..]) } else { w.to_string() }
        })
        .collect();
    let out = words.concat();
    // Номер телефона: «+», затем цифры с пробелами, дефисами и скобками, всего от 7 цифр.
    let chars: Vec<char> = out.chars().collect();
    let mut clean = String::with_capacity(out.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '+' {
            let mut j = i + 1;
            let mut digits = 0;
            while j < chars.len() && (chars[j].is_ascii_digit() || " -()".contains(chars[j])) {
                if chars[j].is_ascii_digit() { digits += 1; }
                j += 1;
            }
            if digits >= 7 {
                while j > i + 1 && !chars[j - 1].is_ascii_digit() { j -= 1; }
                clean.push_str("<телефон>");
                i = j;
                continue;
            }
        }
        clean.push(chars[i]);
        i += 1;
    }
    clean
}

/// Отказ, в котором виновато, похоже, состояние подписи запросов, а не пароль, блокировка или сеть.
pub fn stale_anisette_suspected(f: &Failure) -> bool { f.text == "Apple не пустила войти" }

/// Сбросить состояние подписи запросов (anisette) — его установщик хранит сам и получит заново.
/// Сертификат подписи и прочее в папке не трогаются. true — было что сбрасывать.
pub fn reset_anisette(store: &Path) -> bool {
    let dir = store.join("anisette");
    dir.exists() && std::fs::remove_dir_all(&dir).is_ok()
}

/// Отказ входа — по-человечески: неверный пароль, заблокированный Apple ID, нет сети.
fn login_failure(detail: &str, email: &str) -> Failure {
    let detail = without_personal(detail, email);
    let detail = detail.as_str();
    let low = detail.to_lowercase();
    let text = if low.contains("-20101") || low.contains("incorrect") || low.contains("password") {
        "Неверный Apple ID или пароль"
    } else if low.contains("locked") || low.contains("-20209") {
        "Apple ID заблокирован — разблокируйте на iforgot.apple.com"
    } else if low.contains("dns") || low.contains("connect") || low.contains("timed out") {
        "Нет интернета — проверьте подключение"
    } else {
        "Apple не пустила войти"
    };
    Failure::new(text, detail)
}

#[cfg(test)]
mod tests {
    use super::{login_failure, reset_anisette, stale_anisette_suspected, without_personal};

    #[test]
    fn сброс_подписи_запросов_только_при_неясном_отказе() {
        assert!(stale_anisette_suspected(&login_failure("Bad(Unknown { code: -36607 })", "")));
        assert!(!stale_anisette_suspected(&login_failure("Bad { code: -20101 }", "")));
        assert!(!stale_anisette_suspected(&login_failure("dns error: timed out", "")));
        let store = std::env::temp_dir().join(format!("osmotr-ani-{}", std::process::id()));
        std::fs::create_dir_all(store.join("anisette")).unwrap();
        std::fs::create_dir_all(store.join("signing")).unwrap();
        assert!(reset_anisette(&store));
        assert!(!store.join("anisette").exists() && store.join("signing").exists(), "сертификат подписи не трогается");
        assert!(!reset_anisette(&store), "второй раз — нечего сбрасывать");
        let _ = std::fs::remove_dir_all(&store);
    }

    #[test]
    fn personal_data_never_reaches_details() {
        let raw = r#"AuthFailed { code: -20101, account: "Person.Name@icloud.com", phone: "+996 555 123 456", other: x@y.kg }"#;
        let clean = without_personal(raw, "person.name@icloud.com");
        assert!(!clean.to_lowercase().contains("person.name"), "{clean}");
        assert!(!clean.contains("x@y.kg"), "{clean}");
        assert!(!clean.contains("555"), "{clean}");
        assert!(clean.contains("-20101"), "код ошибки остаётся: {clean}");
    }
}

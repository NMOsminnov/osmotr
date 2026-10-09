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

use crate::{Explain, Failure, R, flow::Ctx};

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
    let anisette = RemoteV3AnisetteProvider::default().explain("Нет связи с сервером подписи")?
        .set_storage(Box::new(FsStorage::new(store.join("anisette"))))
        .set_serial_number("2".into());
    let mut account = AppleAccount::builder(&email.trim().to_lowercase())
        .anisette_provider(anisette)
        .login(password, two_factor)
        .await
        .map_err(|e| login_failure(&format!("{e:?}")))?;
    let session = DeveloperSession::from_account(&mut account).await.explain("Apple не открыла доступ к подписи приложений")?;

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

/// Отказ входа — по-человечески: неверный пароль, заблокированный Apple ID, нет сети.
fn login_failure(detail: &str) -> Failure {
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

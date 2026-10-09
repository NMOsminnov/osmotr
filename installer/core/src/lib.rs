//! Установка «Осмотра» на iPhone с компьютера — шагами, которые ведут человека без навыков:
//! подключил телефон, запустил файл — дальше программа сама, а где нужен палец на телефоне,
//! показывает, что нажать, и сама замечает, что нажато.
//!
//! Ядро не знает про окно: шаги шлют [Event], окно отвечает [Answer]. Так же его гоняет
//! `osmotr-setup-cli` — без окна, для проверки.
//!
//! Подпись бесплатным Apple ID и установка — библиотека `isideload` (на ней работает iLoader,
//! github.com/nab138/iloader, MIT); связь с телефоном — `idevice`; файл пары для SideStore
//! устроен так же, как у iLoader.

pub mod account;
pub mod apps;
pub mod drivers;
pub mod flow;
pub mod phone;

use serde::{Deserialize, Serialize};

/// Шаги установки — в порядке, в каком их видит человек.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum StepId {
    /// Драйверы Apple на компьютере (без них Windows не видит iPhone).
    Drivers,
    /// iPhone подключён кабелем.
    Phone,
    /// «Доверять этому компьютеру» на iPhone.
    Trust,
    /// «Режим разработчика» на iPhone — сразу после знакомства: перезагрузка телефона в начале,
    /// пока человек внимателен, и Apple ID для этого не нужен.
    DevMode,
    /// Вход в Apple ID (подпись — им).
    AppleId,
    /// SideStore — продлевает подпись сам, раз в неделю.
    SideStore,
    /// Сам «Осмотр».
    Osmotr,
    /// LocalDevVPN — без него SideStore не продлевает.
    Vpn,
    /// «Доверять разработчику».
    Signer,
}

impl StepId {
    pub const ALL: [StepId; 9] = [StepId::Drivers, StepId::Phone, StepId::Trust, StepId::DevMode, StepId::AppleId,
        StepId::SideStore, StepId::Osmotr, StepId::Vpn, StepId::Signer];

    /// Подпись шага в окне.
    pub fn title(self) -> &'static str {
        match self {
            StepId::Drivers => "Драйверы Apple на компьютере",
            StepId::Phone => "iPhone подключён",
            StepId::Trust => "iPhone доверяет компьютеру",
            StepId::AppleId => "Вход в Apple ID",
            StepId::SideStore => "SideStore — продление подписи",
            StepId::Osmotr => "«Осмотр»",
            StepId::Vpn => "LocalDevVPN",
            StepId::DevMode => "Режим разработчика",
            StepId::Signer => "Доверие разработчику",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum StepState {
    Waiting,
    Running,
    Done,
    /// Сделать не вышло, но установка идёт дальше — в конце скажем, что сделать руками.
    Manual,
    Failed,
}

/// Что программа сообщает окну.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Event {
    /// Шаг сменил состояние; [note] — что именно сейчас происходит («Скачиваем SideStore — 40 %»).
    Step { id: StepId, state: StepState, note: String },
    /// Что сделать человеку — на телефоне или на компьютере. Программа сама заметит, что сделано.
    Hint { title: String, text: String, image: Option<String> },
    /// Убрать подсказку — сделано.
    HintDone,
    /// Нужен Apple ID и пароль; [error] — почему прошлый раз не вышло.
    AskCredentials { error: Option<String> },
    /// Нужен код подтверждения; [to] — куда пришёл.
    AskCode { to: String, error: Option<String> },
    /// Всё. [left] — что ещё сделать на телефоне (то, что программа сделать не может).
    Finished { left: Vec<String> },
    /// Остановились: что случилось — по-человечески, [detail] — для того, кто будет разбираться.
    Failed { text: String, detail: String },
}

/// Что окно отвечает программе.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Answer {
    Credentials { email: String, password: String },
    Code { code: String },
    /// Прислать код ещё раз.
    Resend,
}

/// Отказ шага: [text] — человеку, [detail] — разработчику.
#[derive(Debug, Clone, thiserror::Error)]
#[error("{text}: {detail}")]
pub struct Failure {
    pub text: String,
    pub detail: String,
}

impl Failure {
    pub fn new(text: impl Into<String>, detail: impl std::fmt::Display) -> Self {
        Self { text: text.into(), detail: detail.to_string() }
    }
}

pub type R<T> = Result<T, Failure>;

/// К любой ошибке — человеческий текст.
pub trait Explain<T> {
    fn explain(self, text: &str) -> R<T>;
}

impl<T, E: std::fmt::Debug> Explain<T> for Result<T, E> {
    fn explain(self, text: &str) -> R<T> {
        self.map_err(|e| Failure::new(text, format!("{e:?}")))
    }
}

//! Окно установки: показывает шаги и подсказки ядра (`osmotr-setup`), передаёт ему Apple ID и
//! коды. Один файл для людей: «Осмотр» и ipatool зашиты внутрь (признак `bundled` при сборке).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{path::PathBuf, sync::Mutex};

use osmotr_setup::{Answer, Event, flow};
use tauri::{Emitter, Manager, State};
use tokio::sync::mpsc;

/// Куда окно шлёт ответы человека.
struct Answers(Mutex<Option<mpsc::UnboundedSender<Answer>>>);

#[cfg(feature = "bundled")]
const OSMOTR_IPA: &[u8] = include_bytes!(env!("OSMOTR_IPA"));
#[cfg(feature = "bundled")]
const IPATOOL: &[u8] = include_bytes!(env!("IPATOOL_EXE"));

/// Служебная папка установщика: %LOCALAPPDATA%\OsmotrSetup (сертификат, скачанное).
fn work_dir() -> PathBuf {
    std::env::var_os("LOCALAPPDATA").map(PathBuf::from).unwrap_or_else(std::env::temp_dir).join("OsmotrSetup")
}

/// Разложить зашитое по папке; без `bundled` — взять лежащее рядом с exe.
fn unpack(work: &std::path::Path) -> std::io::Result<PathBuf> {
    std::fs::create_dir_all(work)?;
    #[cfg(feature = "bundled")]
    {
        let ipa = work.join("Osmotr.ipa");
        std::fs::write(&ipa, OSMOTR_IPA)?;
        std::fs::write(work.join(if cfg!(windows) { "ipatool.exe" } else { "ipatool" }), IPATOOL)?;
        Ok(ipa)
    }
    #[cfg(not(feature = "bundled"))]
    {
        let here = std::env::current_exe()?.parent().map(PathBuf::from).unwrap_or_default();
        for name in ["ipatool.exe", "ipatool"] {
            if here.join(name).exists() { let _ = std::fs::copy(here.join(name), work.join(name)); }
        }
        Ok(here.join("Osmotr.ipa"))
    }
}

/// Начать установку (окно зовёт один раз, когда готово показывать).
#[tauri::command]
fn start(app: tauri::AppHandle, answers: State<'_, Answers>) -> Result<(), String> {
    let work = work_dir();
    let ipa = unpack(&work).map_err(|e| format!("Не распаковали установщик: {e}"))?;
    let (tx, mut rx) = mpsc::unbounded_channel::<Event>();
    let (atx, arx) = mpsc::unbounded_channel::<Answer>();
    *answers.0.lock().unwrap() = Some(atx);
    let ctx = flow::Ctx::new(tx, arx, work, ipa);
    tauri::async_runtime::spawn(async move { let _ = flow::run(ctx).await; });
    tauri::async_runtime::spawn(async move {
        while let Some(e) = rx.recv().await { let _ = app.emit("setup", e); }
    });
    Ok(())
}

/// Ответ человека: Apple ID, код, «прислать ещё раз».
#[tauri::command]
fn answer(answer: Answer, answers: State<'_, Answers>) {
    if let Some(tx) = answers.0.lock().unwrap().as_ref() { let _ = tx.send(answer); }
}

/// Шаги — подписи для окна (одни на ядро и окно).
#[tauri::command]
fn steps() -> Vec<(osmotr_setup::StepId, &'static str)> {
    osmotr_setup::StepId::ALL.iter().map(|s| (*s, s.title())).collect()
}

fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    tauri::Builder::default()
        .manage(Answers(Mutex::new(None)))
        .setup(|app| {
            if let Some(w) = app.get_webview_window("main") { let _ = w.set_focus(); }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![start, answer, steps])
        .run(tauri::generate_context!())
        .expect("окно установки не открылось");
}

//! Тот же ход установки без окна: события — строками, вопросы — в консоли. Для проверки ядра и
//! для разбора, если у человека что-то пошло не так (`osmotr-setup-cli <папка> <Osmotr.ipa>`).

use std::io::{BufRead, Write};

use osmotr_setup::{Answer, Event, flow};

#[tokio::main]
async fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    // «clean-phone» — снять с подключённого iPhone то, что ставит установщик (проверки на «чистом»).
    if std::env::args().nth(1).as_deref() == Some("clean-phone") { return clean_phone().await }
    let mut args = std::env::args().skip(1);
    let work = args.next().map(Into::into).unwrap_or_else(|| std::env::temp_dir().join("osmotr-setup"));
    let ipa = args.next().map(Into::into).unwrap_or_else(|| std::path::PathBuf::from("Osmotr.ipa"));
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Event>();
    let (atx, arx) = tokio::sync::mpsc::unbounded_channel::<Answer>();
    let ctx = flow::Ctx::new(tx, arx, work, ipa);
    let job = tokio::spawn(flow::run(ctx));
    let ask = |prompt: &str| -> String {
        print!("{prompt}: "); std::io::stdout().flush().ok();
        let mut s = String::new(); std::io::stdin().lock().read_line(&mut s).ok(); s.trim().to_string()
    };
    while let Some(e) = rx.recv().await {
        match e {
            Event::Step { id, state, note } => println!("[{state:?}] {} {note}", id.title()),
            Event::Hint { title, text, .. } => println!("\n>>> {title}\n    {text}\n"),
            Event::HintDone => {}
            Event::AskCredentials { error } => {
                if let Some(e) = error { println!("! {e}") }
                let email = ask("Apple ID");
                let password = ask("Пароль");
                let _ = atx.send(Answer::Credentials { email, password });
            }
            Event::AskCode { to, error } => {
                if let Some(e) = error { println!("! {e}") }
                let code = ask(&format!("Код подтверждения ({to}; пусто — прислать ещё раз)"));
                let _ = atx.send(if code.is_empty() { Answer::Resend } else { Answer::Code { code } });
            }
            Event::Finished { left } => { println!("\nГотово. Осталось на телефоне:"); for l in left { println!("  • {l}") } break }
            Event::Failed { text, detail } => { println!("\nОстановились: {text}\n  ({detail})"); break }
        }
    }
    let _ = job.await;
}

/// Снять с телефона «Осмотр», SideStore и LocalDevVPN — по подписи на экране.
async fn clean_phone() {
    use osmotr_setup::phone;
    let Ok(Some(dev)) = phone::find_usb().await else { println!("iPhone не подключён (или нет службы Apple) — с телефона ничего не снято"); return };
    if !phone::paired(&dev.udid).await { println!("С телефоном нет знакомства — с телефона ничего не снято"); return }
    let Ok(p) = phone::describe(dev).await else { println!("Телефон не отвечает"); return };
    let apps = phone::installed(&p).await.unwrap_or_default();
    for (id, name) in apps.iter().filter(|(_, n)| ["Осмотр", "SideStore", "LocalDevVPN"].contains(&n.as_str())) {
        match phone::uninstall(&p, id).await {
            Ok(()) => println!("Снято с телефона: {name} ({id})"),
            Err(e) => println!("Не снято: {name} — {}", e.text),
        }
    }
}

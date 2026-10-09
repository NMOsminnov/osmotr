//! Тот же ход установки без окна: события — строками, вопросы — в консоли. Для проверки ядра и
//! для разбора, если у человека что-то пошло не так (`osmotr-setup-cli <папка> <Osmotr.ipa>`).

use std::io::{BufRead, Write};

use osmotr_setup::{Answer, Event, flow};

#[tokio::main]
async fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
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

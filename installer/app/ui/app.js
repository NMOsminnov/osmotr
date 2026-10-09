// Окно установки: шаги слева, справа — что нужно человеку сейчас. Всё решает ядро (события
// «setup»), окно только показывает и передаёт ответы.
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const stepsEl = document.getElementById('steps');
const stage = document.getElementById('stage');
const rows = {};
let current = null;    // шаг, который идёт сейчас
let hintShown = false;
let locked = false;    // форма ввода на экране — подсказки не перебивают её

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// Картинки подсказок — телефон и что на нём нажать.
const phone = (screen) => `<svg class="picture" width="190" height="230" viewBox="0 0 190 230" aria-hidden="true">
  <rect x="45" y="8" width="100" height="200" rx="18" fill="none" stroke="currentColor" stroke-width="4" opacity=".75"/>
  <rect x="78" y="16" width="34" height="8" rx="4" fill="currentColor" opacity=".4"/>${screen}</svg>`;
const pictures = {
  cable: phone(`<path d="M95 208 v14 M85 222 h20" stroke="currentColor" stroke-width="5" stroke-linecap="round" opacity=".6"/>`),
  unlock: phone(`<circle cx="95" cy="90" r="16" fill="none" stroke="var(--accent)" stroke-width="4"/><path d="M83 90 v-8 a12 12 0 0 1 22-6" fill="none" stroke="var(--accent)" stroke-width="4"/><text x="95" y="150" text-anchor="middle" font-size="12" fill="currentColor" opacity=".7">Разблокируйте</text>`),
  trust: phone(`<rect x="56" y="70" width="78" height="76" rx="10" fill="var(--bg)" stroke="var(--line)"/>
    <text x="95" y="90" text-anchor="middle" font-size="9" fill="currentColor">Доверять этому</text><text x="95" y="101" text-anchor="middle" font-size="9" fill="currentColor">компьютеру?</text>
    <line x1="56" y1="112" x2="134" y2="112" stroke="var(--line)"/><text x="95" y="127" text-anchor="middle" font-size="11" font-weight="600" fill="var(--accent)">Доверять</text>
    <circle cx="112" cy="124" r="11" fill="var(--accent)" opacity=".25"/>`),
  devmode: phone(`<text x="95" y="72" text-anchor="middle" font-size="9" fill="currentColor" opacity=".7">Конфиденциальность</text>
    <rect x="56" y="88" width="78" height="26" rx="8" fill="var(--bg)" stroke="var(--line)"/><text x="62" y="104" font-size="8.5" fill="currentColor">Режим разраб.</text>
    <rect x="110" y="94" width="20" height="13" rx="6.5" fill="var(--done)"/><circle cx="123" cy="100.5" r="5" fill="#fff"/>`),
};

function renderSteps(list) {
  stepsEl.innerHTML = '';
  for (const [id, title] of list) {
    const li = document.createElement('li');
    li.className = 'waiting';
    li.innerHTML = `<span class="mark"></span><div><div class="title">${esc(title)}</div><div class="note"></div></div>`;
    stepsEl.appendChild(li);
    rows[id] = li;
  }
}

function show(html) { stage.innerHTML = `<div class="card">${html}</div>`; }

function showProgress() {
  if (locked || hintShown || !current) return;
  const li = rows[current];
  const title = li?.querySelector('.title').textContent ?? '';
  const note = li?.querySelector('.note').textContent ?? '';
  show(`<div class="waiting-big"><div class="spinner"></div><h2>${esc(title)}</h2></div>
        <p class="muted">${esc(note || 'Подождите — программа работает сама.')}</p>`);
}

function onStep({ id, state, note }) {
  const li = rows[id];
  if (!li) return;
  li.className = state;
  li.querySelector('.note').textContent = note || '';
  if (state === 'running') { current = id; li.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
  if (current === id) showProgress();
}

function onHint({ title, text, image }) {
  if (locked) return;
  hintShown = true;
  show(`<h2>${esc(title)}</h2>${pictures[image] ?? ''}<p>${esc(text)}</p>
        <p class="muted">Программа сама заметит, когда сделаете.</p>`);
}

function askCredentials({ error }) {
  locked = true;
  show(`<h2>Войдите в Apple ID</h2>
    <p>Тот же Apple ID, что на iPhone (в настройках — сверху, ваше имя). Им подписывается «Осмотр».</p>
    ${error ? `<div class="error">${esc(error)}</div>` : ''}
    <label>Apple ID (почта)<input id="email" type="email" autocomplete="username" spellcheck="false"></label>
    <label>Пароль<input id="password" type="password" autocomplete="current-password"></label>
    <button id="go">Войти</button>
    <p class="muted">Пароль никуда не сохраняется — он уходит только в Apple.</p>`);
  const email = document.getElementById('email'), password = document.getElementById('password'), go = document.getElementById('go');
  email.focus();
  const send = () => {
    if (!email.value.trim() || !password.value) return;
    go.disabled = true; locked = false;
    invoke('answer', { answer: { type: 'credentials', email: email.value.trim(), password: password.value } });
    showProgress();
  };
  go.onclick = send;
  password.onkeydown = (e) => { if (e.key === 'Enter') send(); };
}

function askCode({ to, error }) {
  locked = true;
  show(`<h2>Введите код подтверждения</h2>
    <p>Apple прислала код ${esc(to)}. Если на iPhone спросят «Разрешить вход?» — нажмите «Разрешить», появится код.</p>
    ${error ? `<div class="error">${esc(error)}</div>` : ''}
    <input id="code" class="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code">
    <button id="go">Подтвердить</button>
    <button id="again" class="ghost">Код не пришёл — прислать ещё раз</button>`);
  const code = document.getElementById('code');
  code.focus();
  const send = () => {
    const v = code.value.replace(/\D/g, '');
    if (v.length !== 6) return;
    locked = false;
    invoke('answer', { answer: { type: 'code', code: v } });
    showProgress();
  };
  code.oninput = () => { code.value = code.value.replace(/\D/g, ''); if (code.value.length === 6) send(); };
  document.getElementById('go').onclick = send;
  document.getElementById('again').onclick = () => { locked = false; invoke('answer', { answer: { type: 'resend' } }); showProgress(); };
}

function onFinished({ left }) {
  locked = true;
  show(`<div class="done-big"><div class="ok">✓</div><h2>Готово — «Осмотр» на iPhone</h2></div>
    ${left.length ? `<p>Осталось сделать на телефоне — по порядку:</p><ol class="todo">${left.map((t) => `<li>${esc(t)}</li>`).join('')}</ol>` : ''}
    <p class="muted">Кабель можно отключить. Программу можно закрыть.</p>`);
}

function onFailed({ text, detail }) {
  locked = true;
  if (current && rows[current]) rows[current].className = 'failed';
  show(`<h2>Остановились</h2><div class="error">${esc(text)}</div>
    <button id="again">Попробовать снова</button>
    <details><summary>Подробности для того, кто будет разбираться</summary><pre>${esc(detail)}</pre></details>`);
  document.getElementById('again').onclick = () => location.reload();
}

listen('setup', ({ payload: e }) => {
  switch (e.type) {
    case 'step': onStep(e); break;
    case 'hint': onHint(e); break;
    case 'hintDone': hintShown = false; showProgress(); break;
    case 'askCredentials': askCredentials(e); break;
    case 'askCode': askCode(e); break;
    case 'finished': onFinished(e); break;
    case 'failed': onFailed(e); break;
  }
});

(async () => {
  renderSteps(await invoke('steps'));
  show(`<div class="waiting-big"><div class="spinner"></div><h2>Начинаем</h2></div>`);
  try { await invoke('start'); } catch (err) { onFailed({ text: String(err), detail: '' }); }
})();

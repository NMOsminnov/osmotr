// Окно установщика не дёргается: тот же экран обновляется на месте, заново появляется только
// новый (автор, 14.10.2026: «Дёргания интерфейса в нём были»). Безголовый Chrome отдаёт DOM
// обвязки (harness.html) после сценария событий.  node --test installer/app/ui/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const chrome = [process.env.CHROME, 'chromium-browser', 'chromium', 'google-chrome',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'].find((c) => c && (c.includes('/') ? existsSync(c) : true));

function run(hash = '') {
  const html = execFileSync(chrome, ['--headless', '--disable-gpu', '--no-sandbox', '--allow-file-access-from-files',
    '--virtual-time-budget=5000', '--dump-dom', pathToFileURL(path.join(here, 'harness.html')).href + hash],
    { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'ignore'] });
  const m = html.match(/<pre id="result">([^<]*)<\/pre>/);
  assert.ok(m && m[1], 'обвязка не отдала итог');
  return JSON.parse(m[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&'));
}

test('проценты и повтор подсказки не пересоздают экран', () => {
  const { appeared } = run();
  // Ход установки — один раз, подсказка — один раз, ход снова (после «сделано») — один раз, вход — один.
  assert.deepEqual(appeared, ['progress', 'hint:Подключите iPhone|Кабелем к компьютеру|cable', 'progress', 'credentials'], JSON.stringify(appeared));
});

test('на экране хода — заголовок текущего шага, обновлённый на месте', () => {
  // Последний ход перед входом — шаг «Вход в Apple ID», затем экран входа.
  const { progressText } = run();
  assert.equal(progressText, 'Войдите в Apple ID');
});

test('на «Готово» доверие разработчику — первым и с картинкой', () => {
  const { trustPicture, first } = run('#finished');
  assert.ok(trustPicture, 'нет картинки «Доверять»');
  assert.match(first, /Доверять/);
});

/**
 * Описи — списки основных средств из Excel. Подбор столбцов — тот же, что на Android
 * (Inventory.kt): словарь заголовков с опечатками (RU/EN/KY), шапка в несколько этажей, сверка
 * данными, таблица без шапки, итоги, повторы шапки, «1 2 3…» и разделы пропускаются, разделы —
 * местом. Ничего не спрашивает: номера нет — по порядку, необязательного нет — пусто.
 * Проверяется тем же набором кривых шаблонов (tools/make_templates.py, test/templates.test.ts).
 */
import type { Sheet } from './xlsx'
import { fuzzyPrefix, compact } from './search'

export enum Field { NUMBER, INVENTORY, NAME, INITIAL, SUM, PRIORITY, LIST, QUANTITY, PLACE }
const FIELDS = [Field.NUMBER, Field.INVENTORY, Field.NAME, Field.INITIAL, Field.SUM, Field.PRIORITY, Field.LIST, Field.QUANTITY, Field.PLACE]

export interface Item {
  list: string; number: string; inventory: string; name: string
  initial: number | null; sum: number | null; priority: string; status: string; row: number
  qty: string; place: string
}

export interface Table {
  sheet: string; headerRow: number; headers: string[]
  columns: Map<Field, number>; body: number; invFromName: boolean
}

// ---------- Строки и числа ----------

const LOOKALIKE: Record<string, string> = { a: 'а', e: 'е', o: 'о', p: 'р', c: 'с', x: 'х', y: 'у', k: 'к', h: 'н', m: 'м', t: 'т', b: 'в', n: 'п', u: 'и', r: 'г' }
const isLetter = (ch: string) => /\p{L}/u.test(ch)
const isDigit = (ch: string) => /\p{Nd}/u.test(ch)
const hasLetter = (s: string) => /\p{L}/u.test(s)
const countLetters = (s: string) => (s.match(/\p{L}/gu) || []).length

function norm(s: string) { return s.toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ').trim() }
/** Для русских слов: латиница → кириллица, только если в слове уже есть кириллица («cost» не испортить). */
function ru(s: string) {
  return norm(s).split(' ').map(w => /[а-я]/.test(w) ? [...w].map(ch => LOOKALIKE[ch] ?? ch).join('') : w).join(' ')
}

/** Как Double.toDoubleOrNull в Kotlin: только настоящее число, пустое — не число. */
function toDouble(s: string): number | null {
  const t = s.trim()
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(t)) return null
  return parseFloat(t)
}

/** Число из ячейки: «1 044 321,55 сом», «1.», «12 000,5» — с пробелами, валютой, точкой в конце. */
export function number(s: string): number | null {
  if (!s) return null
  const quick = toDouble(s)
  if (quick !== null) return quick
  const first = s.trimStart()[0]
  if (first === undefined || !(isDigit(first) || first === '-' || first === '+')) return null
  let t = s.replace(/[\s  ]/g, '').replace(/(сом|руб\.?|som|kgs|\$|₽|шт\.?)$/i, '').replace(/\.+$/, '')
  // И запятая, и точка: дробная часть — за последним из них («1,033,993.19», «1.033.993,19»).
  if (t.includes(',') && t.includes('.')) t = t.lastIndexOf(',') > t.lastIndexOf('.') ? t.replace(/\./g, '').replace(',', '.') : t.replace(/,/g, '')
  return toDouble(t.replace(',', '.'))
}

const whole = (d: number) => Number.isFinite(d) && d === Math.floor(d)
const TOTAL = /^(итого|всего|total|жыйынтык|бардыгы)/
const NOT_DATA = /износ|амортиз|рекоменд|итог|дата|срок|год выпуск|примечан|коммент|^сч[её]т|ед\.? ?изм|единиц|заводск|серийн|паспорт|serial|note|date/

// ---------- Заголовки ----------

const memo = new Map<string, number>()

/** Сколько очков столбцу с заголовком [h] за поле [f]; отрицательное — точно не он. */
export function headerScore(f: Field, h: string): number {
  if (!h.trim() || h.length > 120) return 0
  const k = f + '|' + h
  let v = memo.get(k)
  if (v === undefined) { if (memo.size > 50000) memo.clear(); v = headerScoreOf(f, h); memo.set(k, v) }
  return v
}

function headerScoreOf(f: Field, h: string): number {
  const r = ru(h), enText = norm(h)
  if (!r) return 0
  const hw = r.split(/[^\p{L}\p{N}/№-]+/u).filter(Boolean)
  // Ключевое слово — вхождением или, от 5 букв, началом слова с опечаткой («наименавание», «инвентраный»).
  const has = (...w: string[]) => w.some(k => r.includes(k) || (k.length >= 5 && !k.includes(' ') && hw.some(x => fuzzyPrefix(x, k))))
  const en = (...w: string[]) => w.some(k => new RegExp(`\\b${k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(enText))
  const noise = NOT_DATA.test(r)
  let base: number
  switch (f) {
    case Field.INVENTORY:
      base = has('заводск', 'серийн', 'паспорт') || en('serial') ? -6
        : has('инв') || en('asset tag', 'inventory', 'inv', 'tag') ? 12
        : has('номенклатурн', 'шифр', 'код ос', 'код объекта') ? 7
        : r === 'номер' || r === 'номер объекта' || r === 'код' ? 6 : 0
      break
    case Field.NAME:
      base = has('номер', '№') ? 0
        : has('наименован', 'аталыш') ? 12
        : has('основное средство', 'основного средства', 'основные средства') ? 11
        : en('description', 'name', 'item', 'asset name', 'equipment') ? 10
        : has('назван', 'объект', 'имуществ', 'предмет', 'оборудован', 'товар', 'номенклатур', 'описание') ? 7 : 0
      break
    case Field.NUMBER:
      base = has('инв', 'заводск', 'паспорт') ? -10
        : ['№', 'п/п', 'пп', 'р/с', '#', 'n'].includes(r) || ['no', 'no.', 'nr'].includes(enText) ? 12
        : has('п/п', 'п. п', 'по порядку', 'порядков', '№ по списку', 'номер в списке', 'р/с') ? 12
        : r.startsWith('№') && r.length <= 4 ? 10
        : r === 'номер' ? 4
        : has('строк') ? 3 : 0
      break
    case Field.INITIAL:
      base = has('остаточн', 'износ') || en('residual', 'net') ? -8
        : has('первонач', 'балансов') || en('initial', 'original') ? 12
        : en('cost', 'price', 'value') || has('баасы') ? 9
        : has('стоимост') ? 7
        : has('цена') ? 6 : 0
      break
    case Field.SUM:
      base = has('износ') ? -8
        : has('первонач') ? -6
        : has('остаточн') || en('residual', 'net book') ? 12
        : has('сумма') || en('amount', 'total') ? 9
        : has('сальдо') && has('дебет') ? (has('конец') ? 8 : 6)
        : has('балансов') ? 6  // «Балансовая», когда первоначальная — отдельно
        : has('стоимост') ? 5 : 0
      break
    case Field.PRIORITY:
      base = has('приоритет', 'маанилуу') || en('priority') ? 12 : has('важност', 'очередн', 'срочност') ? 8 : 0
      break
    case Field.LIST:
      base = has('статус') || en('status') ? 9 : has('список', 'раздел') ? 6 : 0
      break
    case Field.QUANTITY:
      base = has('кол-во', 'количеств', 'кол.', 'саны') || r === 'кол' || en('qty', 'quantity', 'count', 'pcs') ? 12 : 0
      break
    case Field.PLACE:
      base = has('местонахожд', 'местоположен', 'помещени', 'кабинет', 'расположен') || en('location', 'room', 'place', 'site') ? 12
        : has('место', 'корпус', 'здание', 'этаж', 'подразделени', 'отдел') ? 8 : 0
      break
    default: base = 0
  }
  // Шум (износ, даты, сроки, примечания…) не годится ни во что, кроме явного «сальдо … дебет».
  return base > 0 && noise && !(f === Field.SUM && has('сальдо')) ? base - 10 : base
}

/** «высокий / средний / низкий» → 1 / 2 / 3. */
export function priorityOf(s: string): string {
  const n = ru(s)
  if (n.startsWith('высок') || n.startsWith('high') || n.startsWith('критич')) return '1'
  if (n.startsWith('средн') || n.startsWith('medium')) return '2'
  if (n.startsWith('низк') || n.startsWith('low')) return '3'
  return s
}

// ---------- Строки листа ----------

const blank = (s: string) => !s.trim()

/** Строка «1 2 3 4 …» под шапкой. */
function isNumbering(r: string[]): boolean {
  const v = r.filter(x => !blank(x))
  if (v.length < 3) return false
  const n: number[] = []
  for (const x of v) { const d = number(x); if (d === null) return false; n.push(d) }
  return n[0] === 1 && n.every((d, i) => i === 0 || d === n[i - 1] + 1)
}

function headerFields(r: string[]): Set<Field> {
  const out = new Set<Field>()
  for (const f of FIELDS) if (r.some(c => headerScore(f, c) >= 6)) out.add(f)
  return out
}

/** Раздел («Кабинет 101», «Здание 2»): в строке одно значение (объединённое на ширину — тоже). */
function sectionOf(r: string[]): string | null {
  const v = [...new Set(r.filter(x => !blank(x)).map(x => x.trim()))]
  if (v.length !== 1) return null
  const s = v[0]
  return hasLetter(s) && !TOTAL.test(norm(s)) ? s : null
}

/** Насколько значения столбца похожи на поле. */
function dataScore(f: Field, values: string[]): number {
  const v = values.map(x => x.trim()).filter(Boolean)
  if (!v.length) return -6
  if (v.length === 1) return 0
  const nums = v.map(number).filter((d): d is number => d !== null)
  const numeric = nums.length / v.length
  const unique = new Set(v).size / v.length
  const letters = v.filter(s => countLetters(s) >= 3).length / v.length
  const ints = nums.filter(whole)
  let seqHits = 0
  for (let i = 1; i < ints.length; i++) if (ints[i] === ints[i - 1] + 1 || ints[i] === 1) seqHits++
  const sequential = ints.length >= 2 ? seqHits / (ints.length - 1) : 0
  const avgLen = v.reduce((a, s) => a + s.length, 0) / v.length
  switch (f) {
    case Field.INVENTORY:
      if (unique < 0.7) return -6
      if (v.filter(s => [...s].some(isDigit)).length < v.length * 0.8) return -6
      if (numeric > 0.9 && sequential > 0.8 && Math.max(...nums) <= v.length * 2) return -6
      if (numeric > 0.9 && nums.filter(d => !whole(d)).length > nums.length * 0.3) return -6
      if (avgLen < 2 || avgLen > 30) return -4
      return 4
    case Field.NAME: return letters >= 0.8 && numeric < 0.2 && avgLen > 5 ? 4 : -6
    case Field.NUMBER: return numeric > 0.9 && ints.length >= nums.length * 0.95 && nums.every(d => d >= 0) && sequential >= 0.7 ? 4 : -6
    case Field.INITIAL: case Field.SUM: return numeric >= 0.8 ? 2 + (nums.some(d => d >= 100 || !whole(d)) ? 1 : 0) : -6
    case Field.PRIORITY: return new Set(v).size <= 10 && ((numeric > 0.9 && nums.every(d => d >= 0 && d <= 20)) || v.every(s => priorityOf(s) !== s)) ? 3 : -6
    case Field.QUANTITY: return numeric > 0.9 && nums.every(d => d >= 0 && d <= 100000) && nums.filter(d => d <= 10).length >= nums.length * 0.6 ? 3 : -6
    case Field.PLACE: return letters >= 0.7 && unique <= 0.6 ? 3 : -4
    case Field.LIST: { const d = new Set(v).size; return d >= 1 && d <= 12 && numeric < 0.5 ? 2 : -4 }
  }
  return -6
}

/** Шапка: склеенные этажи над строкой [i] (только этажи, сами похожие на шапку). */
function composite(rows: string[][], i: number): string[] {
  const floors: number[] = []
  for (let k = Math.max(0, i - 2); k <= i; k++) if (k === i || (headerFields(rows[k]).size > 0 && sectionOf(rows[k]) === null)) floors.push(k)
  const width = Math.max(...floors.map(k => rows[k].length))
  return Array.from({ length: width }, (_, c) => [...new Set(floors.map(k => (rows[k][c] ?? '').trim()).filter(x => x && number(x) === null))].join(' '))
}

function lowerFloor(r: string[]) { return headerFields(r).size > 0 && !r.some(x => !blank(x) && number(x) !== null) && sectionOf(r) === null }

function headerRow(rows: string[][]): number | null {
  let best: number | null = null, bestScore = 1
  for (let i = 0; i < Math.min(rows.length, 60); i++) {
    if (!rows[i].length || sectionOf(rows[i]) !== null) continue
    const score = headerFields(composite(rows, i)).size
    if (score > bestScore || (score === bestScore && best !== null && i === best + 1 && lowerFloor(rows[i]))) { best = i; bestScore = score }
  }
  return best
}

/** Повтор шапки (новая страница печатной формы, второй блок). */
function repeatOf(r: string[], headers: string[]): boolean {
  let hits = 0
  for (let c = 0; c < r.length; c++) {
    const h = headers[c] ?? '', cell = r[c]
    if (blank(cell) || !h || cell.length > h.length + 2) continue
    const v = ru(cell)
    if (v && ru(h).includes(v) && number(cell) === null && ++hits >= 2) return true
  }
  return false
}

function dataRow(r: string[], headers: string[]): boolean {
  return r.some(x => !blank(x)) && !r.some(x => x.length >= 5 && TOTAL.test(x.trimStart().slice(0, 12).toLowerCase())) &&
    !repeatOf(r, headers) && !isNumbering(r) && sectionOf(r) === null
}

const INV_IN_NAME = /[,;(]?\s*(?:инв|inv)[a-zа-я]*\.?\s*(?:№|n|no|номер)?\s*[:.]?\s*([0-9A-Za-zА-Яа-яЁё](?:[0-9A-Za-zА-Яа-яЁё/.,-]| (?=\d))*[0-9A-Za-zА-Яа-яЁё])\)?/iu

/** Таблица листа; null — на листе нет списка предметов. */
export function detect(sheet: Sheet): Table | null {
  const rows = sheet.rows
  if (rows.filter(r => r.some(x => !blank(x))).length < 2) return null
  const hr = headerRow(rows)
  const headers = hr !== null ? composite(rows, hr) : []
  let body = (hr ?? -1) + 1
  while (body < rows.length && rows[body].length && isNumbering(rows[body])) body++
  const data: string[][] = []
  for (let i = body; i < rows.length && data.length < 500; i++) if (dataRow(rows[i], headers)) data.push(rows[i])
  if (!data.length || (hr === null && data.length < 3)) return null
  const width = Math.max(headers.length, ...data.map(r => r.length))
  const column = (c: number) => data.map(r => r[c] ?? '')
  const dataScores = new Map<Field, number[]>()
  for (const f of FIELDS) dataScores.set(f, Array.from({ length: width }, (_, c) => dataScore(f, column(c))))
  const scores = new Map<Field, Map<number, number>>()
  for (const f of FIELDS) {
    const m = new Map<number, number>()
    for (let c = 0; c < width; c++) {
      const d = dataScores.get(f)![c]
      let s: number
      if (hr !== null) { const h = headerScore(f, headers[c] ?? ''); s = h <= 0 ? -Infinity : h + d } else s = d + 5
      if (s >= 8) m.set(c, s)
    }
    scores.set(f, m)
  }
  const columns = new Map<Field, number>()
  const taken = new Set<number>()
  const order = [...FIELDS].sort((a, b) => (Math.max(0, ...scores.get(b)!.values()) || 0) - (Math.max(0, ...scores.get(a)!.values()) || 0))
  // Kotlin: maxOrNull ?: 0 — пустое = 0; отрицательных здесь нет (порог 8).
  for (const f of order) {
    const ranked = [...scores.get(f)!.entries()].filter(([c]) => !taken.has(c)).sort((a, b) => b[1] - a[1] || a[0] - b[0])
    if (ranked.length) { columns.set(f, ranked[0][0]); taken.add(ranked[0][0]) }
  }
  if (hr === null && columns.has(Field.NAME)) {
    const text = Array.from({ length: width }, (_, c) => c).filter(c => !taken.has(c) || columns.get(Field.NAME) === c).filter(c => dataScores.get(Field.NAME)![c] > 0)
    let best: number | null = null, bestLen = -1
    for (const c of text) { const l = column(c).reduce((a, s) => a + s.length, 0); if (l > bestLen) { best = c; bestLen = l } }
    if (best !== null) columns.set(Field.NAME, best)
  }
  const ini = columns.get(Field.INITIAL), sum = columns.get(Field.SUM)
  if (ini !== undefined && sum !== undefined && hr === null) {
    const pairs: [number, number][] = []
    for (const r of data) { const a = number(r[ini] ?? ''), b = number(r[sum] ?? ''); if (a !== null && b !== null) pairs.push([a, b]) }
    if (pairs.filter(([a, b]) => b > a).length > Math.floor(pairs.length / 2)) { columns.set(Field.INITIAL, sum); columns.set(Field.SUM, ini) }
  }
  if (!columns.has(Field.INVENTORY) && !columns.has(Field.NAME)) return null
  if (hr === null && (!columns.has(Field.INVENTORY) || !columns.has(Field.NAME))) return null
  // Инвентарники в описи есть всегда: заголовок не узнали — столбец, больше всех похожий на них по данным.
  if (!columns.has(Field.INVENTORY)) {
    const used = new Set(columns.values())
    let best: number | null = null, bestN = -1
    for (let c = 0; c < width; c++) {
      if (used.has(c) || dataScores.get(Field.INVENTORY)![c] <= 0) continue
      const n = column(c).filter(v => [...v].some(isDigit) && [...v].some(ch => !isDigit(ch))).length
      if (n > bestN) { best = c; bestN = n }
    }
    if (best !== null) columns.set(Field.INVENTORY, best)
  }
  const nameCol = columns.get(Field.NAME)
  const fromName = !columns.has(Field.INVENTORY) && nameCol !== undefined &&
    column(nameCol).filter(v => INV_IN_NAME.test(v)).length >= Math.floor(data.length / 2)
  return { sheet: sheet.name, headerRow: hr ?? -1, headers: headers.length ? headers : new Array(width).fill(''), columns, body, invFromName: fromName }
}

/** Предметы листа; итоги, повторы шапки, «1 2 3…» и пустые строки пропускаются, разделы — местом. */
export function items(sheet: Sheet, t: Table): Item[] {
  const cell = (r: string[], f: Field) => { const c = t.columns.get(f); return c === undefined ? '' : (r[c] ?? '').trim() }
  const int = (s: string) => { const d = number(s); return d !== null && whole(d) ? String(Math.trunc(d)) : null }
  const out: Item[] = []
  let section = t.headerRow > 0 ? (sectionOf(sheet.rows[t.headerRow - 1] ?? []) ?? '') : ''
  for (let i = t.body; i < sheet.rows.length; i++) {
    const r = sheet.rows[i]
    const sec = sectionOf(r)
    if (sec !== null) { const inv = cell(r, Field.INVENTORY); if (!inv || inv === sec) { section = sec; continue } }
    if (!dataRow(r, t.headers)) continue
    let inv = cell(r, Field.INVENTORY), name = cell(r, Field.NAME)
    if (t.invFromName && !inv) {
      const m = INV_IN_NAME.exec(name)
      if (m) { inv = m[1]; name = (name.slice(0, m.index) + name.slice(m.index + m[0].length)).replace(/^[ ,;.-]+|[ ,;.-]+$/g, '') }
    }
    if (!inv && !name) continue
    if (!inv && ![...name].some(isLetter)) continue
    const n = ((s: string) => int(s) ?? s)(cell(r, Field.NUMBER))
    out.push({
      list: t.sheet, number: n || String(out.length + 1), inventory: inv, name,
      initial: number(cell(r, Field.INITIAL)), sum: number(cell(r, Field.SUM)),
      priority: ((s: string) => int(s) ?? priorityOf(s))(cell(r, Field.PRIORITY)),
      status: cell(r, Field.LIST), row: i + 1,
      qty: ((s: string) => int(s) ?? s)(cell(r, Field.QUANTITY)),
      place: cell(r, Field.PLACE) || section,
    })
  }
  return out
}

/** Вся опись: предметы всех листов-списков. */
export function parseBook(sheets: Sheet[]): { lists: string[]; items: Item[] } {
  const all: Item[] = []
  const lists: string[] = []
  for (const s of sheets) { const t = detect(s); if (!t) continue; const its = items(s, t); if (its.length) { lists.push(s.name); all.push(...its) } }
  return { lists, items: all }
}

// ---------- Папки предметов ----------

/** Ключ инвентарника для сравнения: без разделителей, похожие латинские буквы — русскими. */
export const key = (inv: string) => compact(inv)
export const idOf = (i: Item) => i.inventory || '№' + i.number

/** Имя папки предмета: «13. 1562∕65»; без инвентарника — «13»; несколько — «… +2 шт». */
export function folderName(first: Item, count = 1): string {
  const clean = (s: string) => s.trim().replace(/[\\/:*?"<>|\u0000-\u001F]/g, ' ').replace(/\s+/g, ' ').trim().replace(/\.+$/, '')
  const base = first.inventory ? clean(first.number + '. ' + first.inventory.slice(0, 48).replace(/\//g, '∕').replace(/\\/g, '∖')) : clean(first.number)
  return base + (count > 1 ? ` +${count - 1} шт` : '')
}

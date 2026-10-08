/**
 * Поиск — как на Android (Search.kt): инвентарник и номер без разделителей («ИН-001 23» =
 * «ин00123», латиница = кириллица); наименование и место — по ключевым словам в любом порядке,
 * с учётом опечаток; точное выше опечаток.
 */
import type { Item } from './inventory'

const LOOKALIKE: Record<string, string> = { a: 'а', b: 'в', c: 'с', e: 'е', h: 'н', k: 'к', m: 'м', o: 'о', p: 'р', t: 'т', x: 'х', y: 'у' }

export const norm = (s: string) => s.toLowerCase().replace(/ё/g, 'е')
/** Только буквы и цифры, похожие латинские — русскими: «M 205» = «М-205». */
export const compact = (s: string) => [...norm(s)].filter(ch => /[\p{L}\p{N}]/u.test(ch)).map(ch => LOOKALIKE[ch] ?? ch).join('')
export const words = (s: string) => norm(s).split(/[^\p{L}\p{N}]+/u).filter(Boolean)

/** Сколько опечаток прощать: короткое — точно, от 4 букв — одну, от 7 — две. */
export const allowed = (len: number) => (len < 4 ? 0 : len < 7 ? 1 : 2)

/** Расстояние между строками с перестановкой соседних, не больше [max] (больше — max + 1). */
export function distance(a: string, b: string, max: number): number {
  if (Math.abs(a.length - b.length) > max) return max + 1
  let prev2 = new Array(b.length + 1).fill(0), prev = Array.from({ length: b.length + 1 }, (_, i) => i), cur = new Array(b.length + 1).fill(0)
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i
    let rowMin = cur[0]
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2] + 1)
      cur[j] = v
      if (v < rowMin) rowMin = v
    }
    if (rowMin > max) return max + 1
    const t = prev2; prev2 = prev; prev = cur; cur = t
  }
  return prev[b.length]
}

/** Слово [w] начинается со слова запроса [t] с учётом опечаток. */
export function fuzzyPrefix(w: string, t: string): boolean {
  if (w.startsWith(t)) return true
  const k = allowed(t.length)
  if (k === 0) return false
  for (let n = t.length - 1; n <= t.length + 1; n++) if (n >= 1 && n <= w.length && distance(w.slice(0, n), t, k) <= k) return true
  return false
}

/** Насколько предмет подходит под запрос; 0 — нет. */
export function scoreItem(i: Item, query: string): number {
  const q = query.trim()
  if (!q) return 0
  const c = compact(q)
  if (!c) return 0
  const inv = compact(i.inventory)
  if (inv) {
    if (inv === c || i.number === q) return 10000
    if (inv.startsWith(c)) return 9000
    if (c.length >= 3 && inv.includes(c)) return 8000
  }
  const tokens = words(q)
  if (!tokens.length) return 0
  const ws = [...words(i.name + ' ' + i.place), ...words(i.inventory)]
  let total = 0
  for (const t of tokens) {
    const tc = compact(t)
    if (ws.includes(t)) total += 30
    else if (ws.some(w => w.startsWith(t))) total += 20
    else if (tc.length >= 2 && inv.includes(tc)) total += 20
    else if (ws.some(w => fuzzyPrefix(w, t))) total += 8
    else return 0
  }
  return total
}

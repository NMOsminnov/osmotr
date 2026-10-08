/**
 * Книга Excel (.xlsx): чтение листов и запись простых книг — как на Android (Xlsx.kt), чтобы
 * описи разбирались одинаково, а отчёты с обеих версий были одного вида.
 *
 * Чтение — без браузерного разбора XML (его нет в фоновом потоке): свой проход по тегам.
 * Объединённые ячейки заполняются значением на весь диапазон (шапка в два этажа, разделы на
 * всю ширину); ячейки без адреса (их пишут сторонние программы) — по порядку.
 */
import { unzipSync, zipSync, strToU8, strFromU8 } from 'fflate'

export interface Sheet { name: string; rows: string[][] }

/** Сущности XML → символы. */
function unescape(s: string): string {
  if (s.indexOf('&') < 0) return s
  return s.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (_, e: string) => {
    switch (e) {
      case 'amp': return '&'
      case 'lt': return '<'
      case 'gt': return '>'
      case 'quot': return '"'
      case 'apos': return "'"
    }
    return String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10))
  })
}

function attr(tag: string, name: string): string | null {
  const m = new RegExp(`(?:^|\\s)${name}="([^"]*)"`).exec(tag)
  return m ? unescape(m[1]) : null
}

/** Номер столбца по буквам: A → 0, AA → 26. */
function colIndex(letters: string): number {
  if (!letters) return -1
  let n = 0
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n - 1
}

function col(i: number): string {
  let n = i + 1, s = ''
  while (n > 0) { s = String.fromCharCode(65 + ((n - 1) % 26)) + s; n = Math.floor((n - 1) / 26) }
  return s
}

/**
 * Число как его показывает Excel, без хвостов: «996555123456.0» → «996555123456», «1E+5» →
 * «100000» (как BigDecimal.stripTrailingZeros().toPlainString() на Android).
 */
export function plain(raw: string): string {
  const m = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(raw.trim())
  if (!m || (!m[2] && !m[3])) return raw
  const sign = m[1] === '-' ? '-' : ''
  let digits = (m[2] || '') + (m[3] || '')
  let point = (m[2] || '').length + (m[4] ? parseInt(m[4], 10) : 0)
  if (point < 0) { digits = '0'.repeat(-point) + digits; point = 0 }
  if (point > digits.length) digits = digits + '0'.repeat(point - digits.length)
  let int = digits.slice(0, point).replace(/^0+/, '') || '0'
  const frac = digits.slice(point).replace(/0+$/, '')
  const out = frac ? `${int}.${frac}` : int
  return out === '0' ? '0' : sign + out
}

function sharedStrings(xml: string): string[] {
  const out: string[] = []
  // Строка — <si>; внутри — <t> (бывает кусками разметки <r><t>…</t></r>); <rPh> — фонетика, не текст.
  const si = /<si\b[^>]*>([\s\S]*?)<\/si>|<si\b[^>]*\/>/g
  let m: RegExpExecArray | null
  while ((m = si.exec(xml))) {
    const body = (m[1] || '').replace(/<rPh\b[\s\S]*?<\/rPh>/g, '')
    let text = ''
    const t = /<t\b[^>]*>([\s\S]*?)<\/t>|<t\b[^>]*\/>/g
    let k: RegExpExecArray | null
    while ((k = t.exec(body))) text += unescape(k[1] || '')
    out.push(text)
  }
  return out
}

/** Ячейки листа строками: номер строки — как в Excel (пустые — пустыми списками). */
function cells(xml: string, shared: string[], progress?: (part: number) => void, rowLimit = Infinity): string[][] {
  const rows = new Map<number, Map<number, string>>()
  const merges: string[] = []
  let row = 0, colNo = -1
  // Теги по порядку: строка, ячейка, её значение, объединения.
  const re = /<(row|c|mergeCell)\b([^>]*?)(\/?)>|<\/c>/g
  let m: RegExpExecArray | null
  let told = 0
  const len = xml.length
  while ((m = re.exec(xml))) {
    if (progress && m.index - told > 512 * 1024) { told = m.index; progress(m.index / len) }
    if (m[0] === '</c>') continue
    const tag = m[1], attrs = m[2]
    if (tag === 'row') {
      const r = attr(attrs, 'r')
      row = r ? parseInt(r, 10) : row + 1
      colNo = -1
      if (row > rowLimit) break
      continue
    }
    if (tag === 'mergeCell') { const ref = attr(attrs, 'ref'); if (ref) merges.push(ref); continue }
    // Ячейка.
    const ref = attr(attrs, 'r') || ''
    const type = attr(attrs, 't') || ''
    const letters = /^[A-Za-z]+/.exec(ref)?.[0] || ''
    colNo = letters ? colIndex(letters) : colNo + 1
    if (m[3] === '/') continue  // пустая ячейка <c …/>
    const end = xml.indexOf('</c>', re.lastIndex)
    if (end < 0) break
    const inner = xml.slice(re.lastIndex, end)
    re.lastIndex = end + 4
    let raw = ''
    if (type === 'inlineStr') {
      const body = inner.replace(/<rPh\b[\s\S]*?<\/rPh>/g, '')
      const t = /<t\b[^>]*>([\s\S]*?)<\/t>/g
      let k: RegExpExecArray | null
      while ((k = t.exec(body))) raw += unescape(k[1])
    } else {
      const v = /<v\b[^>]*>([\s\S]*?)<\/v>/.exec(inner)
      raw = v ? unescape(v[1]) : ''
    }
    let value: string
    switch (type) {
      case 's': value = shared[parseInt(raw, 10)] ?? ''; break
      case 'inlineStr': case 'str': case 'e': value = raw; break
      case 'b': value = raw === '1' ? 'ИСТИНА' : 'ЛОЖЬ'; break
      default: value = plain(raw)
    }
    if (colNo >= 0 && value !== '') {
      let r = rows.get(row)
      if (!r) rows.set(row, (r = new Map()))
      r.set(colNo, value)
    }
  }
  for (const ref of merges) {
    const [from, to] = ref.split(':')
    if (!from || !to) continue
    const at = (s: string) => [parseInt(s.replace(/^[A-Za-z]+/, ''), 10) || 0, colIndex(/^[A-Za-z]+/.exec(s)?.[0] || '')]
    const [r1, c1] = at(from), [r2, c2] = at(to)
    const v = rows.get(r1)?.get(c1)
    if (v === undefined) continue
    if ((r2 - r1 + 1) * (c2 - c1 + 1) > 5000) continue  // объединение на весь лист — не размножать
    for (let r = r1; r <= r2; r++) {
      let mr = rows.get(r)
      if (!mr) rows.set(r, (mr = new Map()))
      for (let c = c1; c <= c2; c++) if (!mr.has(c)) mr.set(c, v)
    }
  }
  if (!rows.size) return []
  const last = Math.max(...rows.keys())
  const out: string[][] = []
  for (let r = 1; r <= last; r++) {
    const mr = rows.get(r)
    if (!mr) { out.push([]); continue }
    const width = Math.max(...mr.keys()) + 1
    const line: string[] = new Array(width)
    for (let c = 0; c < width; c++) line[c] = mr.get(c) ?? ''
    out.push(line)
  }
  return out
}

/**
 * Все листы книги по порядку — как их видно в Excel. Не .xlsx или повреждена — пусто.
 * [progress] — доля прочитанного (0…1) и имя листа.
 */
export function sheets(data: Uint8Array, progress?: (part: number, sheet: string) => void, rowLimit = Infinity): Sheet[] {
  let files: Record<string, Uint8Array>
  try { files = unzipSync(data) } catch { return [] }
  const text = (name: string) => (files[name] ? strFromU8(files[name]) : null)
  const shared = sharedStrings(text('xl/sharedStrings.xml') || '')
  const rels = new Map<string, string>()
  for (const m of (text('xl/_rels/workbook.xml.rels') || '').matchAll(/<Relationship\b([^>]*?)\/?>/g))
    rels.set(attr(m[1], 'Id') || '', attr(m[1], 'Target') || '')
  let parts: [string, string][] = []
  for (const m of (text('xl/workbook.xml') || '').matchAll(/<sheet\b([^>]*?)\/?>/g)) {
    const name = attr(m[1], 'name') || 'Лист'
    const rid = /\s[\w]*:?id="([^"]*)"/.exec(m[1].replace(/sheetId="[^"]*"/, ''))?.[1] || ''
    const target = rels.get(rid)
    if (!target) continue
    parts.push([name, target.startsWith('/') ? target.slice(1) : 'xl/' + target.replace(/^\.\//, '')])
  }
  if (!parts.length) parts = Object.keys(files).filter(n => /^xl\/worksheets\/sheet\d+\.xml$/.test(n))
    .sort((a, b) => parseInt(a.replace(/\D/g, ''), 10) - parseInt(b.replace(/\D/g, ''), 10))
    .map((p, i) => [`Лист ${i + 1}`, p])
  const sizes = parts.map(([, p]) => files[p]?.length || 0)
  const total = Math.max(1, sizes.reduce((a, b) => a + b, 0))
  let done = 0
  const out: Sheet[] = []
  parts.forEach(([name, path], k) => {
    const bytes = files[path]
    if (!bytes) return
    const base = done
    const rows = cells(strFromU8(bytes), shared, p => progress?.((base + p * sizes[k]) / total, name), rowLimit)
    done += sizes[k]
    progress?.(done / total, name)
    out.push({ name, rows })
  })
  return out
}

// ---------- Запись ----------

/** Стили ячеек: обычный, жирный заголовок, зелёная строка (сделано), розовая. */
export const PLAIN = 0, HEADER = 1, GREEN = 2, RED = 3

export interface Out { name: string; rows: (string | number | null | undefined)[][]; widths?: number[]; rowStyle?: (r: number) => number }

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
}

/** Имена листов по правилам Excel: до 31 знака, без []:*?/\, без повторов. */
function uniqueNames(names: string[]): string[] {
  const used = new Set<string>()
  return names.map(raw => {
    const base = (raw.replace(/[[\]:*?/\\]/g, ' ').trim() || 'Лист').slice(0, 28)
    let name = base, i = 2
    while (used.has(name.toLowerCase())) name = `${base} ${i++}`
    used.add(name.toLowerCase())
    return name
  })
}

/**
 * Книга из нескольких листов. Текст — ячейками inlineStr (номера и телефоны не превращаются в
 * числа), числа — числами. Заголовок закреплён и с фильтром.
 */
export function writeBook(sheetsOut: Out[]): Uint8Array {
  const names = uniqueNames(sheetsOut.map(s => s.name))
  const n = sheetsOut.length
  const files: Record<string, Uint8Array> = {}
  const put = (name: string, text: string) => { files[name] = strToU8(text) }
  const head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
  put('[Content_Types].xml', head + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    Array.from({ length: n }, (_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('') +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>')
  put('_rels/.rels', head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
  put('xl/workbook.xml', head + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>' +
    names.map((nm, i) => `<sheet name="${esc(nm)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') + '</sheets></workbook>')
  put('xl/_rels/workbook.xml.rels', head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    Array.from({ length: n }, (_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('') +
    `<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`)
  put('xl/styles.xml', head + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="4"><xf xfId="0"/><xf xfId="0" fontId="1" applyFont="1"/><xf xfId="0" fillId="2" applyFill="1"/><xf xfId="0" fillId="3" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
  sheetsOut.forEach((sh, k) => {
    const x: string[] = [head + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>']
    if (sh.widths?.length) x.push('<cols>' + sh.widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('') + '</cols>')
    x.push('<sheetData>')
    let width = 0
    sh.rows.forEach((row, r) => {
      width = Math.max(width, row.length)
      x.push(`<row r="${r + 1}">`)
      const style = r === 0 ? HEADER : (sh.rowStyle?.(r) ?? PLAIN)
      const s = style !== PLAIN ? ` s="${style}"` : ''
      row.forEach((v, c) => {
        const ref = `${col(c)}${r + 1}`
        if (v === null || v === undefined) { if (s) x.push(`<c r="${ref}"${s}/>`) }
        else if (typeof v === 'number') x.push(`<c r="${ref}"${s}><v>${v}</v></c>`)
        else x.push(`<c r="${ref}" t="inlineStr"${s}><is><t xml:space="preserve">${esc(String(v))}</t></is></c>`)
      })
      x.push('</row>')
    })
    x.push('</sheetData>')
    if (sh.rows.length > 1 && width > 0) x.push(`<autoFilter ref="A1:${col(width - 1)}${sh.rows.length}"/>`)
    x.push('</worksheet>')
    put(`xl/worksheets/sheet${k + 1}.xml`, x.join(''))
  })
  return zipSync(files, { level: 6 })
}

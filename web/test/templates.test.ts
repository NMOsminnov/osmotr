/**
 * Те же самые кривые описи, что и на Android (shared/src/jvmTest/resources/templates, генератор —
 * tools/make_templates.py): разбор PWA должен совпадать с Android один в один.
 */
import { describe, expect, test } from 'vitest'
import { readFileSync } from 'node:fs'
import { sheets } from '../src/xlsx'
import { detect, items as itemsOf, number, headerScore, Field, folderName, type Item } from '../src/inventory'
import { scoreItem } from '../src/search'

const dir = new URL('../../shared/src/jvmTest/resources/templates/', import.meta.url)
const book = (name: string) => sheets(new Uint8Array(readFileSync(new URL(name, dir))))
const items = (name: string) => {
  const tables = book(name).map(s => [s, detect(s)] as const).filter(([, t]) => t)
  expect(tables.length, name + ': не нашли список').toBeGreaterThan(0)
  return tables.flatMap(([s, t]) => itemsOf(s, t!))
}
const invs = ['777/1001', '777/1002', '777/1003', 'К-310', 'М 205', 'F0000000009001', '777/,1095', '7770001007']
const standard = (its: Item[]) => {
  expect(its.map(i => i.inventory)).toEqual(invs)
  expect(its[0].name).toBe('Сканер штрих-кода Honeywell')
}

describe('кривые описи', () => {
  test('01 ОСВ из 1С', () => { const its = items('01-osv-1c.xlsx'); standard(its); expect(its[0].sum).toBeCloseTo(6900); expect(its[0].place).toBe('Основное подразделение') })
  test('02 ИНВ-1', () => { const its = items('02-inv1.xlsx'); standard(its); expect(its.map(i => i.number)).toEqual(['1','2','3','4','5','6','7','8']); expect(its[3].initial).toBeCloseTo(1044321.55); expect(its[0].qty).toBe('1') })
  test('03 без шапки', () => { const its = items('03-no-header.xlsx'); standard(its); expect(its[0].initial).toBeCloseTo(6900); expect(its[0].sum).toBeCloseTo(6210) })
  test('04 английский', () => { const its = items('04-english.xlsx'); standard(its); expect(its[0].place).toBe('Room 101'); expect(its[0].priority).toBe('2') })
  test('05 киргизский', () => { const its = items('05-kyrgyz.xlsx'); standard(its); expect(its[0].initial).toBeCloseTo(6900); expect(its[0].qty).toBe('1') })
  test('06 латиница в заголовках', () => { const its = items('06-lookalikes.xlsx'); standard(its); expect(its[0].priority).toBe('2') })
  test('07 инвентарник в наименовании', () => { const its = items('07-inv-in-name.xlsx'); standard(its); expect(its[6].name).toBe('Персональный компьютер (системный блок)') })
  test('08 числа текстом', () => { const its = items('08-text-numbers.xlsx'); expect(its[0].inventory).toBe('130002381'); expect(its[0].number).toBe('1'); expect(its[3].initial).toBeCloseTo(1044321.55); expect(its[0].priority).toBe('2') })
  test('09 два блока', () => { const its = items('09-two-blocks.xlsx'); standard(its); expect(its[0].place).toBe('Здание 1 (главный корпус)'); expect(its[7].place).toBe('Здание 2 (склад)') })
  test('10 таблица в углу', () => standard(items('10-offset.xlsx')))
  test('11 лишние листы', () => { expect(book('11-many-sheets.xlsx').filter(s => detect(s)).map(s => s.name)).toEqual(['Список ОС']); standard(items('11-many-sheets.xlsx')) })
  test('12 номер — это инвентарник', () => standard(items('12-number-is-inventory.xlsx')))
  test('13 инвентарный, а не заводской', () => { const its = items('13-several-numbers.xlsx'); standard(its); expect(its[0].initial).toBeCloseTo(6900); expect(its[0].sum).toBeCloseTo(6210) })
  test('14 объединённое наименование', () => standard(items('14-merged-name.xlsx')))
  test('15 место и количество', () => { const its = items('15-place-qty.xlsx'); standard(its); expect(its[0].place).toBe('Каб. 201'); expect(its[0].qty).toBe('2') })
  test('16 разделы', () => { const its = items('16-sections.xlsx'); standard(its); expect([...new Set(its.map(i => i.place))]).toEqual(['Кабинет 101', 'Кабинет 102', 'Кабинет 103']) })
  test('17 без адресов, формулы', () => { const its = items('17-raw-xml.xlsx'); standard(its); expect(its[1].name).toBe('Сервер Dell PowerEdge R640'); expect(its[1].initial).toBeCloseTo(450000) })
  test('18 нумерация заново', () => { const its = items('18-restart-numbers.xlsx'); standard(its); expect(its[4].number).toBe('1') })
  test('19 лишние столбцы', () => { const its = items('19-noisy.xlsx'); standard(its); expect(its[0].initial).toBeCloseTo(6900); expect(its[0].sum).toBeCloseTo(6210); expect(its[0].number).toBe('1') })
  test('20 без инвентарников', () => { const its = items('20-no-inventory.xlsx'); expect(its.length).toBe(8); expect(its[0].inventory).toBe('') })
  const patterns = ['ОС-000123', 'INV-2024-0001', '0001234', '1010400123', 'ВА0000123', '01.01.0023.5', 'А/45-12', 'КГ 123 456', '12345/2', 'Н179']
  test('21 опечатки, инвентарники всех видов', () => { const its = items('21-typos-patterns.xlsx'); expect(its.map(i => i.inventory)).toEqual(patterns); expect(its[0].initial).toBeCloseTo(1000.5) })
  test('22 без шапки, всех видов', () => { const its = items('22-patterns-no-header.xlsx'); expect(its.map(i => i.inventory)).toEqual(patterns); expect(its[1].name).toBe('Кресло офисное') })
})

test('числа', () => {
  expect(number('1 044 321,55 сом')).toBeCloseTo(1044321.55)
  expect(number('1,044,321.55')).toBeCloseTo(1044321.55)
  expect(number('1.044.321,55')).toBeCloseTo(1044321.55)
  expect(number('1.')).toBe(1)
  expect(number('777/1001')).toBeNull()
})

test('заголовки с опечатками', () => {
  expect(headerScore(Field.NAME, 'Наименавание')).toBeGreaterThanOrEqual(8)
  expect(headerScore(Field.INVENTORY, 'Инвентраный номер')).toBeGreaterThanOrEqual(8)
  expect(headerScore(Field.QUANTITY, 'Количетсво')).toBeGreaterThanOrEqual(8)
})

test('поиск по ключевым словам с опечатками', () => {
  const p: Item = { list: 'Опись', number: '1', inventory: '777/1001', name: 'Принтер лазерный Canon LBP', initial: null, sum: null, priority: '', status: '', row: 1, qty: '', place: 'Каб. 204' }
  expect(scoreItem(p, 'canon принтер')).toBeGreaterThan(0)
  expect(scoreItem(p, 'принер')).toBeGreaterThan(0)
  expect(scoreItem(p, 'лазреный')).toBeGreaterThan(0)
  expect(scoreItem(p, 'каб 204')).toBeGreaterThan(0)
  expect(scoreItem(p, '7771001')).toBeGreaterThan(scoreItem(p, 'принтер'))
  expect(scoreItem(p, 'принтер холодильник')).toBe(0)
  expect(scoreItem(p, 'кот')).toBe(0)
})

test('имя папки', () => {
  const it = (n: string, inv: string): Item => ({ list: '', number: n, inventory: inv, name: 'ИВЛ', initial: null, sum: null, priority: '', status: '', row: 1, qty: '', place: '' })
  expect(folderName(it('13', '1562/65'))).toBe('13. 1562∕65')
  expect(folderName(it('13', ''))).toBe('13')
  expect(folderName(it('13', '1562/65'), 3)).toBe('13. 1562∕65 +2 шт')
})

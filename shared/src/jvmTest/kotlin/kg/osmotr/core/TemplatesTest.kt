package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Самые кривые описи (tools/make_templates.py): каждая разбирается сама, без вопросов. */
class TemplatesTest {
    private fun file(name: String) = File(System.getProperty("templates"), name)

    /** Предметы всех листов-списков книги; вопросов — ни одного. */
    private fun items(name: String, ask: Boolean = false): List<Inventory.Item> {
        val tables = Xlsx.sheets(file(name).readBytes()).mapNotNull { s -> Inventory.detect(s)?.let { s to it } }
        assertTrue("$name: не нашли список", tables.isNotEmpty())
        assertEquals("$name: спорное ${tables.map { it.second.disputed }}", ask, tables.any { it.second.disputed.isNotEmpty() })
        return tables.flatMap { (s, t) -> Inventory.items(s, t) }
    }

    private val invs = listOf("777/1001", "777/1002", "777/1003", "К-310", "М 205", "F0000000009001", "777/,1095", "7770001007")

    private fun standard(name: String, its: List<Inventory.Item>) {
        assertEquals("$name: инвентарники", invs, its.map { it.inventory })
        assertEquals("$name: наименование", "Сканер штрих-кода Honeywell", its[0].name)
    }

    @Test fun t01_осв_из_1с_шапка_в_два_этажа_группы_итоги() {
        val its = items("01-osv-1c.xlsx"); standard("01", its)
        assertEquals("сальдо на конец, дебет", 6900.0, its[0].sum!!, 0.01)
        assertEquals("раздел — местом", "Основное подразделение", its[0].place)
    }

    @Test fun t02_инв1_три_этажа_нумерация_столбцов_повтор_шапки() {
        val its = items("02-inv1.xlsx"); standard("02", its)
        assertEquals((1..8).map { "$it" }, its.map { it.number })
        assertEquals(1044321.55, its[3].initial!!, 0.01)
        assertEquals("1", its[0].qty)
    }

    @Test fun t03_без_шапки() {
        val its = items("03-no-header.xlsx"); standard("03", its)
        assertEquals(6900.0, its[0].initial!!, 0.01); assertEquals(6210.0, its[0].sum!!, 0.01)
    }

    @Test fun t04_по_английски() {
        val its = items("04-english.xlsx"); standard("04", its)
        assertEquals("Room 101", its[0].place); assertEquals("2", its[0].priority); assertEquals(6900.0, its[0].initial!!, 0.01)
    }

    @Test fun t05_по_киргизски() {
        val its = items("05-kyrgyz.xlsx"); standard("05", its)
        assertEquals(6900.0, its[0].initial!!, 0.01); assertEquals("1", its[0].qty)
    }

    @Test fun t06_латиница_в_заголовках_переносы_капс() {
        val its = items("06-lookalikes.xlsx"); standard("06", its)
        assertEquals(6900.0, its[0].initial!!, 0.01); assertEquals("2", its[0].priority)
    }

    @Test fun t07_инвентарник_в_наименовании() {
        val its = items("07-inv-in-name.xlsx"); standard("07", its)
        assertEquals("Персональный компьютер (системный блок)", its[6].name)
    }

    @Test fun t08_числа_текстом_приоритет_словами() {
        val its = items("08-text-numbers.xlsx")
        assertEquals("130002381", its[0].inventory); assertEquals("1", its[0].number)
        assertEquals(1044321.55, its[3].initial!!, 0.01)
        assertEquals("средний → 2", "2", its[0].priority)
    }

    @Test fun t09_два_блока_со_своими_шапками() {
        val its = items("09-two-blocks.xlsx"); standard("09", its)
        assertEquals("Здание 1 (главный корпус)", its[0].place); assertEquals("Здание 2 (склад)", its[7].place)
    }

    @Test fun t10_таблица_в_углу_листа() = standard("10", items("10-offset.xlsx"))

    @Test fun t11_лишние_листы() {
        val sheets = Xlsx.sheets(file("11-many-sheets.xlsx").readBytes())
        assertEquals("только «Список ОС»", listOf("Список ОС"), sheets.filter { Inventory.detect(it) != null }.map { it.name })
        standard("11", items("11-many-sheets.xlsx"))
    }

    @Test fun t12_номер_это_инвентарник_а_код_это_счёт() = standard("12", items("12-number-is-inventory.xlsx"))

    @Test fun t13_инвентарный_а_не_заводской_и_не_паспорт() {
        val its = items("13-several-numbers.xlsx"); standard("13", its)
        assertEquals(6900.0, its[0].initial!!, 0.01); assertEquals("остаточная", 6210.0, its[0].sum!!, 0.01)
    }

    @Test fun t14_наименование_объединено_на_три_столбца() = standard("14", items("14-merged-name.xlsx"))

    @Test fun t15_место_и_количество() {
        val its = items("15-place-qty.xlsx"); standard("15", its)
        assertEquals("Каб. 201", its[0].place); assertEquals("2", its[0].qty)
    }

    @Test fun t16_разделы_кабинеты() {
        val its = items("16-sections.xlsx"); standard("16", its)
        assertEquals(listOf("Кабинет 101", "Кабинет 102", "Кабинет 103"), its.map { it.place }.distinct())
    }

    @Test fun t17_без_адресов_формулы_разметка() {
        val its = items("17-raw-xml.xlsx"); standard("17", its)
        assertEquals("Сервер Dell PowerEdge R640", its[1].name); assertEquals(450000.0, its[1].initial!!, 0.01)
    }

    @Test fun t18_нумерация_заново_пустые_строки() {
        val its = items("18-restart-numbers.xlsx"); standard("18", its)
        assertEquals("1", its[4].number)
    }

    @Test fun t19_лишние_столбцы() {
        val its = items("19-noisy.xlsx"); standard("19", its)
        assertEquals(6900.0, its[0].initial!!, 0.01); assertEquals(6210.0, its[0].sum!!, 0.01)
        assertEquals("номер — по порядку", "1", its[0].number)
    }

    @Test fun t20_инвентарников_нет_не_спрашиваем() {
        // Инвентарники в описи есть всегда (автор); нет — всё равно без вопросов, по наименованиям.
        val its = items("20-no-inventory.xlsx")
        assertEquals(8, its.size); assertEquals("Сканер штрих-кода Honeywell", its[0].name); assertEquals("", its[0].inventory)
    }

    @Test fun t21_инвентарник_без_узнаваемого_заголовка() {
        // Заголовок «Шифр ОС-1» не из словаря — столбец находится по данным.
        val s = Xlsx.Sheet("Лист1", listOf(listOf("№", "Наименование", "Учётный шифр", "Сумма")) +
            listOf("777/1001" to "Сканер", "К-310" to "Генератор дизельный", "М 205" to "Видеокамера").mapIndexed { i, (inv, n) -> listOf("${i + 1}", n, inv, "100") })
        val t = Inventory.detect(s)!!
        assertEquals(listOf("777/1001", "К-310", "М 205"), Inventory.items(s, t).map { it.inventory })
    }

    @Test fun числа() {
        assertEquals(1044321.55, Inventory.number("1 044 321,55 сом")!!, 0.001)
        assertEquals(1044321.55, Inventory.number("1,044,321.55")!!, 0.001)
        assertEquals(1044321.55, Inventory.number("1.044.321,55")!!, 0.001)
        assertEquals(1.0, Inventory.number("1.")!!, 0.0)
        assertEquals(null, Inventory.number("777/1001"))
    }

    private val patterns = listOf("ОС-000123", "INV-2024-0001", "0001234", "1010400123", "ВА0000123", "01.01.0023.5", "А/45-12", "КГ 123 456", "12345/2", "Н179")

    @Test fun t21_опечатки_в_заголовках_инвентарники_всех_видов() {
        val its = items("21-typos-patterns.xlsx")
        assertEquals(patterns, its.map { it.inventory })
        assertEquals("Стол письменный", its[0].name); assertEquals(1000.5, its[0].initial!!, 0.01); assertEquals("1", its[0].qty)
    }

    @Test fun t22_инвентарники_всех_видов_без_шапки() {
        val its = items("22-patterns-no-header.xlsx")
        assertEquals(patterns, its.map { it.inventory }); assertEquals("Кресло офисное", its[1].name)
    }

    @Test fun поиск_по_ключевым_словам_с_опечатками() {
        fun item(inv: String, name: String, place: String = "") = Inventory.Item("Опись", "1", inv, name, null, null, "", "", 1, place = place)
        val printer = item("777/1001", "Принтер лазерный Canon LBP", "Каб. 204")
        assertTrue("слова в любом порядке", Search.scoreItem(printer, "canon принтер") > 0)
        assertTrue("опечатка", Search.scoreItem(printer, "принер") > 0)
        assertTrue("опечатка и перестановка", Search.scoreItem(printer, "лазреный") > 0)
        assertTrue("по месту", Search.scoreItem(printer, "каб 204") > 0)
        assertTrue("инвентарник без разделителей", Search.scoreItem(printer, "7771001") > Search.scoreItem(printer, "принтер"))
        assertEquals("чужое слово — не найдено", 0, Search.scoreItem(printer, "принтер холодильник"))
        assertEquals("короткое — без опечаток", 0, Search.scoreItem(printer, "кот"))
        assertTrue("точное выше опечатки", Search.scoreItem(printer, "принтер") > Search.scoreItem(printer, "принер"))
        // Набрали хвост с бирки «2545» — инвентарник 013/2545 выше строки № 2545 (найдено на описи в 5,7 тыс. строк).
        val bed = Inventory.Item("Опись", "2545", "013/627", "Кровать", null, null, "", "", 2545)
        val laptop = Inventory.Item("Опись", "12", "013/2545", "Ноутбук ASER", null, null, "", "", 12)
        assertTrue("хвост инвентарника выше № строки", Search.scoreItem(laptop, "2545") > Search.scoreItem(bed, "2545"))
        assertTrue("№ строки тоже находится", Search.scoreItem(bed, "2545") > 0)
        assertTrue("латиница в названии — набрано кириллицей", Search.scoreItem(laptop, "ноутбук асер") > 0)
        assertTrue("и с опечаткой", Search.scoreItem(laptop, "ноутбк асер") > 0)
        assertTrue("и наоборот", Search.scoreItem(Inventory.Item("Опись", "1", "1", "Монитор Делл", null, null, "", "", 1), "dell") > 0)
    }

    @Test fun заголовки_с_опечатками() {
        assertTrue(Inventory.headerScore(Inventory.Field.NAME, "Наименавание") >= 8)
        assertTrue(Inventory.headerScore(Inventory.Field.INVENTORY, "Инвентраный номер") >= 8)
        assertTrue(Inventory.headerScore(Inventory.Field.INITIAL, "Первоночальная стоимость") >= 8)
        assertTrue(Inventory.headerScore(Inventory.Field.QUANTITY, "Количетсво") >= 8)
    }
}

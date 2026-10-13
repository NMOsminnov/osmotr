package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Импорт уже отсмотренного (автор, 13.10.2026): «Есть инвентарник — отмечаем выполненным»; столбец
 * с инвентарниками выбирает человек; строки, объединённые по ширине, — заголовки, пропускаются.
 */
class EarlierImportTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var obj: File

    @Before fun setUp() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        obj = Store.createFolder(Store.root, "Склад")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "склад.xlsx"), listOf(Xlsx.Out("Опись", listOf(
            listOf("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость"),
            listOf(1, "Ноутбук", "555/1", 40000), listOf(2, "Ноутбук", "555/2", 40000),
            listOf(3, "Проектор", "555/3", 25000), listOf(4, "Стол", "0101-345АБ", 9000),
        ))))
    }

    /** Таблица человека: заголовок на всю ширину, шапка, отметки; инвентарник — во втором столбце. */
    private fun ихТаблица() = Xlsx.Sheet("Отметки", listOf(
        listOf("Кабинет 3 — отсмотрено 2025", "Кабинет 3 — отсмотрено 2025", "Кабинет 3 — отсмотрено 2025"),
        listOf("№", "Инв. номер", "Отметка"),
        listOf("1", "555/1", "да"),
        listOf("2", "0101-345АБ (у окна)", "да"),
        listOf("3", "999/9", "да"),
        listOf("Кабинет 5", "Кабинет 5", "Кабинет 5"),
        listOf("4", "555/2", "да"),
    ), wide = setOf(0, 5))

    private fun items() = Inventory.parse(Inventory.fileIn(obj)!!).items

    @Test fun ширина_пропускается_инвентарник_находится_и_в_ячейке_с_пояснением() {
        val p = Inventory.previewEarlier(obj, ихТаблица(), 1)
        assertEquals(setOf("555/1", "0101-345АБ", "555/2"), p.found.map { it.inventory }.toSet())
        assertEquals("заголовки на всю ширину", 2, p.skipped)
        assertEquals("шапка и неизвестный — не нашлись", listOf("Инв. номер", "999/9"), p.unknown)
        assertEquals(3, p.fresh)
    }

    @Test fun отмечает_осмотренным_без_снимков_и_второй_раз_ничего_не_добавляет() {
        val r = Inventory.importEarlier(obj, ихТаблица(), 1, "их.xlsx")
        assertEquals(3, r.fresh)
        val st = Inventory.status(obj)
        val all = items()
        assertTrue(st.inspected(all.first { it.inventory == "555/1" }))
        assertTrue(st.earlier(all.first { it.inventory == "555/1" }))
        assertFalse(st.inspected(all.first { it.inventory == "555/3" }))
        assertEquals(listOf("555/1", "0101-345АБ", "555/2"), File(obj, Inventory.EARLIER).readLines().filter { it.isNotBlank() })
        assertEquals("повтор — без новых", 0, Inventory.importEarlier(obj, ихТаблица(), 1, "их.xlsx").fresh)
        assertTrue("журнал цел", Journal.verify(obj).ok)
    }

    @Test fun в_отчёте_ранее_а_снятое_да() {
        Inventory.importEarlier(obj, ихТаблица(), 1, "их.xlsx")
        val проектор = items().first { it.inventory == "555/3" }
        File(Inventory.openFolder(obj, проектор), "a.jpg").writeBytes(ByteArray(10))
        Inventory.writeReport(obj)
        val book = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes())
        val list = book.first { it.name == "Опись" }.rows
        val head = list[0]
        fun осмотрено(inv: String) = list.first { it.getOrNull(head.indexOf("Инв. номер")) == inv }[head.indexOf("Осмотрено")]
        assertEquals("Ранее", осмотрено("555/1"))
        assertEquals("Да", осмотрено("555/3"))
    }

    @Test fun нерабочее_без_снимков_не_ставится_даже_у_осмотренного_ранее() {
        Inventory.importEarlier(obj, ихТаблица(), 1, "их.xlsx")
        val ноутбук = items().first { it.inventory == "555/1" }
        assertEquals(0, Inventory.setBroken(obj, listOf(ноутбук), true))
        assertFalse(Inventory.status(obj).broken(ноутбук))
    }

    @Test fun excel_строки_объединённые_по_ширине_помечаются() {
        // Книга вручную: A1:C1 объединены (заголовок), B3:B4 — по высоте (не заголовок).
        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
            """<row r="1"><c r="A1" t="inlineStr"><is><t>Раздел</t></is></c></row>""" +
            """<row r="2"><c r="A2" t="inlineStr"><is><t>№</t></is></c><c r="B2" t="inlineStr"><is><t>Инв</t></is></c></row>""" +
            """<row r="3"><c r="A3"><v>1</v></c><c r="B3" t="inlineStr"><is><t>555/1</t></is></c></row>""" +
            """<row r="4"><c r="A4"><v>2</v></c></row>""" +
            """</sheetData><mergeCells count="2"><mergeCell ref="A1:C1"/><mergeCell ref="B3:B4"/></mergeCells></worksheet>"""
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Отметки" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                put("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""")
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }.toByteArray()
        val s = Xlsx.sheets(bytes).single()
        assertEquals(setOf(0), s.wide)
        assertEquals("555/1", s.rows[3][1])  // объединённое по высоте — значение размножено, строка не заголовок
    }
}

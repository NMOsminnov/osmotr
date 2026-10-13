package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Грязные данные описи (13.10.2026, вопрос автора: «самые разные случаи обработки невалидных
 * данных устранены?»): заглушки вместо инвентарника, ошибки Excel, повторы, переносы строк,
 * неразрывные пробелы, пустые наименования. Нераспознанное не должно ни ломать подбор столбцов,
 * ни склеивать разные предметы.
 */
class DirtyDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val грязный = Xlsx.Sheet("Опись", listOf(
        listOf("№", "Наименование", "Инв. номер", "Место"),
        listOf("1", "Стол", "б/н", "каб. 1"), listOf("2", "Стул", "Б/Н", "каб. 1"), listOf("3", "Шкаф", "-", "каб. 1"),
        listOf("4", "Тумба", "—", "каб. 1"), listOf("5", "Сейф", "нет", "каб. 2"), listOf("6", "#N/A", "777/1", "каб. 2"),
        listOf("7", "Принтер", "#N/A", "каб. 2"), listOf("8", "Ноутбук", "777/2", "каб. 2"), listOf("9", "Ноутбук (повтор)", "777/2", "каб. 2"),
        listOf("10", "Монитор\nSamsung", "777 3", "каб. 3"), listOf("11", "Кресло", "без номера", "каб. 3"),
        listOf("12", "Диван", "777/4", "каб. 3"), listOf("13", "Диван", "777/4", "каб. 3"),
        listOf("", "", "", ""), listOf("14", "   ", "777/5", "каб. 3"), listOf("15", "Лампа", "0", "каб. 3"),
    ))

    private fun items() = Inventory.distinctIds(Inventory.items(грязный, Inventory.detect(грязный)!!))

    @Test fun заглушки_не_отменяют_столбец_инвентарников() {
        val its = items()
        assertEquals(listOf("777/1", "777/2", "777 3", "777/4", "777/5"), its.map { it.inventory }.filter { it.isNotEmpty() }.distinct())
        // «б/н», «-», «—», «нет», «без номера», «0», «#N/A» — предметы без инвентарника, и все разные.
        val без = its.filter { it.inventory.isEmpty() }
        assertEquals(listOf("1", "2", "3", "4", "5", "7", "11", "15"), без.map { it.number })
        assertEquals(без.size, без.map(Inventory::id).toSet().size)
    }

    @Test fun ясная_шапка_не_отменяется_сбоями_в_ячейках() {
        // Больше половины ячеек — мусор; столбец «Инв. номер» всё равно он, а мусор — пусто у своих предметов.
        val s = Xlsx.Sheet("Опись", listOf(listOf("№", "Наименование", "Инв. номер")) +
            (1..10).map { n -> listOf("$n", "Предмет $n", if (n <= 3) "777/$n" else listOf("см. акт", "ЗНАЧ!", "уточнить", "?", "б/н", "—", "нет данных")[(n - 4) % 7]) })
        val its = Inventory.items(s, Inventory.detect(s)!!)
        assertEquals(10, its.size)
        assertEquals(listOf("777/1", "777/2", "777/3"), its.take(3).map { it.inventory })
        assertTrue("мусор — пусто у своего предмета", its.drop(3).all { it.inventory.isEmpty() })
    }

    @Test fun ошибки_excel_переносы_и_пустые_наименования() {
        val its = items()
        assertEquals("«#N/A» — не наименование", "", its.first { it.inventory == "777/1" }.name)
        assertEquals("Монитор Samsung", its.first { it.inventory == "777 3" }.name)
        assertTrue("инвентарник без наименования — предмет", its.any { it.inventory == "777/5" })
    }

    @Test fun повтор_инвентарника_сказан_в_итоге() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot(); Journal.forget()
        val obj = Store.createFolder(Store.root, "Грязная")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "грязная.xlsx"), listOf(Xlsx.Out("Опись", грязный.rows)))
        Inventory.writeReport(obj)
        val итог = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes()).first { it.name == "Итог" }.rows
        val i = итог.indexOfFirst { r -> r.any { it.startsWith("Инвентарник повторяется") } }
        assertTrue(итог.toString(), i >= 0)
        assertEquals(setOf("777/2", "777/4"), итог.drop(i + 1).takeWhile { it.isNotEmpty() && it[0].isNotEmpty() }.map { it[0] }.filter { it.startsWith("777") }.toSet())
    }

    @Test fun ошибка_формулы_в_книге_пустая_ячейка() {
        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
            """<row r="1"><c r="A1" t="e"><v>#N/A</v></c><c r="B1" t="inlineStr"><is><t>Стол</t></is></c></row></sheetData></worksheet>"""
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                fun put(n: String, t: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
                put("xl/workbook.xml", """<workbook xmlns:r="r"><sheets><sheet name="Л" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                put("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""")
                put("xl/worksheets/sheet1.xml", sheet)
            }
        }.toByteArray()
        assertEquals(listOf("", "Стол"), Xlsx.sheets(bytes).single().rows[0])
    }
}

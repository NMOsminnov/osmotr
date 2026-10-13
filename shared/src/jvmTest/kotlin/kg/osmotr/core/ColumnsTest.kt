package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Чтение описи вручную и плохие файлы (автор, 13.10.2026: «Есть ли возможность отредактировать
 * чтение файла (например, если автоматически не тот столбец считался)… продумай все варианты
 * использования, включая ситуацию, что подгрузили не тот файл или битый файл»).
 */
class ColumnsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
    }

    /** Опись, где подбор ошибается: «Код» похож на инвентарник больше, чем настоящий столбец без шапки. */
    private val лист = Xlsx.Sheet("Опись", listOf(
        listOf("№", "Наименование", "Код", "Бирка", "Цена"),
        listOf("1", "Ноутбук HP", "А-1", "555/1", "40000"),
        listOf("2", "Ноутбук HP", "А-1", "555/2", "40000"),
        listOf("3", "Проектор", "Б-7", "555/3", "25000"),
        listOf("4", "Стол", "В-2", "555/4", "9000"),
    ))

    private fun книга(vararg sheets: Xlsx.Sheet): ByteArray {
        val f = File(tmp.root.path + "/книга-${Platform.nanoTime()}.xlsx")
        Xlsx.writeBook(f, sheets.map { s -> Xlsx.Out(s.name, s.rows) })
        return f.readBytes()
    }

    @Test fun ручной_столбец_поверх_подбора_и_только_он() {
        val сам = Inventory.read(listOf(лист), Inventory.Choices())
        val выбор = Inventory.sealed(лист, Inventory.SheetChoice("Опись", columns = mapOf("INVENTORY" to 3)))
        val вручную = Inventory.read(listOf(лист), Inventory.Choices(listOf(выбор)))
        assertEquals(listOf("555/1", "555/2", "555/3", "555/4"), вручную.items.map { it.inventory })
        assertEquals("наименование — по-прежнему подбор", сам.items.map { it.name }, вручную.items.map { it.name })
        assertEquals("столбец — одному полю", 1, вручную.sheets[0].table!!.columns.count { it.value == 3 })
    }

    @Test fun поле_можно_не_брать_а_лист_пропустить() {
        val безЦены = Inventory.read(listOf(лист), Inventory.Choices(listOf(Inventory.SheetChoice("Опись", columns = mapOf("INITIAL" to -1)))))
        assertTrue(безЦены.items.all { it.initial == null })
        val пропуск = Inventory.read(listOf(лист), Inventory.Choices(listOf(Inventory.SheetChoice("Опись", skip = true))))
        assertTrue(пропуск.items.isEmpty())
        assertTrue(Inventory.warnings(пропуск).any { "Ни одного предмета" in it })
    }

    @Test fun шапка_и_первая_строка_указываются() {
        // Сверху — служебные строки, которые подбор принял бы за данные без шапки.
        val s = Xlsx.Sheet("Л", listOf(
            listOf("Акт 5", "", ""), listOf("утверждаю", "", ""),
            listOf("п", "что", "инв"),
            listOf("1", "Шкаф", "77/1"), listOf("2", "Тумба", "77/2"), listOf("3", "Сейф", "77/3"),
        ))
        val c = Inventory.SheetChoice("Л", header = 2, body = 3, columns = mapOf("NUMBER" to 0, "NAME" to 1, "INVENTORY" to 2))
        val r = Inventory.read(listOf(s), Inventory.Choices(listOf(Inventory.sealed(s, c))))
        assertEquals(listOf("Шкаф", "Тумба", "Сейф"), r.items.map { it.name })
        assertEquals(listOf("77/1", "77/2", "77/3"), r.items.map { it.inventory })
    }

    @Test fun выбор_к_другому_шаблону_не_применяется_и_об_этом_сказано() {
        val выбор = Inventory.sealed(лист, Inventory.SheetChoice("Опись", columns = mapOf("INVENTORY" to 3)))
        // Исправленный файл того же шаблона — выбор в силе.
        val исправленный = Xlsx.Sheet("Опись", лист.rows + listOf(listOf("5", "Шкаф", "Г-1", "555/5", "1")))
        assertFalse(Inventory.read(listOf(исправленный), Inventory.Choices(listOf(выбор))).sheets[0].stale)
        // Другой шаблон с тем же именем листа — нет.
        val чужой = Xlsx.Sheet("Опись", listOf(listOf("Инв. номер", "Название"), listOf("9/1", "Кресло"), listOf("9/2", "Диван")))
        val r = Inventory.read(listOf(чужой), Inventory.Choices(listOf(выбор)))
        assertTrue(r.sheets[0].stale)
        assertEquals(listOf("9/1", "9/2"), r.items.map { it.inventory })
        assertTrue(Inventory.warnings(r).any { "не подошёл" in it })
    }

    @Test fun выбор_в_файле_рядом_с_описью_меняет_разбор_и_пишется_в_журнал() {
        val obj = Inventory.create("Склад") { it.write(книга(лист)) }
        val f = Inventory.fileIn(obj)!!
        val до = Inventory.parse(f).items.map { it.inventory }
        val sheet = Xlsx.sheets(f.readBytes()).single()
        Inventory.setChoices(obj, Inventory.Choices(listOf(Inventory.sealed(sheet, Inventory.SheetChoice("Опись", columns = mapOf("INVENTORY" to 3))))))
        val после = Inventory.parse(f).items.map { it.inventory }
        assertTrue(до != после)
        assertEquals(listOf("555/1", "555/2", "555/3", "555/4"), после)
        assertTrue(File(obj, Inventory.COLUMNS).isFile)
        assertTrue(Journal.entries(obj).any { it.what == "Столбцы описи" && "Инвентарный номер — D" in it.subject })
        // Сброс — файл уходит, разбор снова подбором.
        Inventory.setChoices(obj, Inventory.Choices())
        assertFalse(File(obj, Inventory.COLUMNS).exists())
        assertEquals(до, Inventory.parse(f).items.map { it.inventory })
        assertTrue(Journal.verify(obj).ok)
    }

    @Test fun плохие_файлы_называются_своими_именами() {
        fun п(b: ByteArray) = Inventory.problemOf(b).orEmpty()
        assertTrue(п(ByteArray(0)).contains("пустой"))
        val ole = ByteArray(2048).also { byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()).copyInto(it) }
        assertTrue(п(ole).contains(".xls"))
        val пароль = ole.copyOf().also { "EncryptionInfo".flatMap { c -> listOf(c.code.toByte(), 0) }.toByteArray().copyInto(it, 600) }
        assertTrue(п(пароль).contains("паролем"))
        assertTrue(п("%PDF-1.7 ...".encodeToByteArray()).contains("PDF"))
        assertTrue(п("<html><body><table><tr><td>1</td></tr></table>".encodeToByteArray()).contains("HTML"))
        assertTrue(п("№;Наименование;Инв\n1;Стол;5/1\n".encodeToByteArray()).contains("CSV"))
        assertTrue(п(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)).contains("картинка"))
        val хорошая = книга(лист)
        assertNull(п(хорошая).ifEmpty { null })
        assertTrue("обрыв посреди архива", п(хорошая.copyOf(хорошая.size / 2)).contains("повреждён"))
        fun zip(vararg names: String) = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> names.forEach { n -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry() } }
        }.toByteArray()
        assertTrue(п(zip("word/document.xml")).contains("Word"))
        assertTrue(п(zip("ppt/presentation.xml")).contains("презентация"))
        assertTrue(п(zip("readme.txt")).contains("архив"))
    }

    @Test fun та_же_опись_второй_раз_не_заводится() {
        val b = книга(лист)
        Inventory.importBook(b, "склад.xlsx")
        val e = runCatching { Inventory.importBook(b, "склад (копия).xlsx") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("уже загружена"))
    }

    @Test fun без_списка_опись_заводится_пустой_а_пустая_книга_нет() {
        val прочее = Xlsx.Sheet("Акт", listOf(listOf("Акт приёма"), listOf("Подписи:"), listOf("Иванов")))
        val p = Inventory.importBook(книга(прочее), "акт.xlsx")
        assertTrue(p.items.isEmpty())
        assertNotNull("папка осталась — человек укажет столбцы", Inventory.fileIn(p.file.parentFile!!))
        val пустая = Xlsx.Sheet("Лист1", listOf(listOf("")))
        val e = runCatching { Inventory.importBook(книга(пустая), "пусто.xlsx") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("пустые"))
    }

    @Test fun замена_не_тем_файлом_видна_заранее_и_откатывается() {
        val obj = Inventory.create("Склад") { it.write(книга(лист)) }
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        Inventory.openFolder(obj, items.first { it.inventory == "А-1" || it.number == "1" })
        val чужая = книга(Xlsx.Sheet("Опись", listOf(listOf("Инв. номер", "Название"), listOf("9/1", "Кресло"), listOf("9/2", "Диван"))))
        assertTrue(Inventory.checkReplace(obj, Inventory.fileIn(obj)!!.readBytes()).same)
        val ch = Inventory.checkReplace(obj, чужая)
        assertFalse(ch.same); assertEquals(2, ch.items); assertEquals(1, ch.bound); assertEquals(1, ch.lost.size)
        Inventory.attachBook(чужая, "чужая.xlsx", obj)
        assertEquals(listOf("9/1", "9/2"), Inventory.parse(Inventory.fileIn(obj)!!).items.map { it.inventory })
        assertTrue(Inventory.hasPrevious(obj))
        Inventory.restorePrevious(obj)
        assertEquals(items.map { it.inventory }, Inventory.parse(Inventory.fileIn(obj)!!).items.map { it.inventory })
        // Битый файл прежнюю опись не трогает.
        val e = runCatching { Inventory.attachBook(чужая.copyOf(чужая.size / 2), "битый.xlsx", obj) }.exceptionOrNull()
        assertNotNull(e)
        assertEquals(items.map { it.inventory }, Inventory.parse(Inventory.fileIn(obj)!!).items.map { it.inventory })
        assertTrue(Journal.verify(obj).ok)
    }

    @Test fun предупреждения_по_предметам() {
        fun item(inv: String, name: String) = Inventory.Item("л", "1", inv, name, null, null, "", "", 1)
        assertTrue(Inventory.itemWarnings(List(10) { item(if (it < 3) "5/$it" else "", "Стол") }).any { "только у 3 из 10" in it })
        assertTrue(Inventory.itemWarnings(List(10) { item("5/$it", "123,45") }).any { "одни числа" in it })
        assertTrue(Inventory.itemWarnings(List(10) { item("5/1", "Стол") }).any { "повторяются" in it })
        assertTrue(Inventory.itemWarnings(List(10) { item("5/$it", "Стол $it") }).isEmpty())
    }
}

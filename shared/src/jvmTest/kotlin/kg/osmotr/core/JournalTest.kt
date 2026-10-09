package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Журнал описи: обычная работа — без ложных тревог; правки мимо приложения (по USB, файловым
 * менеджером) — видны: изменённый, подкинутый, пропавший файл, правленая строка, обрезанный хвост.
 */
class JournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var obj: File
    private lateinit var items: List<Inventory.Item>

    private fun book(dir: File, vararg rows: List<Any?>) = Xlsx.writeBook(File(dir, Inventory.PREFIX + dir.name + ".xlsx"), listOf(Xlsx.Out("Опись",
        listOf(listOf<Any?>("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость")) + rows.toList())))

    @Before fun setUp() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        obj = Store.createFolder(Store.root, "Склад")!!
        book(obj, listOf(1, "Ноутбук", "555/1", 40000), listOf(2, "Ноутбук", "555/2", 40000), listOf(3, "Проектор", "555/3", 25000))
        items = Inventory.parse(Inventory.fileIn(obj)!!).items
    }

    /** Снимок «с камеры»: файл и запись, как делает Store.saved (там — в фоне). */
    private fun shoot(dir: File, name: String, fill: Int = 1): File =
        File(dir, name).apply { writeBytes(ByteArray(200) { (it * fill).toByte() }) }.also { Journal.add(dir, "Снимок", Store.relative(it), it) }
    private fun problems() = Journal.verify(obj).problems.map { it.kind to it.path.substringAfterLast('/') }
    private fun lines() = File(obj, Journal.FILE).readLines().filter { it.isNotBlank() }
    private fun write(l: List<String>) = File(obj, Journal.FILE).writeText(l.joinToString("\n") + "\n")

    @Test fun обычная_работа_без_ложных_тревог() {
        val d1 = Inventory.openFolder(obj, items[0]); shoot(d1, "a.jpg")
        Store.setNote(d1, "треснут корпус")
        Inventory.setBroken(obj, listOf(items[0]), true)
        // Второй ноутбук — в ту же папку (переименуется «+1 шт»), потом обратно.
        val g = Inventory.addTo(d1, listOf(items[1]))
        val back = Inventory.removeFrom(g, items[1])
        // Снимок — в другую папку, удалить, вернуть.
        val d3 = Inventory.openFolder(obj, items[2]); val p = shoot(d3, "b.jpg", 3)
        Store.move(listOf(p), back)
        val t = Store.trash(listOf(File(back, "b.jpg")))!!
        Store.restore(t)
        Store.renameFolder(d3, "Проектор в зале")
        val c = Journal.verify(obj)
        assertTrue("${c.text()} ${c.problems}", c.ok)
        assertTrue(Journal.entries(obj).map { it.what }.containsAll(listOf("Снимок", "Комментарий", "Нерабочее", "Предметы в папке",
            Journal.PHOTO_MOVED, "Снимок удалён", "Возвращено из корзины", Journal.FOLDER_RENAMED)))
    }

    @Test fun правки_мимо_приложения_видны() {
        val d = Inventory.openFolder(obj, items[0]); val a = shoot(d, "a.jpg"); shoot(d, "b.jpg", 2)
        Store.setNote(d, "всё хорошо")
        assertTrue(Journal.verify(obj).ok)
        // Подменили снимок, удалили второй, подкинули третий, поправили комментарий.
        a.writeBytes(ByteArray(200) { 9 })
        File(d, "b.jpg").delete()
        File(d, "c.jpg").writeBytes(ByteArray(50) { 1 })
        File(d, Store.NOTE).writeText("всё плохо\n")
        val p = problems().toSet()
        assertEquals(setOf("изменён" to "a.jpg", "пропал без записи" to "b.jpg", "появился без записи" to "c.jpg", "изменён" to Store.NOTE), p)
        assertTrue(!Journal.verify(obj).ok)
    }

    @Test fun журнал_правка_обрезка_удаление_видны() {
        repeat(4) { Journal.add(obj, "Действие", "№$it") }
        val good = lines()
        write(good.toMutableList().also { it[2] = it[2].replace("№1", "№X") })
        assertEquals("содержимое изменено", Journal.verify(obj).reason)
        // Подделка: отпечаток пересчитан, а подписать нечем.
        val json = kotlinx.serialization.json.Json
        val e = json.decodeFromString(Journal.Entry.serializer(), good[2]).copy(subject = "подделка")
        val forged = e.copy(hash = Journal.hex(Platform.sha256(e.body().encodeToByteArray())))
        write(good.toMutableList().also { it[2] = json.encodeToString(Journal.Entry.serializer(), forged) })
        assertEquals(3, Journal.verify(obj).brokenAt)
        // Хвост отрезали — начало цело, но конец помнит приложение.
        write(good.dropLast(2))
        assertTrue(Journal.verify(obj).reason.startsWith("журнал короче"))
        // Журнал удалили.
        File(obj, Journal.FILE).delete()
        assertTrue(Journal.verify(obj).reason.startsWith("журнал удалён"))
    }

    @Test fun снятое_до_журнала_и_перезапуск() {
        // Снимок лежал до журнала: содержимое не известно, а пропажа — видна.
        val d = Inventory.openFolder(obj, items[0])  // первая запись журнала — «до журнала»
        val old = File(d, "old.jpg").apply { writeBytes(ByteArray(10) { 1 }) }
        val d2 = Store.createFolder(obj, "Новая")!!
        File(obj, Journal.FILE).delete(); Journal.forget()
        File(Store.cacheDir.parentFile!!, "journal-heads.txt").delete()
        Journal.add(d2, "Снимок", "x")  // журнал заводится заново: всё лежащее — «до журнала»
        assertEquals(Journal.BEFORE, Journal.entries(obj).first().what)
        assertTrue(Journal.verify(obj).ok)
        old.delete()
        assertEquals(listOf("пропал без записи" to "old.jpg"), problems())
        // После перезапуска цепочка продолжается.
        Journal.forget(); Journal.add(obj, "После перезапуска")
        assertTrue(Journal.check(Journal.entries(obj)).ok)
    }

    /** Образец для tools/verify_journal.py: папка описи целиком (OSMOTR_JOURNAL_SAMPLE — куда положить). */
    @Test fun образец_для_проверки_на_python() {
        val out = System.getenv("OSMOTR_JOURNAL_SAMPLE") ?: return
        val d = Inventory.openFolder(obj, items[0]); shoot(d, "a.jpg"); Store.setNote(d, "проверка"); Inventory.setBroken(obj, listOf(items[0]), true)
        Store.renameFolder(d, "Ноутбук у окна")
        java.io.File(Store.root.path).copyRecursively(java.io.File(out), overwrite = true)
    }

    @Test fun перенос_папки_в_другую_опись_без_ложных_тревог() {
        val other = Store.createFolder(Store.root, "Цех")!!
        book(other, listOf(1, "Станок", "777/1", 100000))
        val d = Inventory.openFolder(obj, items[0]); shoot(d, "a.jpg")
        Store.moveFolder(d, other)
        assertTrue(Journal.verify(obj).text(), Journal.verify(obj).ok)
        assertTrue(Journal.verify(other).text(), Journal.verify(other).ok)
    }

    @Test fun отчёт_с_журналом_и_сверкой() {
        val d = Inventory.openFolder(obj, items[0]); shoot(d, "a.jpg")
        Inventory.writeReport(obj)
        var book = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes())
        assertEquals(listOf("верна"), book.first { it.name == "Журнал" }.rows.drop(1).map { it.last() }.distinct())
        assertTrue(book.first { it.name == "Итог" }.rows.any { r -> r.any { it.startsWith("Журнал цел") } })
        File(d, "a.jpg").writeBytes(ByteArray(5))
        Journal.forget()
        Inventory.writeReport(obj)
        book = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes())
        assertEquals("изменён", book.first { it.name == "Сверка файлов" }.rows[1][1])

    }
}

package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Опустевшая папка удаляется (автор, 13.10.2026: «Если папка остаётся пустой, её надо удалить.
 * Например, если из неё все комментарии убрали, записи и т. д.»), а голосовые заметки и
 * комментарии описи — в отчёте («Если возможно, встраиваем в Excel. Нет — добавляем столбцом
 * наличие. Комментарии на опись выносим отдельной страницей»).
 */
class EmptyFolderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var obj: File

    @Before fun setUp() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        obj = Store.createFolder(Store.root, "Склад")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "склад.xlsx"), listOf(Xlsx.Out("Опись", listOf(
            listOf("№ п/п", "Основное средство", "Инв.Номер"),
            listOf(1, "Ноутбук", "555/1"), listOf(2, "Проектор", "555/2"),
        ))))
    }

    private fun item(inv: String) = Inventory.parse(Inventory.fileIn(obj)!!).items.first { it.inventory == inv }
    private fun photo(dir: File, name: String) = File(dir, name).also { it.writeBytes(ByteArray(64) { 7 }); Journal.add(dir, "Снимок", Store.relative(it), it) }

    @Test fun убрали_комментарий_и_папка_пуста_её_нет() {
        val d = Inventory.openFolder(obj, item("555/1"))
        Store.setNote(d, "царапина")
        assertTrue(d.isDirectory)
        Store.setNote(d, "")
        assertFalse("осталась одна привязка к предмету — папки нет", d.exists())
        assertTrue(Journal.verify(obj).ok)
    }

    @Test fun убрали_контакты_и_папку_без_привязки_тоже() {
        val d = Store.createFolder(obj, "Подсобка")!!
        Store.setContacts(d, listOf(Store.Contact("Иванов", phone = "555")))
        Store.setContacts(d, emptyList())
        assertFalse(d.exists())
    }

    @Test fun удалили_последнее_и_вернули_папка_возвращается_с_привязкой() {
        val d = Inventory.openFolder(obj, item("555/1"))
        val sub = Store.createFolder(d, "до ремонта")!!
        val p = photo(sub, "a.jpg")
        val voice = File(d, "голос.m4a").also { it.writeBytes(ByteArray(300) { 1 }); Store.voiceSaved(it) }
        val t = Store.trash(listOf(p, voice))!!
        assertFalse("вложенная опустела, за ней и папка предмета", d.exists())
        Store.restore(t)
        assertTrue(File(sub, "a.jpg").isFile)
        assertTrue(File(d, "голос.m4a").isFile)
        assertEquals(listOf("555/1"), Inventory.members(d))
        assertTrue(Inventory.status(obj).photosOf(item("555/1")) > 0)
        assertTrue(Journal.verify(obj).ok)
    }

    @Test fun папка_с_чем_то_остаётся_а_опись_и_корень_никогда() {
        val d = Inventory.openFolder(obj, item("555/1"))
        photo(d, "a.jpg"); val b = photo(d, "b.jpg")
        Store.trash(listOf(b))
        assertTrue(d.isDirectory)
        assertFalse(Store.isEmptyFolder(obj))
        assertFalse(Store.isEmptyFolder(Store.root))
        assertTrue(Store.pruneEmpty(obj).isEmpty() && obj.isDirectory)
    }

    @Test fun перенесли_снимки_и_старая_папка_ушла() {
        val a = Inventory.openFolder(obj, item("555/1"))
        val b = Inventory.openFolder(obj, item("555/2"))
        val p = photo(a, "a.jpg")
        Store.move(listOf(p), b)
        assertFalse(a.exists())
        assertTrue(File(b, "a.jpg").isFile)
        assertTrue(Journal.verify(obj).ok)
    }

    @Test fun голосовые_в_отчёте_столбцом_наличия_а_комментарии_описи_своим_листом() {
        val d = Inventory.openFolder(obj, item("555/1"))
        photo(d, "a.jpg")
        File(d, "голос 1.m4a").also { it.writeBytes(ByteArray(300) { 1 }); Store.voiceSaved(it) }
        Store.setNote(obj, "ключ от склада у охраны")
        File(obj, "общая.m4a").also { it.writeBytes(ByteArray(300) { 2 }); Store.voiceSaved(it) }
        Store.setContacts(obj, listOf(Store.Contact("Петров", phone = "0555 123")))
        Inventory.writeReport(obj)
        val f = Inventory.reportFile(Inventory.fileIn(obj)!!)
        val book = Xlsx.sheets(f.readBytes())
        val list = book.first { it.name == "Опись" }.rows
        val h = list[0]
        assertEquals("Да", list.first { it.getOrNull(h.indexOf("Инв. номер")) == "555/1" }[h.indexOf("Голосовые")])
        assertEquals("Нет", list.first { it.getOrNull(h.indexOf("Инв. номер")) == "555/2" }[h.indexOf("Голосовые")])
        val about = book.first { it.name == "Комментарии к описи" }.rows.map { it.take(2) }
        assertTrue(about.toString(), about.any { it == listOf("Комментарий", "ключ от склада у охраны") })
        assertTrue(about.toString(), about.any { it[0] == "Голосовая заметка" && it[1] == "общая.m4a" })
        assertTrue(about.toString(), about.any { it[0] == "Контакт" && "Петров" in it[1] })
        assertNotNull(Xlsx.sheets(f.readBytes()).firstOrNull())
    }
}

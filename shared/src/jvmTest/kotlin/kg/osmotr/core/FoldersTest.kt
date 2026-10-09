package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Память о папках и отчёт «всегда свежий»: правки не теряются, закрытие приложения — тоже. */
class FoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun objectWithInventory(): File {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val obj = Store.createFolder(Store.root, "Объект")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "опись.xlsx"), listOf(Xlsx.Out("Опись", listOf(
            listOf("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость"),
            listOf(1, "Ноутбук", "555/1", 40000),
            listOf(2, "Ноутбук", "555/2", 40000),
            listOf(3, "Проектор", "555/3", 25000),
        ))))
        return obj
    }
    private fun photo(dir: File, name: String) = File(dir, name).apply { writeBytes(ByteArray(10) { 1 }) }
    /** Папки сняты давно — как на выезде: их сведениям можно верить. */
    private fun age(obj: File) = java.io.File(obj.path).walkTopDown().filter { it.isDirectory }.forEach { it.setLastModified(System.currentTimeMillis() - 60_000) }

    @Test fun правки_после_запомненного_видны() {
        val obj = objectWithInventory()
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        val d1 = Inventory.openFolder(obj, items[0]); photo(d1, "a.jpg")
        age(obj)
        assertEquals(1, Inventory.status(obj).photosOf(items[0]))  // запомнили
        // Снимок, второй предмет в ту же папку, новая папка — всё видно сразу.
        photo(d1, "b.jpg")
        val group = Inventory.addTo(d1, listOf(items[1]))
        val d3 = Inventory.openFolder(obj, items[2]); photo(d3, "c.jpg")
        val st = Inventory.status(obj)
        assertEquals(2, st.photosOf(items[0])); assertEquals(group, st.folderOf(items[1])); assertEquals(1, st.photosOf(items[2]))
        // Снимок удалили.
        File(group, "a.jpg").delete()
        assertEquals(1, Inventory.status(obj).photosOf(items[0]))
    }

    @Test fun после_перезапуска_сведения_с_диска_те_же() {
        val obj = objectWithInventory()
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        photo(Inventory.openFolder(obj, items[0]), "a.jpg"); photo(Inventory.openFolder(obj, items[2]), "c.jpg")
        age(obj)
        val before = Inventory.status(obj)
        Thread.sleep(1500)  // запись на диск — в фоне через секунду
        assertTrue(File(Store.cacheDir, "folders-v1.json").isFile)
        Folders.forget()
        val after = Inventory.status(obj)
        assertEquals(before.folder, after.folder); assertEquals(before.photos, after.photos)
    }

    @Test fun отчёт_несобранный_до_закрытия_собирается_при_запуске() {
        val obj = objectWithInventory()
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        val report = Inventory.reportFile(Inventory.fileIn(obj)!!)
        // «Закрыли» до сборки: правка была, отчёта нет, метка «устарел» — на диске.
        Inventory.REPORT_QUIET_MS = 60_000
        photo(Inventory.openFolder(obj, items[0]), "a.jpg")
        Inventory.refreshReportSoon(obj)
        assertTrue(!report.exists())
        assertEquals(1, File(Store.cacheDir, "stale-reports").listFiles()!!.size)
        // Запуск: метка подхвачена, отчёт собран, метка снята.
        Inventory.REPORT_QUIET_MS = 0
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app"))
        Inventory.resumeReportsNow()
        assertTrue(report.isFile)
        val done = Xlsx.sheets(report.readBytes()).first { it.name == "Опись" }.rows.first { it.getOrNull(1) == "555/1" }
        assertEquals("Да", done[Xlsx.sheets(report.readBytes()).first { it.name == "Опись" }.rows[0].indexOf("Осмотрено")])
        assertEquals(0, File(Store.cacheDir, "stale-reports").listFiles()?.size ?: 0)
    }
}

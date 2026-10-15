package kg.osmotr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Нагрузка (15.10.2026, автор: «тесты гонял уже? На огромных объёмах и нагрузках»): опись на
 * 10 000 строк, 3 000 папок предметов по 5 снимков, отметки по тысяче предметов, отчёт, поиск,
 * «Положить» сотню снимков по 4 МБ. Пороги — с запасом на медленную машину сборки; числа —
 * в выводе, чтобы видеть, сколько что стоит.
 */
class LoadTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun ms(block: () -> Unit) = System.nanoTime().let { t -> block(); (System.nanoTime() - t) / 1_000_000 }

    private fun bigObject(rows: Int): File {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val obj = Store.createFolder(Store.root, "Крупный объект")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "опись.xlsx"), listOf(Xlsx.Out("Опись",
            listOf(listOf<Any?>("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость", "Приоритет")) +
                (1..rows).map { listOf<Any?>(it, "Насос центробежный ЦН-${it % 97} цех ${it % 13}", "ИН-${100000 + it}", 1000.0 + it, 1 + it % 5) })))
        return obj
    }

    @Test fun опись_на_10_тысяч_строк_3_тысячи_папок_отметки_и_отчёт() {
        val obj = bigObject(10_000)
        val book = Inventory.fileIn(obj)!!
        var p: Inventory.Parsed? = null
        val parse = ms { p = Inventory.parse(book) }
        assertEquals(10_000, p!!.items.size)
        val items = p!!.items
        val shot = ByteArray(2_000) { 7 }
        val st0 = Inventory.status(obj)
        val folders = ms {
            items.take(3_000).forEach { i -> val d = Inventory.openFolder(obj, i, st0); repeat(5) { n -> File(d, "s$n.jpg").writeBytes(shot) } }
        }
        Folders.forget()
        var st: Inventory.Status? = null
        val status = ms { st = Inventory.status(obj) }
        assertEquals(3_000, items.count { st!!.photosOf(it) == 5 })
        // Отметки: тысяча «ремонт», тысяча «нерабочее», затем пятьсот из «ремонта» — в «нерабочее».
        val marks = ms {
            assertEquals(1_000, Inventory.setRepair(obj, items.subList(0, 1_000), true))
            assertEquals(1_000, Inventory.setBroken(obj, items.subList(1_000, 2_000), true))
            assertEquals(500, Inventory.setBroken(obj, items.subList(0, 500), true))
        }
        st = Inventory.status(obj)
        assertEquals(500, items.count(st!!::repair))
        assertEquals(1_500, items.count(st!!::broken))
        assertTrue(items.none { st!!.repair(it) && st!!.broken(it) })
        val report = ms { Inventory.writeReport(obj) }
        val sheets = Xlsx.sheets(Inventory.reportFile(book).readBytes())
        val sum = sheets.first { it.name == "Итог" }.rows
        assertEquals("1500", sum[1][sum[0].indexOf("Нерабочих")])
        assertEquals("500", sum[1][sum[0].indexOf("Требуют ремонта")])
        assertEquals("3000", sum[1][sum[0].indexOf("Осмотрено")])
        var hits = 0
        val search = ms { repeat(5) { hits = items.count { Search.scoreItem(it, "ИН-105432") > 0 } } } / 5
        assertTrue(hits >= 1)
        println("10 000 строк: разбор $parse мс; 3 000 папок × 5 снимков $folders мс; учёт $status мс; " +
            "отметки 2 500 $marks мс; отчёт $report мс (${Inventory.reportFile(book).length() / 1024} КБ); поиск $search мс")
        assertTrue("разбор $parse мс", parse < 15_000)
        assertTrue("учёт $status мс", status < 10_000)
        assertTrue("отметки $marks мс", marks < 10_000)
        assertTrue("отчёт $report мс", report < 30_000)
        assertTrue("поиск $search мс", search < 2_000)
    }

    /** Только отчёт — для профиля (`OSMOTR_PROFILE=1`): 10 000 строк, 3 000 папок. */
    @Test fun отчёт_большой_описи() {
        org.junit.Assume.assumeTrue(System.getenv("OSMOTR_PROFILE") == "1")
        val obj = bigObject(10_000)
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        val st0 = Inventory.status(obj)
        items.take(3_000).forEach { i -> val d = Inventory.openFolder(obj, i, st0); repeat(5) { n -> File(d, "s$n.jpg").writeBytes(ByteArray(100)) } }
        Inventory.setRepair(obj, items.subList(0, 500), true)
        repeat(3) { println("отчёт: ${ms { Inventory.writeReport(obj) }} мс") }
    }

    @Test fun папка_предмета_не_дорожает_с_числом_папок() {
        val obj = bigObject(4_000)
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        // Как на выезде: перед каждой папкой — свежий учёт (экран описи), затем папка и снимок.
        val times = items.take(3_000).map { i -> ms { val d = Inventory.openFolder(obj, i); File(d, "a.jpg").writeBytes(ByteArray(10)) } }
        val head = times.take(300).average(); val tail = times.takeLast(300).average()
        println("папка предмета: первые 300 — ${"%.1f".format(head)} мс, последние 300 — ${"%.1f".format(tail)} мс")
        assertTrue("дорожает: $head → $tail мс", tail < head * 4 + 5)
    }

    @Test fun запомненная_папка_предмета_не_дает_двойников() {
        val obj = bigObject(50)
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        val a = Inventory.openFolder(obj, items[0])
        assertEquals("та же папка", a, Inventory.openFolder(obj, items[0]))
        File(a, "s.jpg").writeBytes(ByteArray(10))
        Store.saved(File(a, "s.jpg"))
        assertEquals("снимок структуры не меняет — та же папка", a, Inventory.openFolder(obj, items[0]))
        // Перенесли папку предмета внутрь другой — находится на новом месте, не заводится вторая.
        val shelf = Store.createFolder(obj, "Стеллаж 1")!!
        val moved = Store.moveFolder(a, shelf)!!
        assertEquals(moved, Inventory.openFolder(obj, items[0]))
        // Папку предмета завели с компьютера, пока приложение было свёрнуто; вернулись — находится.
        Inventory.status(obj)
        val outside = File(obj, Inventory.folderName(items[1])).apply { mkdirs() }
        Store.changed()  // возврат в приложение (OsmotrApp, ON_RESUME)
        assertEquals(outside, Inventory.openFolder(obj, items[1]))
        assertEquals(1, java.io.File(obj.path).walkTopDown().count { it.isDirectory && it.name.contains(items[1].inventory) })
    }

    @Test fun положить_сотню_снимков_по_4_мб_из_файлов() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val dir = Store.createFolder(Store.root, "Из галереи")!!
        val photo = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val incoming = File(tmp.root.path + "/incoming").apply { mkdirs() }
        val files = (1..100).map { n -> File(incoming, "in-$n.part").apply { writeBytes(photo) } }
        val rt = Runtime.getRuntime(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        var out: List<File> = emptyList()
        val t = ms { out = Store.addPhotoFiles(dir, files.mapIndexed { i, f -> Triple("IMG-$i.jpg", f, null) }) }
        System.gc()
        val grew = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024
        assertEquals(100, out.size)
        assertEquals(100, Store.photosIn(dir).size)
        assertTrue(out.all { java.io.File(it.path).length() == photo.size.toLong() })
        assertTrue("временные убраны", files.none { it.exists() })
        println("100 × 4 МБ из файлов: $t мс, рост кучи ~$grew МБ")
        assertTrue("память росла на $grew МБ", grew < 100)
    }

    @Test fun журнал_после_перезапуска_не_проверяет_старые_подписи_но_ловит_подмену() {
        val obj = bigObject(3_000)
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        items.take(2_000).forEach { Inventory.openFolder(obj, it) }   // 2 000 записей журнала
        val full = ms { assertTrue(Journal.verifyQuick(obj).brokenAt == null) }
        Journal.forget()   // как после перезапуска приложения
        var c: Journal.Check? = null
        val again = ms { c = Journal.verifyQuick(obj) }
        assertTrue(c!!.brokenAt == null)
        println("журнал 2 000 записей: первая проверка $full мс, после перезапуска $again мс")
        assertTrue("после перезапуска $again мс против $full мс", again * 3 < full)
        // Подменили текст старой записи (номер и отпечаток прежние) — поймано.
        val f = File(obj, Journal.FILE)
        val lines = f.readLines().toMutableList()
        lines[10] = lines[10].replace("Предметы в папке", "Предметы в папкЕ")
        f.writeText(lines.joinToString("\n") + "\n")
        Journal.forget()
        assertEquals(11, Journal.verifyQuick(obj).brokenAt)
    }

    @Test fun положить_сотню_снимков_по_4_мб() {
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val dir = Store.createFolder(Store.root, "Присланное")!!
        val photo = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val items = (1..100).map { Triple("IMG-WA$it.jpg", photo, null as Long?) }
        val rt = Runtime.getRuntime(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        var out: List<File> = emptyList()
        val t = ms { out = Store.addPhotos(dir, items) }
        val grew = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024
        assertEquals(100, out.size)
        assertEquals(100, Store.photosIn(dir).size)
        assertTrue(out.all { java.io.File(it.path).length() == photo.size.toLong() })
        println("100 × 4 МБ: $t мс, рост кучи при записи ~$grew МБ (сами снимки в памяти — 400 МБ ещё до записи)")
    }
}

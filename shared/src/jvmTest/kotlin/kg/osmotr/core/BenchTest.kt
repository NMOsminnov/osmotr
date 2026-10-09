package kg.osmotr.core

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Замер на выезде крупного объекта: опись [OSMOTR_BENCH] (5–6 тыс. строк), 600 папок предметов
 * по 5 снимков, комментарии. Не тест — линейка: что сколько стоит до и после переделки памяти.
 */
class BenchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun выезд_крупного_объекта() {
        val src = java.io.File(System.getenv("OSMOTR_BENCH") ?: "")
        assumeTrue(src.isFile)
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val obj = Store.createFolder(Store.root, "Объект")!!
        val book = File(obj, Inventory.PREFIX + "объект.xlsx"); book.writeBytes(src.readBytes())
        fun ms(block: () -> Unit) = System.nanoTime().let { t -> block(); (System.nanoTime() - t) / 1_000_000 }
        var p: Inventory.Parsed? = null
        println("разбор: ${ms { p = Inventory.parse(book) }} мс; из кэша: ${ms { Inventory.parse(book) }} мс; предметов ${p!!.items.size}")
        val photo = ByteArray(200_000) { 1 }
        val st0 = Inventory.status(obj)
        println("папки 600 × 5 снимков: ${ms {
            p!!.items.take(600).forEach { i -> val d = Inventory.openFolder(obj, i, st0); repeat(5) { n -> File(d, "s$n.jpg").writeBytes(photo) }; Store.setNote(d, "заметка") }
        }} мс")
        println("учёт снимков, всё свежее (кэшу не верим): ${ms { Inventory.status(obj) }} мс")
        // На выезде папки сняты минуты назад: состарить время папок, как на деле.
        val past = System.currentTimeMillis() - 60_000
        java.io.File(obj.path).walkTopDown().filter { it.isDirectory }.forEach { it.setLastModified(past) }
        println("учёт снимков, первый проход: ${ms { Inventory.status(obj) }} мс")
        repeat(2) { println("учёт снимков, повторно: ${ms { Inventory.status(obj) }} мс") }
        val one = Inventory.status(obj).folderOf(p!!.items[10])!!
        File(one, "new.jpg").writeBytes(photo)
        println("учёт после одного нового снимка: ${ms { Inventory.status(obj) }} мс; снимков в папке ${Inventory.status(obj).photosOf(p!!.items[10])}")
        Folders.forget()
        println("учёт после перезапуска (сведения с диска): ${ms { Inventory.status(obj) }} мс")
        repeat(2) { println("отчёт: ${ms { Inventory.writeReport(obj) }} мс, ${Inventory.reportFile(book).length() / 1024} КБ") }
        for (q in listOf("2545", "ноутбук", "ноутбк асер", "013/2545")) {
            var hits = 0
            val t = ms { repeat(5) { hits = p!!.items.count { Search.scoreItem(it, q) > 0 } } } / 5
            println("поиск «$q»: $t мс, найдено $hits")
        }
        Thread.sleep(1500)
        println("экран описи (разбор из кэша + учёт): ${ms { Inventory.parse(book); Inventory.status(obj) }} мс")
    }
}

package kg.osmotr.core

import kg.osmotr.ui.Host
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Shot
import kg.osmotr.ui.zipFolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InventoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val host = object : Host {
        override fun takePhotos(dir: File) {}
        override suspend fun afterResume(): Shot? = null
        override fun canWrite() = true
        override fun requestStorage() {}
        override fun pickBooks(multiple: Boolean, got: (List<Picked>) -> Unit) {}
        override fun share(files: List<File>, mime: String, title: String) {}
        override fun toast(text: String, long: Boolean) {}
        override val pickPhone: ((got: (String, String) -> Unit) -> Unit)? = null
        override val cacheDir: File get() = File(tmp.root.path + "/.cache")
    }
    private fun root() = File(tmp.root.path)
    private fun sheet(name: String, vararg rows: List<String>) = Xlsx.Sheet(name, rows.toList())

    @Test fun распечатанный_шаблон_с_номером_пп() {
        // Как на листе, с которым ходили: шапка не в первой строке, «№ п/п», «Первонач. Стоимость», «Приоритет».
        val s = sheet("Лист1",
            listOf("Объект", "", "", "", "", "", ""),
            listOf("№ п/п", "Основное средство", "Количество", "Инв.Номер", "Первонач. Стоимость", "Приоритет", "Комментарии"),
            listOf("1", "Генератор дизельный ДГ-15", "1", "К-310", "1 044 321,55", "1", ""),
            listOf("2", "Компрессор передвижной", "1", "К-620", "52 310 400,10", "1", ""),
            listOf("3", "Видеокамера Panasonic HC-V10", "1", "М 205", "1 299 017,38", "", ""),
            listOf("", "Итого", "", "", "53 653 739,03", "", ""),
        )
        val t = Inventory.detect(s)!!
        assertEquals(1, t.headerRow)
        assertEquals(0, t.columns[Inventory.Field.NUMBER]); assertEquals(1, t.columns[Inventory.Field.NAME])
        assertEquals(3, t.columns[Inventory.Field.INVENTORY]); assertEquals(4, t.columns[Inventory.Field.INITIAL])
        assertEquals(5, t.columns[Inventory.Field.PRIORITY])
        val items = Inventory.items(s, t)
        assertEquals("итоговая строка пропущена", 3, items.size)
        assertEquals(52310400.10, items[1].initial!!, 0.001)
        assertEquals("М 205", items[2].inventory)
        // Суммы в этом шаблоне нет — не спрашиваем: необязательное просто пусто.
        assertTrue(t.disputed.isEmpty())
    }

    @Test fun лист_без_списка_пропускается() {
        assertNull(Inventory.detect(sheet("Правила", listOf("Поле", "Как заполнено"), listOf("Код учёта", "из источника"))))
    }

    @Test fun поиск_уравнивает_похожие_латинские_и_русские_буквы() {
        // Инвентарник набран латиницей «M 205» и «p5732», ищут по-русски — и наоборот.
        assertEquals(Search.compact("М-205"), Search.compact("M 205"))
        assertEquals(Search.compact("р5732"), Search.compact("p5732"))
        assertEquals(Search.compact("рртц5160"), Search.compact("PPTЦ 5160"))
    }

    /** Настоящая оборотка — только у автора на диске (в ней данные организации), в репозиторий не кладётся. */
    @Test fun настоящая_оборотка() {
        val f = File(System.getenv("OSMOTR_SAMPLE_XLSX") ?: "")
        assumeTrue(f.isFile)
        Store.init(root(), File(tmp.root.path + "/.app"))
        val sheets = Xlsx.sheets(f.readBytes())
        val tables = sheets.mapNotNull { s -> Inventory.detect(s)?.let { s to it } }
        tables.forEach { (s, t) ->
            println("лист «${s.name}»: строка заголовков ${t.headerRow + 1}, " +
                t.columns.entries.joinToString { "${it.key.title}=«${t.headers.getOrNull(it.value)}»" } +
                "; спорно: ${t.disputed.map { it.title }}; кандидаты суммы: ${t.candidates[Inventory.Field.SUM]?.map { t.headers.getOrNull(it) }}")
            val items = Inventory.items(s, t)
            println("  предметов ${items.size}; пример: ${items.take(2)}")
        }
        assertEquals(listOf("Опись", "Вынесено"), tables.map { it.first.name })
        // Скорость на 5,7 тыс. строк: первый разбор, из кэша, учёт снимков, отчёт.
        Store.ensureRoot()
        val obj = Store.createFolder(Store.root, "Объект")!!
        val copy = File(obj, Inventory.PREFIX + "оборотка.xlsx"); f.copyTo(copy)
        fun ms(block: () -> Unit) = System.nanoTime().let { t -> block(); (System.nanoTime() - t) / 1_000_000 }
        var parsed: Inventory.Parsed? = null
        println("разбор: ${ms { parsed = Inventory.parse(copy) }} мс; из кэша: ${ms { Inventory.parse(copy) }} мс; предметов ${parsed!!.items.size}")
        println("учёт снимков: ${ms { Inventory.status(obj) }} мс; отчёт: ${ms { Inventory.writeReport(obj) }} мс, ${Inventory.reportFile(copy).length() / 1024} КБ")
    }

    private fun objectWithInventory(): File {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val obj = Store.createFolder(Store.root, "Объект")!!
        Xlsx.writeBook(File(obj, Inventory.PREFIX + "оборотка.xlsx"), listOf(
            Xlsx.Out("Опись", listOf(
                listOf("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость", "Балансовая стоимость", "Приоритет"),
                listOf(1, "Серверный юнит Dell R640", "777/1001", 450000, 300000, 1),
                listOf(2, "Серверный юнит Dell R640", "777/1002", 450000, 300000, 1),
                listOf(3, "Серверный юнит Dell R640", "777/1003", 450000, 300000, 1),
                listOf(4, "Сканер штрих-кода", "М-205", 6900, 6210, 5),
            )),
            Xlsx.Out("Вынесено", listOf(
                listOf("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость", "Балансовая стоимость", "Приоритет"),
                listOf(1, "Автомобиль ГАЗель", "777/1011", 21222, 0, 4),
            )),
        ))
        return obj
    }
    private fun photo(dir: File, name: String) = File(dir, name).apply { writeBytes(ByteArray(10) { 1 }) }

    @Test fun папка_предмета_называется_инвентарником_и_отмечается_по_снимкам() {
        val obj = objectWithInventory()
        val p = Inventory.parse(Inventory.filesIn(obj).single())
        assertEquals(listOf("Опись", "Вынесено"), p.lists)
        val scanner = p.items.first { it.inventory == "М-205" }
        val dir = Inventory.openFolder(obj, scanner)
        assertEquals("4. М-205", dir.name)
        assertEquals(listOf("М-205"), Inventory.members(dir))
        assertEquals("та же папка второй раз", dir, Inventory.openFolder(obj, scanner))
        assertEquals(false, Inventory.status(obj).inspected(scanner))
        photo(dir, "a.jpg")
        val st = Inventory.status(obj)
        assertTrue(st.inspected(scanner)); assertEquals(1, st.photosOf(scanner))
        // Набрали «M 205» латиницей — та же папка.
        assertEquals(dir, st.folder[Search.compact("M 205")])
        assertEquals(listOf(scanner), Inventory.itemsIn(dir))
    }

    @Test fun похожие_в_одну_папку_со_снимками_и_подсказка_такие_же() {
        val obj = objectWithInventory()
        val units = Inventory.parse(Inventory.filesIn(obj).single()).items.filter { it.name.startsWith("Серверный") }
        val first = Inventory.openFolder(obj, units[0]); photo(first, "u1.jpg")
        val second = Inventory.openFolder(obj, units[1]); photo(second, "u2.jpg")
        // В папке первого — подсказка: ещё два таких же в описи, не осмотрен из них один (третий).
        assertEquals(listOf("777/1003"), Inventory.similar(obj, listOf(units[0]), Inventory.status(obj)).map { it.inventory })
        val group = Inventory.group(obj, units)
        assertEquals("1. 777∕1001 +2 шт", group.name)
        assertEquals(listOf("777/1001", "777/1002", "777/1003"), Inventory.members(group))
        assertEquals("снимки второго переехали", 2, Store.photoTree(group).size)
        assertTrue("пустая папка второго убрана", !second.exists())
        val st = Inventory.status(obj)
        assertTrue(units.all { st.inspected(it) })
        // Ошиблись — убрать один из папки.
        val back = Inventory.removeFrom(group, units[2])
        assertEquals("1. 777∕1001 +1 шт", back.name)
    }

    @Test fun отчёт_осмотрено_не_осмотрено_в_excel() {
        val obj = objectWithInventory()
        val p = Inventory.parse(Inventory.filesIn(obj).single())
        photo(Inventory.openFolder(obj, p.items.first { it.inventory == "М-205" }), "a.jpg")
        Inventory.writeReport(obj)
        val report = Inventory.reportFile(Inventory.filesIn(obj).single())
        assertTrue(report.isFile)
        val sheets = Xlsx.sheets(report.readBytes())
        assertEquals(listOf("Итог", "Опись", "Вынесено", "По дням"), sheets.map { it.name })
        val summary = sheets[0].rows
        assertEquals(listOf("Опись", "4", "1", "3"), summary[1].take(4))
        // По приоритетам: П1 — три юнита, ни один не осмотрен; П4 — машина; П5 — сканер, осмотрен.
        assertEquals(listOf("П1", "3", "0"), summary.first { it.firstOrNull() == "П1" }.take(3))
        assertEquals(listOf("П5", "1", "1"), summary.first { it.firstOrNull() == "П5" }.take(3))
        val head = sheets[1].rows[0]
        val row = sheets[1].rows.first { it.getOrNull(1) == "М-205" }
        assertEquals("Да", row[head.indexOf("Осмотрено")]); assertEquals("1", row[head.indexOf("Снимков")]); assertEquals("Объект/4. М-205", row[head.indexOf("Папка")])
        assertEquals("Нет", sheets[1].rows.first { it.getOrNull(1) == "777/1001" }[head.indexOf("Осмотрено")])
        // Отчёт — не опись: в описях объекта его нет.
        assertEquals(1, Inventory.filesIn(obj).size)
    }

    private fun book(file: File, vararg invs: String) = Xlsx.writeBook(file, listOf(Xlsx.Out("Опись",
        listOf(listOf("№ п/п", "Основное средство", "Инв.Номер", "Первонач. Стоимость", "Приоритет")) +
            invs.mapIndexed { n, inv -> listOf(n + 1, "Предмет $inv", inv, 100, 1) })))

    @Test fun новая_опись_своя_папка_замена_оставляет_папки_предметов() {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val src = File(tmp.newFile("x.xlsx").path).also { book(it, "А-1", "А-2") }
        val dir = Inventory.create("Склад") { it.write(src.readBytes()) }
        assertEquals(File(Store.root, "Склад"), dir)
        assertEquals("Склад", Inventory.nameOf(Inventory.fileIn(dir)!!))
        assertEquals("имя занято — с номером", "Склад (2)", Inventory.create("Склад") { it.write(src.readBytes()) }.name)
        val item = Inventory.openFolder(dir, Inventory.parse(Inventory.fileIn(dir)!!).items.first()); photo(item, "a.jpg")
        book(src, "А-1", "А-2", "А-3")
        Inventory.replace(dir) { it.write(src.readBytes()) }
        assertEquals("одна опись на папку", 1, Inventory.filesIn(dir).size)
        val items = Inventory.parse(Inventory.fileIn(dir)!!).items
        assertEquals(3, items.size)
        assertTrue("папка предмета осталась и засчитана", Inventory.status(dir).inspected(items.first()))
    }

    @Test fun перенос_описей_из_кучи_по_своим_папкам() {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val heap = Store.createFolder(Store.root, "Объект")!!
        // Остаётся та, с чьими предметами работали (а не первая по алфавиту).
        book(File(heap, Inventory.PREFIX + "Яблоко.xlsx"), "А-1", "А-2")
        book(File(heap, Inventory.PREFIX + "Бета.xlsx"), "Б-1", "Б-2")
        book(File(Store.root, Inventory.PREFIX + "Корень.xlsx"), "В-1")
        val a = Store.createFolder(heap, "А-1")!!; photo(a, "a.jpg")
        photo(Store.createFolder(heap, "А-2")!!, "a2.jpg")
        val b = Store.createFolder(heap, "Б-1")!!; photo(b, "b.jpg")
        val c = Store.createFolder(Store.root, "В-1")!!; photo(c, "c.jpg")
        Inventory.migrate()
        assertEquals(listOf("Яблоко"), Inventory.filesIn(heap).map(Inventory::nameOf))
        assertTrue("предмет первой остался", a.isDirectory)
        val second = File(Store.root, "Бета")
        assertEquals(1, Inventory.filesIn(second).size)
        assertTrue("папка предмета второй переехала с ней", File(second, "Б-1/b.jpg").isFile && !b.exists())
        val root = File(Store.root, "Корень")
        assertTrue("из корня опись уехала в свою папку", Inventory.filesIn(Store.root).isEmpty() && File(root, "В-1/c.jpg").isFile)
        Inventory.migrate()  // второй раз — ничего
        assertEquals(1, Inventory.filesIn(second).size)
    }

    @Test fun имя_папки_номер_и_инвентарник_или_название() {
        fun item(n: String, inv: String, name: String) = Inventory.Item("Опись", n, inv, name, null, null, "", "", 1)
        assertEquals("13. 1562∕65", Inventory.folderName(item("13", "1562/65", "Монитор")))
        assertEquals("без инвентарника — просто номер", "13", Inventory.folderName(item("13", "", "ИВЛ")))
        assertEquals("13. 1562∕65 +19 шт", Inventory.folderName(item("13", "1562/65", "Монитор"), 20))
        assertEquals("13 +1 шт", Inventory.folderName(item("13", "", "ИВЛ"), 2))
    }

    @Test fun прежние_папки_переименовываются_по_новому_правилу() {
        val obj = objectWithInventory()
        val units = Inventory.parse(Inventory.fileIn(obj)!!).items.filter { it.name.startsWith("Серверный") }
        val group = Inventory.group(obj, units)
        val old = File(obj, "777 1001 (3 шт)"); assertTrue(group.renameTo(old))  // как называли прежние версии
        photo(old, "x.jpg")
        Inventory.migrate()
        val now = File(obj, "1. 777∕1001 +2 шт")
        assertTrue("переименована", now.isDirectory && !old.exists())
        assertTrue("снимки с ней", File(now, "x.jpg").isFile)
        assertTrue(Inventory.status(obj).inspected(units[2]))
    }

    @Test fun отчёт_комментарий_по_дням_нет_в_описи_и_выгрузка_нового() {
        val obj = objectWithInventory()
        val p = Inventory.parse(Inventory.fileIn(obj)!!)
        val scanner = Inventory.openFolder(obj, p.items.first { it.inventory == "М-205" })
        java.io.File(photo(scanner, "a.jpg").path).setLastModified(System.currentTimeMillis() - 2 * 86_400_000L)
        Store.setNote(scanner, "треснут корпус")
        val extra = Store.createFolder(obj, "Принтер без бирки")!!; photo(extra, "b.jpg")
        Inventory.writeReport(obj)
        val book = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes())
        assertEquals(listOf("Итог", "Опись", "Вынесено", "По дням", "Нет в описи"), book.map { it.name })
        val list = book.first { it.name == "Опись" }.rows
        val head = list[0]; val row = list.first { it.getOrNull(1) == "М-205" }
        assertEquals("треснут корпус", row[head.indexOf("Комментарий")])
        assertEquals("Да", row[head.indexOf("Осмотрено")])
        assertTrue(row[head.indexOf("Первый снимок")].isNotEmpty())
        assertEquals("Принтер без бирки", book.first { it.name == "Нет в описи" }.rows[1][0])
        assertEquals(1, book.first { it.name == "По дням" }.rows.size - 1)
        // Выгрузка: прошлая — сутки назад; новее неё — один снимок (принтер), сегодня — тоже он.
        Inventory.markExport(obj, System.currentTimeMillis() - 86_400_000L)
        assertEquals(1, Inventory.photosSince(obj, Inventory.lastExport(obj)!!))
        assertEquals(2, Inventory.photosSince(obj, 0))
        assertTrue(Inventory.status(obj).let { st -> !st.today(p.items.first { it.inventory == "М-205" }) })
    }

    @Test fun нерабочее_отмечается_только_после_снимков_и_уходит_в_отчёт() {
        val obj = objectWithInventory()
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        val units = items.filter { it.name.startsWith("Серверный") }.take(2)
        val car = items.first { it.inventory == "777/1011" }
        // Без снимков — не отмечается: сперва снимают, потом помечают (автор 09.10.2026).
        assertEquals(0, Inventory.setBroken(obj, units + car, true))
        assertTrue(!File(obj, Inventory.BROKEN).exists())
        units.forEach { photo(Inventory.openFolder(obj, it), "a.jpg") }
        val carDir = Inventory.openFolder(obj, car); val carPhoto = photo(carDir, "c.jpg")
        assertEquals(3, Inventory.setBroken(obj, units + car, true))
        var st = Inventory.status(obj)
        assertTrue((units + car).all(st::broken))
        assertEquals(listOf("777/1001", "777/1002", "777/1011"), File(obj, Inventory.BROKEN).readLines())
        // Снимок машины удалили — отметка не действует (строка в файле остаётся до снятия отметки).
        carPhoto.delete()
        assertTrue(!Inventory.status(obj).broken(car))
        // Снять отметку; повторная отметка не задваивает строку.
        Inventory.setBroken(obj, listOf(car, units[0]), false)
        Inventory.setBroken(obj, listOf(units[0]), true)
        assertEquals(listOf("777/1002", "777/1001"), File(obj, Inventory.BROKEN).readLines())
        st = Inventory.status(obj)
        // Набрали «777 1002» — тот же предмет (ключ без разделителей).
        assertTrue(Search.compact("777 1002") in st.brokenKeys)

        Inventory.writeReport(obj)
        val book = Xlsx.sheets(Inventory.reportFile(Inventory.fileIn(obj)!!).readBytes())
        val list = book.first { it.name == "Опись" }.rows
        val head = list[0]
        assertEquals("Нерабочее", list.first { it.getOrNull(1) == "777/1001" }[head.indexOf("Состояние")])
        assertEquals("", list.first { it.getOrNull(1) == "777/1003" }.getOrElse(head.indexOf("Состояние")) { "" })
        val summary = book.first { it.name == "Итог" }.rows
        assertEquals("2", summary[1][summary[0].indexOf("Нерабочих")])
        // Все сняли — файла нет.
        Inventory.setBroken(obj, units, false)
        assertTrue(!File(obj, Inventory.BROKEN).exists())
    }

    @Test fun имя_описи_из_файла_или_из_книги() {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val f = File(tmp.newFile("b.xlsx").path)
        Xlsx.writeBook(f, listOf(Xlsx.Out("Сводка", listOf(listOf("Склад Центральный"), listOf("Источник: 1С")))))
        assertEquals("Оборотка Объект 2026", Inventory.nameFor("Оборотка Объект 2026.xlsx", f))
        assertEquals("мусорное имя из мессенджера — заголовок книги", "Склад Центральный", Inventory.nameFor("040dc55f-115.4.-____-___2026________.xlsx", f))
    }

    @Test fun несколько_разборов_разом_не_мешают_друг_другу() {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val books = (1..3).map { n -> File(Store.createFolder(Store.root, "Опись $n")!!, Inventory.PREFIX + "$n.xlsx").also { book(it, *Array(300) { k -> "$n/${1000 + k}" }) } }
        // Каждый файл — дважды одновременно: разбор один, второй берёт готовое.
        val results = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>()
        val threads = (books + books).map { f -> Thread { val p = Inventory.parse(f); results.getOrPut(f.name) { java.util.Collections.synchronizedList(mutableListOf()) } += p.items.size } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(books.associate { it.name to listOf(300, 300) }, results.mapValues { it.value.toList() })
        assertTrue("шкалы погашены", Inventory.parsing.value.isEmpty())
    }
}

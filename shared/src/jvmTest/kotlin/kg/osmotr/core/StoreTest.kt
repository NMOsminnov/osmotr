package kg.osmotr.core

import kg.osmotr.ui.Host
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Shot
import kg.osmotr.ui.zipFolder

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class StoreTest {
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

    @Before fun setUp() {
        Store.init(root(), File(tmp.root.path + "/.app"))
        Store.ensureRoot()
    }

    private fun photo(dir: File, name: String) = File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(10) { 1 }) }

    @Test fun папки_вкладываются_и_имя_чистится() {
        val obj = Store.createFolder(Store.root, "  ТЭЦ-2 / корпус  ")!!
        assertEquals("ТЭЦ-2 корпус", obj.name)
        val node = Store.createFolder(obj, "Насос 1")!!
        assertEquals("ТЭЦ-2 корпус › Насос 1", Store.title(node))
        assertNull("занятое имя", Store.createFolder(obj, "Насос 1"))
        assertNull("пустое имя", Store.createFolder(obj, " ? "))
    }

    @Test fun имена_снимков_по_времени_съёмки_не_повторяются() {
        val dir = Store.createFolder(Store.root, "Узел")!!
        val a = Store.newPhotoFile(dir, "heic", 0L).apply { writeBytes(ByteArray(4) { 1 }) }
        val b = Store.newPhotoFile(dir, "heic", 0L)
        assertTrue(a.name.startsWith("Узел_") && a.name.endsWith("_001.heic"))
        assertTrue(b.name.endsWith("_002.heic"))
        assertTrue("HEIC штатной камеры — тоже снимок", Store.isPhoto(a))
    }

    @Test fun голосовые_заметки_в_папке_новые_первыми_пустая_не_считается() {
        val dir = Store.createFolder(Store.root, "Насосная")!!
        val a = File(dir, "Насосная_голос_20261009_100000.m4a").apply { writeBytes(ByteArray(10) { 1 }) }
        val b = File(dir, "Насосная_голос_20261009_120000.m4a").apply { writeBytes(ByteArray(10) { 1 }) }
        File(dir, "Насосная_голос_20261009_130000.m4a").writeBytes(ByteArray(0))  // запись не удалась
        assertEquals(listOf(b, a), Store.voiceNotes(dir))
        val next = Store.newVoiceFile(dir)
        assertTrue(next.name.startsWith("Насосная_голос_") && next.name.endsWith(".m4a"))
        // Не снимок — сетка и учёт «осмотрено» её не видят.
        assertTrue(!Store.isPhoto(a))
    }

    @Test fun естественный_порядок() {
        assertEquals(listOf("Насос 2", "Насос 10", "насос 11"), listOf("Насос 10", "насос 11", "Насос 2").sortedWith(Store.NATURAL))
    }

    @Test fun список_папки_считает_вложенное() {
        val obj = Store.createFolder(Store.root, "Объект")!!
        val n = Store.createFolder(obj, "Насос 1")!!
        photo(n, "a.jpg"); photo(File(n, "Подшипник"), "b.jpg"); photo(obj, "c.jpg")
        val l = Store.list(obj)
        assertEquals(1, l.folders.size)
        assertEquals(2, l.folders[0].photos)
        assertEquals(1, l.folders[0].folders)
        assertEquals(listOf("c.jpg"), l.photos.map { it.name })
    }

    @Test fun перенос_не_затирает_одноимённые() {
        val a = Store.createFolder(Store.root, "A")!!; val b = Store.createFolder(Store.root, "B")!!
        val p = photo(a, "x.jpg"); photo(b, "x.jpg")
        Store.move(listOf(p), b)
        assertEquals(listOf("x_2.jpg", "x.jpg"), Store.photosIn(b).map { it.name })
        assertTrue(Store.photosIn(a).isEmpty())
    }

    @Test fun архив_хранит_вложенность() = runBlocking {
        val obj = Store.createFolder(Store.root, "Объект")!!
        photo(File(obj, "Насос 1"), "a.jpg"); photo(obj, "b.jpg")
        val zip = zipFolder(host, obj) {}
        assertEquals("проверка готового архива: снимков", 2, zip.photos)
        val names = ZipFile(java.io.File(zip.file.path)).use { z -> z.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("Объект/b.jpg", "Объект/Насос 1/a.jpg"), names)
    }

    @Test fun удалённое_по_ошибке_возвращается() {
        val obj = Store.createFolder(Store.root, "Объект")!!
        val p = photo(obj, "a.jpg"); photo(File(obj, "Насос"), "b.jpg")
        val t = Store.trash(listOf(obj))!!
        assertEquals(2, t.photos); assertEquals(1, t.folders)
        assertFalse(obj.exists())
        Store.restore(t)
        assertTrue(p.exists()); assertTrue(File(obj, "Насос/b.jpg").exists())
        val one = Store.trash(listOf(p))!!
        assertFalse(p.exists()); Store.restore(one); assertTrue(p.exists())
    }

    @Test fun переименование_папки() {
        val obj = Store.createFolder(Store.root, "Объект")!!
        photo(obj, "a.jpg")
        val renamed = Store.renameFolder(obj, "Объект 2")!!
        assertTrue(File(renamed, "a.jpg").exists())
    }

    @Test fun комментарий_лежит_в_папке_и_уходит_в_архив() = runBlocking {
        val obj = Store.createFolder(Store.root, "Объект")!!
        photo(obj, "a.jpg")
        Store.setNote(obj, "  течь по сальнику  ")
        assertEquals("течь по сальнику", Store.note(obj))
        assertEquals("течь по сальнику", Store.list(Store.root).folders.single().note)
        assertTrue("комментарий — не снимок", Store.photosIn(obj).none { it.name == Store.NOTE })
        val names = ZipFile(java.io.File(zipFolder(host, obj) {}.file.path)).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("Объект/${Store.NOTE}"))
        Store.setNote(obj, " ")
        assertNull(Store.note(obj))
    }

    @Test fun папку_нельзя_перенести_в_саму_себя_и_во_вложенную() {
        val a = Store.createFolder(Store.root, "A")!!
        val inner = Store.createFolder(a, "B")!!
        assertNull(Store.moveFolder(a, a)); assertNull(Store.moveFolder(a, inner))
        val c = Store.createFolder(Store.root, "C")!!
        Store.createFolder(c, "B")
        val moved = Store.moveFolder(inner, c)!!
        assertEquals("B (2)", moved.name)
    }

    @Test fun поиск_по_приоритету_номерам_и_комментариям() {
        val obj = Store.createFolder(Store.root, "ТЭЦ-2")!!
        val n12 = Store.createFolder(obj, "Насос 12")!!
        Store.createFolder(obj, "Насос 120")
        Store.createFolder(obj, "Насосная станция")
        val shield = Store.createFolder(Store.root, "Щит управления")!!
        Store.setNote(shield, "Инв. № ИН-00123\nтечь по сальнику")
        val i = Search.index()
        fun names(q: String) = Search.find(i, q, Store.root).map { it.entry.name }

        // Число целиком — первым; часть инв. номера в комментарии — тоже находится, но последней.
        assertEquals("число целиком — раньше похожего", listOf("Насос 12", "Насос 120", "Щит управления"), names("12"))
        assertEquals("начало названия раньше вхождения", "Насос 12", names("насос").first())
        assertEquals("инв. номер в комментарии, без разделителей", listOf("Щит управления"), names("ин00123"))
        assertEquals(listOf("Щит управления"), names("00123"))
        assertEquals("все слова запроса; точное — первым", listOf("Насос 12", "Насос 120"), names("насос 12"))
        assertEquals("ё = е, регистр не важен", listOf("ТЭЦ-2"), names("тэц2"))
        assertEquals("строка комментария — для подсказки", "Инв. № ИН-00123", Search.find(i, "00123", Store.root).single().noteLine)
        assertTrue(names("нет такого").isEmpty())
        // Внутри текущей папки — выше при равном совпадении.
        val other = Store.createFolder(Store.root, "Насос 7")!!
        assertEquals(n12, Search.find(Search.index(), "насос", obj).first().entry.dir)
        assertTrue(other.exists())
    }

    @Test fun контакты_книгой_excel_в_папке() = runBlocking {
        val obj = Store.createFolder(Store.root, "Объект")!!
        val people = listOf(
            Store.Contact("Иванов Иван Иванович", "Главный энергетик", "+996 555 12-34-56", "ivanov@example.kg", "звонить после 14:00"),
            Store.Contact(name = "Петров П.", phone = "0700 123 456"),
        )
        Store.setContacts(obj, people)
        val f = File(obj, Store.CONTACTS)
        assertTrue(f.isFile)
        assertEquals(people, Store.contacts(obj))
        // Заголовки в книге — для человека; телефон — текстом, как ввели.
        val rows = Xlsx.read(f)
        assertEquals(Store.CONTACT_COLUMNS, rows.first())
        assertEquals("+996 555 12-34-56", rows[1][2])
        // Поиск находит папку по человеку и по телефону без разделителей.
        assertEquals(listOf("Объект"), Search.find(Search.index(), "петров", Store.root).map { it.entry.name })
        assertEquals(listOf("Объект"), Search.find(Search.index(), "555123456", Store.root).map { it.entry.name })
        // В архив — вместе со снимками.
        photo(obj, "a.jpg")
        val names = ZipFile(java.io.File(zipFolder(host, obj) {}.file.path)).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("Объект/${Store.CONTACTS}"))
        Store.setContacts(obj, emptyList())
        assertFalse("пустой список — файла нет", f.exists())
    }

    @Test fun книга_после_правки_в_excel_читается() {
        // Excel пишет строки в общий словарь (sharedStrings), а телефон, набранный числом, — числом.
        val f = File(root(), "excel.xlsx")
        java.util.zip.ZipOutputStream(java.io.File(f.path).outputStream()).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            put("xl/sharedStrings.xml", """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>ФИО</t></si><si><t>Сидоров</t></si></sst>""")
            put("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                <row r="2"><c r="A2" t="s"><v>1</v></c><c r="C2"><v>996555123456</v></c></row></sheetData></worksheet>""")
        }
        assertEquals(listOf(listOf("ФИО"), listOf("Сидоров", "", "996555123456")), Xlsx.read(f))
    }

    @Test fun одновременная_запись_не_теряет_файл() {
        Store.init(root(), File(tmp.root.path + "/.app")); Store.ensureRoot()
        val f = File(Store.root, "Осмотр — x.xlsx")
        val threads = (1..4).map { t -> Thread { repeat(40) { Store.writeDurably(f) { out -> repeat(2000) { out.writeByte(t) } } } } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertTrue("файл на месте", f.isFile); assertEquals(2000L, f.length())
        assertTrue("временных не осталось", Store.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}

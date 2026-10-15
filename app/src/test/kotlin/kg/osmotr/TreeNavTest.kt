package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Journal
import kg.osmotr.core.Platform
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kg.osmotr.ui.Host
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Screen
import kg.osmotr.ui.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Дерево папок (отзыв и автор, 15.10.2026): «назад» — туда, откуда пришли, путь помнится;
 * в дереве — поиск и «+» (новая папка).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class TreeNavTest {
    @get:Rule val compose = createComposeRule()
    private var backs: androidx.activity.OnBackPressedDispatcher? = null
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
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { 7 }

    private fun setUp(): File {
        Platform.context = ApplicationProvider.getApplicationContext()
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val book = File(tmp.root.path + "/b.xlsx")
        Xlsx.writeBook(book, listOf(Xlsx.Out("Опись", listOf(listOf("№ п/п", "Наименование", "Инв. номер"),
            listOf(1, "Шкаф металлический", "МТ-101"), listOf(2, "Стол письменный", "МТ-102")))))
        val bytes = book.readBytes()
        val obj = Inventory.create("Склад") { it.write(bytes) }
        Store.createFolder(obj, "Стеллаж А")!!.let { Store.createFolder(it, "Полка 3") }
        return obj
    }

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
    }
    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true), true).fetchSemanticsNodes().isNotEmpty()
    private fun back() = compose.runOnUiThread { backs!!.onBackPressed() }
    private fun content(screens: List<Screen>) = compose.setContent {
        backs = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        OsmotrApp(host, mutableStateListOf(), screens)
    }

    @Test fun из_дерева_в_папку_и_назад_в_дерево_с_ней() {
        setUp()
        content(listOf(Screen.Tree(Store.root)))
        waitFor { has("Полка 3") }
        compose.onAllNodes(hasText("Полка 3")).onFirst().performClick()
        waitFor { !has("Найти папку") }
        assertTrue("в папке", !has("Найти папку"))
        back()
        waitFor { has("Найти папку") }
        assertTrue("снова дерево", has("Найти папку") && has("Полка 3"))
        back()
        waitFor { !has("Найти папку") }
        assertTrue("главный", !has("Найти папку"))
    }

    @Test fun поиск_в_дереве() {
        setUp()
        content(listOf(Screen.Tree(Store.root)))
        waitFor { has("Стеллаж А") }
        compose.onAllNodes(hasTestTag("поиск в дереве")).onFirst().performTextInput("полка")
        waitFor { !has("Стеллаж А") || has("Склад › Стеллаж А") }
        assertTrue("найдена с путём", has("Полка 3") && has("Склад › Стеллаж А"))
    }

    @Test fun из_раскладки_дерево_назад_к_поиску() {
        setUp()
        val photos = listOf(Picked("IMG-1.jpg", jpeg), Picked("IMG-2.jpg", jpeg))
        content(listOf(Screen.Search(Store.root, place = photos)))
        waitFor { has("Дерево папок") }
        compose.onAllNodes(hasText("Дерево папок")).onFirst().performClick()
        waitFor { has("Найти папку") }
        assertTrue("подсказка про «+»", has("новая папка внутри"))
        back()
        waitFor { has("Дерево папок") }
        assertTrue("назад — к поиску «Куда положить»", has("Дерево папок") && !has("Найти папку"))
    }

    /** Содержимое окна «куда / новая папка» — без всплывающего окна (см. PlaceFolderChooser). */
    @Test fun выбор_папки_по_инвентарнику_и_по_названию() {
        val obj = setUp()
        var got: Pair<String, suspend () -> File?>? = null
        compose.setContent { kg.osmotr.ui.PlaceFolderChooser(obj, 2) { where, resolve -> got = where to resolve } }
        waitFor { has("Сюда") }
        compose.onAllNodes(hasTestTag("новая папка поле")).onFirst().performTextInput("102")
        waitFor { has("МТ-102 — Стол письменный") }
        compose.onAllNodes(hasTestTag("папка предмета")).onFirst().performClick()
        assertTrue(got!!.first.contains("предмет МТ-102"))
        val item = kotlinx.coroutines.runBlocking { got!!.second() }!!
        assertEquals(listOf("МТ-102"), Inventory.members(item))
        compose.onAllNodes(hasTestTag("новая папка поле")).onFirst().performTextReplacement("Сейф 7")
        waitFor { has("Новая папка «Сейф 7»") }
        compose.onAllNodes(hasTestTag("новая папка по названию")).onFirst().performClick()
        val made = kotlinx.coroutines.runBlocking { got!!.second() }!!
        assertEquals(File(obj, "Сейф 7").path, made.path)
        assertTrue(made.isDirectory)
    }

    /** «Осмотрено» — и у снятого, не только у отмеченного вручную (отзыв 15.10.2026). */
    @Test fun метка_осмотрено_у_снятого() {
        val obj = setUp()
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        File(Inventory.openFolder(obj, items[0]), "a.jpg").writeBytes(jpeg)
        Store.changed()
        content(listOf(Screen.Inventory(obj)))
        waitFor { has("МТ-101") }
        waitFor { compose.onAllNodes(hasTestTag("метка осмотрено"), true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("одна метка — у снятого шкафа", 1, compose.onAllNodes(hasTestTag("метка осмотрено"), true).fetchSemanticsNodes().size)
    }
}

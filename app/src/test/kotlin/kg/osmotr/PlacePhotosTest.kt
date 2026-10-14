package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * Присланные фото («Поделиться» из WhatsApp) — найти, куда положить: предмет описи по инвентарнику
 * (папка заводится сама) или новая папка в описи, если инвентарника в ней нет (автор, 14.10.2026:
 * «При добавлении нужен поиск по папкам, инвентарникам… Ибо это может быть и новый инвентарник»).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class PlacePhotosTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val toasts = mutableListOf<String>()
    private val host = object : Host {
        override fun takePhotos(dir: File) {}
        override suspend fun afterResume(): Shot? = null
        override fun canWrite() = true
        override fun requestStorage() {}
        override fun pickBooks(multiple: Boolean, got: (List<Picked>) -> Unit) {}
        override fun share(files: List<File>, mime: String, title: String) {}
        override fun toast(text: String, long: Boolean) { toasts += text }
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
        return Inventory.create("Склад") { it.write(bytes) }
    }

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
    }

    @Test fun по_инвентарнику_в_папку_предмета() {
        val obj = setUp()
        val photos = listOf(Picked("IMG-WA0001.jpg", jpeg), Picked("IMG-WA0002.jpg", jpeg))
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Search(Store.root, place = photos))) }
        compose.onAllNodes(hasText("Куда положить", substring = true)).onFirst().assertExists()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("102")
        waitFor { compose.onAllNodes(hasText("Стол письменный", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Стол письменный", substring = true), true).onFirst().performClick()
        // Сначала — куда кладём, полным путём; папка предмета ещё не заведена.
        compose.onAllNodes(hasText("Склад › предмет МТ-102", substring = true)).onFirst().assertExists()
        assertTrue(Store.foldersIn(obj).isEmpty())
        compose.onAllNodes(hasText("Положить")).onFirst().performClick()
        val item = Inventory.parse(Inventory.fileIn(obj)!!).items.first { it.inventory == "МТ-102" }
        waitFor { Inventory.status(obj).photosOf(item) == 2 }
        assertEquals(2, Inventory.status(obj).photosOf(item))
        compose.onAllNodes(hasText("2 фото → ", substring = true)).onFirst().assertExists()   // плашка с путём
    }

    @Test fun нового_инвентарника_нет_новая_папка_в_описи() {
        val obj = setUp()
        val photos = listOf(Picked("IMG-WA0003.jpg", jpeg))
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Search(Store.root, place = photos))) }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("МТ-999 Сейф")
        waitFor { compose.onAllNodes(hasText("в описи «Склад»", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("в описи «Склад»", substring = true), true).onFirst().performClick()
        compose.onAllNodes(hasText("МТ-999 Сейф (новая папка)", substring = true)).onFirst().assertExists()
        compose.onAllNodes(hasText("Положить")).onFirst().performClick()
        val dir = File(obj, "МТ-999 Сейф")
        waitFor { Store.photosIn(dir).size == 1 }
        assertEquals(1, Store.photosIn(dir).size)
    }
}

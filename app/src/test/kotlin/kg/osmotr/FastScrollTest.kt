package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ползунок быстрой прокрутки в описи (15.10.2026: «выбираем опись из списка и внутри неё
 * прокрутка»): пролистнули — показался, протащили вниз — внизу описи.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class FastScrollTest {
    @get:Rule val compose = createComposeRule()
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

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!what() && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
    }

    @Test fun протащить_ползунок_вниз_описи() {
        Platform.context = ApplicationProvider.getApplicationContext()
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val book = File(tmp.root.path + "/b.xlsx")
        Xlsx.writeBook(book, listOf(Xlsx.Out("Опись", listOf(listOf("№ п/п", "Наименование", "Инв. номер")) +
            (1..200).map { listOf(it, "Предмет $it", "ИН-${1000 + it}") })))
        val bytes = book.readBytes()
        val obj = Inventory.create("Склад") { it.write(bytes) }
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Inventory(obj))) }
        waitFor { compose.onAllNodes(hasText("ИН-1001", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        // Пролистнули пальцем — ползунок показался.
        compose.onAllNodes(hasText("ИН-1003", substring = true), true).onFirst().performTouchInput { swipeUp() }
        waitFor { compose.onAllNodes(hasTestTag("ползунок списка")).fetchSemanticsNodes().isNotEmpty() }
        val bar = compose.onAllNodes(hasTestTag("ползунок списка")).onFirst()
        // Взять ползунок (он у верха — список в начале) и протащить к низу.
        bar.performTouchInput {
            down(Offset(width - 20f, 60f))
            repeat(40) { moveBy(Offset(0f, height / 38f)) }
            up()
        }
        waitFor { compose.onAllNodes(hasText("ИН-1200", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("внизу описи", compose.onAllNodes(hasText("ИН-1200", substring = true), true).fetchSemanticsNodes().isNotEmpty())
    }
}

package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kg.osmotr.core.File
import kg.osmotr.core.Journal
import kg.osmotr.core.Platform
import kg.osmotr.core.Store
import kg.osmotr.ui.Host
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Screen
import kg.osmotr.ui.Shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Удалили фото — с экрана оно уходит сразу, не дожидаясь корзины и пересчёта папки; «Вернуть» —
 * сразу обратно (автор, 14.10.2026: «При удалении фото не сразу обновляются миниатюры»).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class DeletePhotosTest {
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

    private fun cells() = compose.onAllNodes(hasTestTag("снимок"), true).fetchSemanticsNodes().size

    @Test fun удалённое_уходит_сразу_и_возвращается_сразу() {
        Platform.context = ApplicationProvider.getApplicationContext()
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val dir = Store.createFolder(Store.root, "Насосная")!!
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { 7 }
        Store.addPhotos(dir, List(3) { Triple("p$it.jpg", jpeg, null) })
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Browser(dir))) }
        val end = System.currentTimeMillis() + 10_000
        while (cells() < 3 && System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(50) }
        assertEquals(3, cells())
        compose.onAllNodes(hasTestTag("снимок"), true).onFirst().performTouchInput { longClick() }
        compose.onAllNodes(hasContentDescription("Удалить")).onFirst().performClick()
        compose.waitForIdle()   // один проход композиции — без ожидания корзины и пересчёта папки
        assertEquals("удалённое ушло сразу", 2, cells())
        val back = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasText("Вернуть")).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < back) { compose.waitForIdle(); Thread.sleep(50) }
        compose.onAllNodes(hasText("Вернуть")).onFirst().performClick()
        compose.waitForIdle()
        assertEquals("«Вернуть» — сразу", 3, cells())
    }
}

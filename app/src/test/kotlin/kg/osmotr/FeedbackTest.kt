package kg.osmotr

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Journal
import kg.osmotr.core.Platform
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kg.osmotr.ui.Banners
import kg.osmotr.ui.DialogBody
import kg.osmotr.ui.Host
import kg.osmotr.ui.LatestBanner
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Screen
import kg.osmotr.ui.Shot
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Автотесты на каждое замечание отзыва 15.10.2026 (снимки переписки, голосовые) — то, что
 * проверяется без телефона. Без телефона не проверить: удаление записей галереи (MediaStore).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class FeedbackTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

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

    private fun obj(): File {
        Platform.context = context
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
    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true), true).fetchSemanticsNodes().isNotEmpty()

    // ---------- Камера ----------

    /** «Сфотала несколько фоток, потом вышла, и он мне не добавил»: прошлый заход не стирает новый. */
    @Test fun камера_прошлый_заход_не_стирает_новый() {
        obj()
        SystemCamera.cancel(context)
        SystemCamera.begin(context, File(tmp.root.path + "/a"))
        val first = SystemCamera.session(context)!!
        Thread.sleep(5)
        SystemCamera.begin(context, File(tmp.root.path + "/b"))
        val second = SystemCamera.session(context)!!
        SystemCamera.end(context, first)
        assertEquals("новый заход на месте", second, SystemCamera.session(context))
        SystemCamera.end(context, second)
        assertNull(SystemCamera.session(context))
    }

    /** Два сборщика (прошлый ждёт кадров, новый вернулся) — по очереди, не разом: иначе дубли и обрезки. */
    @Test fun камера_сборщики_по_одному() = runBlocking {
        obj()
        SystemCamera.begin(context, File(tmp.root.path + "/a"))
        val now = AtomicInteger(0); val most = AtomicInteger(0)
        val importer: (android.content.Context, SystemCamera.Session) -> Int = { _, _ ->
            most.accumulateAndGet(now.incrementAndGet(), ::maxOf); Thread.sleep(40); now.decrementAndGet(); 0
        }
        val a = async { SystemCamera.collect(context, settleMs = 300, importer = importer) }
        val b = async { SystemCamera.collect(context, settleMs = 300, importer = importer) }
        a.await(); b.await()
        assertEquals("одновременно работал один", 1, most.get())
    }

    /** Копия (переименовать нельзя): целая, без временного остатка, источник убран. */
    @Test fun камера_копия_целиком() {
        val from = java.io.File(tmp.root, "DCIM/a.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(300_000) { (it % 7).toByte() }) }
        val to = java.io.File(tmp.root, "Осмотры/x.jpg").apply { parentFile!!.mkdirs() }
        assertTrue(SystemCamera.copyWhole(from, to))
        assertEquals(300_000L, to.length())
        assertTrue("источник убран", !from.exists())
        assertTrue("без .part", to.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    // ---------- Присланное ----------

    /** Фото — во временный файл (сотня по 4 МБ в памяти роняла бы приложение), опись — в память. */
    @Test fun присланное_фото_во_временный_файл() {
        val photo = java.io.File(tmp.root, "IMG-WA0001.jpg").apply { writeBytes(jpeg) }
        val incoming = java.io.File(tmp.root, "incoming")
        val p = readPicked(context.contentResolver, Uri.fromFile(photo), incoming)!!
        assertNotNull("в файле", p.file)
        assertTrue(p.isPhoto)
        assertTrue(p.bytes.contentEquals(jpeg))
        val book = java.io.File(tmp.root, "Опись.xlsx").apply { writeBytes(ByteArray(100) { 1 }) }
        val b = readPicked(context.contentResolver, Uri.fromFile(book), incoming)!!
        assertNull("опись — в памяти", b.file)
    }

    /** «Положить» — папка открывается сразу, снимки ложатся один раз («дублируются, типа дважды»). */
    @Test fun положить_сразу_в_папку_без_дублей() {
        val o = obj()
        val photos = listOf(Picked("IMG-1.jpg", jpeg), Picked("IMG-2.jpg", jpeg))
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Search(Store.root, place = photos))) }
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput("102")
        waitFor { compose.onAllNodes(hasText("Стол письменный", substring = true), true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Стол письменный", substring = true), true).onFirst().performClick()
        compose.onAllNodes(hasTestTag("положить")).onFirst().performClick()
        // Папка предмета заводится в фоне (миллисекунды) — и сразу открывается; снимки пишутся следом.
        val t0 = System.currentTimeMillis()
        while (has("Куда положить") && System.currentTimeMillis() - t0 < 2_000) { compose.waitForIdle(); Thread.sleep(20) }
        assertTrue("поиска уже нет — открыта папка", !has("Куда положить"))
        val item = Inventory.parse(Inventory.fileIn(o)!!).items.first { it.inventory == "МТ-102" }
        waitFor { Inventory.status(o).photosOf(item) == 2 }
        Thread.sleep(500); compose.waitForIdle()
        assertEquals("ровно два", 2, Inventory.status(o).photosOf(item))
    }

    // ---------- Плашки ----------

    /** Снимали 1–5 быстро — плашка про пятый, а не про второй, и очереди за ней нет. */
    @Test fun плашка_только_про_последний() {
        val state = SnackbarHostState()
        val banner = LatestBanner()
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) { Banners(state, Modifier.align(Alignment.BottomCenter)) }
        }
        compose.runOnIdle { (1..5).forEach { banner.show(scope, state, "Пункт $it: +2 снимка", "Открыть") } }
        compose.waitForIdle()
        assertTrue(has("Пункт 5"))
        assertTrue(!has("Пункт 2"))
        compose.runOnIdle { state.currentSnackbarData?.dismiss() }
        compose.waitForIdle()
        assertNull("за ней никого", state.currentSnackbarData)
    }

    /** «Чтобы его можно было смахнуть». */
    @Test fun плашка_смахивается() {
        val state = SnackbarHostState()
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) { Banners(state, Modifier.align(Alignment.BottomCenter)) }
        }
        compose.runOnIdle { LatestBanner().show(scope, state, "Пункт 1: +2 снимка", "Открыть") }
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("плашка")).onFirst().performTouchInput { swipeRight() }
        waitFor { state.currentSnackbarData == null }
        assertNull(state.currentSnackbarData)
    }

    // ---------- Ремонт ----------

    /** Кнопка в папке предмета ставит «требуется ремонт», в описи — метка «ремонт». */
    @Test fun кнопка_ремонт_в_папке_предмета() {
        val o = obj()
        val item = Inventory.parse(Inventory.fileIn(o)!!).items.first()
        val dir = Inventory.openFolder(o, item)
        File(dir, "a.jpg").writeBytes(jpeg); Store.changed()
        compose.setContent { OsmotrApp(host, mutableStateListOf(), listOf(Screen.Browser(dir))) }
        waitFor { compose.onAllNodes(hasTestTag("чип ремонт"), true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("чип ремонт"), true).onFirst().performClick()
        waitFor { Inventory.status(o).repair(item) }
        assertTrue(Inventory.status(o).repair(item))
        assertTrue("кнопка «нерабочее» спрятана", compose.onAllNodes(hasTestTag("чип нерабочее"), true).fetchSemanticsNodes().isEmpty())
    }

    // ---------- Прокрутка окон ----------

    /** Длинное окно листается до конца (DialogBody), а не обрезается. */
    @Test fun длинное_окно_листается() {
        compose.setContent {
            AlertDialog({}, confirmButton = { TextButton({}) { Text("ок") } },
                text = { DialogBody { (1..80).forEach { Text("строка $it") } } })
        }
        compose.onAllNodes(hasScrollAction(), true).onFirst().performScrollToNode(hasText("строка 80"))
        compose.onAllNodes(hasText("строка 80"), true).onFirst().assertIsDisplayed()
    }
}

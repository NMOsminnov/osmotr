package kg.osmotr.motion

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasSetTextAction
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
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Матрица движения «Осмотра» — перенос матрицы приложения APCS (запись графа
 * tooling-native-motion-matrix; автор, 13.10.2026: «Применяем пути, матрицы по всему мобильному
 * приложению. Практики APCS в помощь»). Настоящие экраны на настоящих файлах описи, часы Compose
 * вручную: каждое действие — три повтора, каждый помеченный блок — каждый кадр (16 мс).
 * Флаги — РЫВОК, ПЕРЕЛЁТ, ДРОЖЬ, ЗАМЕНЁН, МЕЛЬКНУЛ, РАЗНОБОЙ (MotionMatrix.kt). Задуманное
 * снимается в [Act.expected] с причиной; всё прочее роняет прогон.
 *
 * Запуск: ./gradlew :app:motionMatrix — отчёт app/build/reports/motion-matrix/report.md.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class OsmotrMotionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val frameMs = 16L
    private val blocks = listOf(
        "шапка", "строка описи", "метка осмотрено", "метка нерабочее", "карточка предмета", "чип осмотрено", "чип нерабочее",
        "таблица", "панель", "подпись столбца", "проверьте столбцы", "сводка описи", "плитка", "комментарий",
    )

    /** Действие: подготовка (без замера), само действие, события по кадрам, возврат как было. */
    private class Act(
        val name: String,
        val act: ComposeContentTestRule.() -> Unit,
        val undo: ComposeContentTestRule.() -> Unit = {},
        val prepare: ComposeContentTestRule.() -> Unit = {},
        val during: Map<Int, ComposeContentTestRule.() -> Unit> = emptyMap(),
        val recordMs: Long = 1200,
        val repeats: Int = 3,
        /** Задуманные флаги: «блок флаг» или «блок·n флаг». */
        val expected: Set<String> = emptySet(),
        /**
         * Спокойное действие: содержимое то же (обновились данные, вернулись на экран) — ничто не
         * должно двигаться, проявляться или пропадать после первых двух кадров (автор, 14.10.2026:
         * «Каждое обновление экрана вызывает анимацию появления»). Нарушение — флаг ЛИШНЕЕ;
         * обычные флаги здесь не к месту (мгновенное появление при переходе — как задумано).
         */
        val calm: Boolean = false,
    )

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

    /** Опись стенда: 12 шкафов с инвентарниками и ценой, рядом — лист «Сводка». [bad] — инвентарник только у трёх. */
    private fun inventory(name: String, bad: Boolean = false): File {
        Platform.context = ApplicationProvider.getApplicationContext()
        Store.init(File(tmp.root.path), File(tmp.root.path + "/.app")); Store.ensureRoot()
        Journal.forget()
        val book = File(tmp.root.path + "/$name.xlsx")
        Xlsx.writeBook(book, listOf(
            Xlsx.Out("Сводка", listOf(listOf("Объект", name), listOf("Предметов", 12))),
            Xlsx.Out("Опись", listOf(listOf("№ п/п", "Наименование", "Инв. номер", "Стоимость")) +
                (1..12).map { i -> listOf(i, "Шкаф металлический $i", if (bad && i > 3) "" else "МТ-${100 + i}", 1000 * i) }),
        ))
        val bytes = book.readBytes()
        return Inventory.create(name) { it.write(bytes) }
    }

    private fun show(vararg start: Screen) {
        compose.setContent { OsmotrApp(host, mutableStateListOf(), start.toList()) }
        compose.waitForIdle()
        settle()
    }

    /** Дождаться фоновых чтений (описи читаются на потоке IO): часы идут, пока не станет тихо. */
    private fun settle(ms: Long = 1500) {
        compose.mainClock.autoAdvance = true
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(30) }
    }

    private fun density() = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().layoutInfo.density.density

    private fun run(a: Act): ActionResult {
        val rec = Recorder(compose, blocks, density())
        val takes = (1..a.repeats).map {
            a.prepare(compose); settle(800)
            compose.mainClock.autoAdvance = false
            val take = Take(frameMs)
            rec.sample(take)
            a.act(compose)
            compose.waitForIdle()
            for (f in 1..(a.recordMs / frameMs).toInt()) {
                a.during[f]?.invoke(compose)
                Thread.sleep(3)  // фоновым записям (поток IO) — время, как у телефона между кадрами
                compose.mainClock.advanceTimeBy(frameMs)
                rec.sample(take)
            }
            compose.mainClock.autoAdvance = true
            a.undo(compose); settle(800)
            if (a.calm) calm(take) else Analysis.analyze(take, a.expected)
        }
        return ActionResult(a.name, takes, Analysis.disagreement(takes)).also { results += it }
    }

    /** ЛИШНЕЕ: после второго кадра блок возник, пропал, проявлялся или сдвинулся. */
    private fun calm(take: Take): TakeResult {
        val n = take.frames.size
        val found = mutableListOf<Finding>()
        for ((tag, list) in Analysis.tracks(take)) list.forEachIndexed { k, t ->
            val why = mutableListOf<String>()
            if (t.first > 2) why += "возник на кадре ${t.first}"
            // Пропал в первых кадрах — уходящий экран при переходе (так и задумано); позже — лишнее.
            if (t.last < n - 1 && t.last >= 2) why += "пропал на кадре ${t.last + 1}"
            val alpha = t.values.firstOrNull()?.get(Prop.A.ordinal)
            if (alpha != null && !alpha.isNaN() && alpha < 0.9f) why += "проявлялся (видимость ${Analysis.fmt(alpha)})"
            val settled = t.values.drop(maxOf(0, 3 - t.first))
            for (p in Prop.entries) {
                val v = settled.map { it[p.ordinal] }.filter { !it.isNaN() }
                if (v.isNotEmpty() && v.max() - v.min() > p.eps) why += "${p.title} ${Analysis.fmt(v.min())}…${Analysis.fmt(v.max())}"
            }
            if (why.isNotEmpty()) found += Finding(tag, k + 1, null, "ЛИШНЕЕ", why.joinToString("; "))
        }
        return TakeResult(found, emptyMap(), n, take.alphaKnown)
    }

    private fun ComposeContentTestRule.tapText(t: String) = onAllNodes(hasText(t), useUnmergedTree = true).onFirst().performClick()
    private fun ComposeContentTestRule.tapDesc(d: String) = onAllNodes(hasContentDescription(d)).onFirst().performClick()
    private fun ComposeContentTestRule.longPress(t: String) = onAllNodes(hasText(t), useUnmergedTree = true).onFirst().performTouchInput { longClick() }

    @Test fun опись() {
        val obj = inventory("Склад")
        show(Screen.Inventory(obj))
        run(Act("опись: выделить долгим нажатием",
            act = { onAllNodes(hasText("Шкаф металлический 1"), useUnmergedTree = true).onFirst().performTouchInput { down(center) } },
            during = mapOf(45 to { onAllNodes(hasText("Шкаф металлический 1"), useUnmergedTree = true).onFirst().performTouchInput { up() } }),
            undo = { tapDesc("Снять выбор") }, recordMs = 1400))
        run(Act("опись: данные обновились, содержимое то же", act = { Store.changed() }, calm = true, recordMs = 1500))
        run(Act("опись: вернулись из папки предмета",
            prepare = { onAllNodes(hasText("Шкаф металлический 4"), useUnmergedTree = true).onFirst().performClick() },
            act = { onAllNodes(hasContentDescription("Назад")).onFirst().performClick() }, calm = true, recordMs = 1500))
        run(Act("опись: «Осмотрено (1)» — метка у предмета",
            prepare = { longPress("Шкаф металлический 2") },
            act = { tapText("Осмотрено (1)") },
            undo = { longPress("Шкаф металлический 2"); settle(600); tapText("Снять (1)") }))
        run(Act("опись: поиск сужает список",
            act = { onAllNodes(hasSetTextAction()).onFirst().performTextInput("105") },
            undo = { onAllNodes(hasSetTextAction()).onFirst().performTextClearance() }))
        run(Act("опись: меню → «Столбцы описи»",
            prepare = { tapDesc("Ещё") },
            act = { tapText("Столбцы описи") },
            // Назад — стрелкой экрана столбцов (у диалога своё окно: его «назад» — не у активности).
            undo = { onAllNodes(hasContentDescription("Назад")).onLast().performClick() }))
    }

    @Test fun столбцы() {
        val obj = inventory("Склад")
        show(Screen.Inventory(obj))
        compose.tapDesc("Ещё"); settle(600); compose.tapText("Столбцы описи"); settle()
        run(Act("столбцы: указать поле столбца",
            prepare = { onAllNodes(hasText("Наименование"), useUnmergedTree = true).onFirst().performClick() },
            act = { onAllNodes(hasText("Инвентарный номер"), useUnmergedTree = true).onFirst().performClick() },
            undo = { tapText("Как решит приложение") }))
        run(Act("столбцы: другой лист",
            act = { onAllNodes(hasText("Сводка", substring = true), useUnmergedTree = true).onFirst().performClick() },
            undo = { onAllNodes(hasText("Опись  ·", substring = true), useUnmergedTree = true).onFirst().performClick() }))
    }

    @Test fun папка_описи() {
        val obj = inventory("Склад")
        val items = Inventory.parse(Inventory.fileIn(obj)!!).items
        Inventory.openFolder(obj, items[0]); Store.setNote(obj, "ключ у охраны")
        show(Screen.Browser(obj))
        run(Act("папка описи: данные обновились, содержимое то же", act = { Store.changed() }, calm = true, recordMs = 1500))
    }

    @Test fun карточка() {
        val obj = inventory("Склад")
        val item = Inventory.parse(Inventory.fileIn(obj)!!).items.first { it.inventory == "МТ-103" }
        val dir = Inventory.openFolder(obj, item)
        show(Screen.Inventory(obj), Screen.Browser(dir))
        run(Act("карточка: данные обновились, содержимое то же", act = { Store.changed() }, calm = true, recordMs = 1500))
        run(Act("карточка: отметить осмотренным",
            act = { tapText("Отметить осмотренным") },
            undo = { tapText("Осмотрено — снять отметку") }))
        run(Act("карточка: снять отметку",
            prepare = { tapText("Отметить осмотренным") },
            act = { tapText("Осмотрено — снять отметку") }))
    }

    @Test fun плашка() {
        val obj = inventory("Плохая", bad = true)
        show(Screen.Inventory(obj))
        run(Act("опись: плашка «проверьте» — скрыть", act = { tapText("Всё верно, скрыть") }, repeats = 1))
    }

    companion object {
        private val results = mutableListOf<ActionResult>()

        @JvmStatic @AfterClass fun report() {
            Report.write(java.io.File("build/reports/motion-matrix"), results, 16, title = "«Осмотр»")
            assertClean(results)
        }
    }
}

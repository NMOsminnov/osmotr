@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Store
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/** Короткие сообщения, которые показывает само приложение (там, где у платформы своих нет). */
object ToastBus {
    val messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(text: String) { messages.tryEmit(text) }
}

/** Экраны приложения — стеком: «назад» снимает верхний. */
sealed interface Screen {
    data class Browser(val dir: File) : Screen
    data class Viewer(val dir: File, val start: File) : Screen
    /** [place] — присланные фото: найти, куда положить (папка, предмет описи, новая папка). */
    data class Search(val here: File, val query: String? = null, val place: List<Picked>? = null) : Screen
    data class Contacts(val dir: File) : Screen
    /** [place] — присланные фото («Поделиться» из WhatsApp): выбрать папку, куда их положить. */
    /** [here] — последняя открытая из дерева папка: вернулись — дерево раскрыто до неё; [query] — поиск. */
    data class Tree(val here: File, val place: List<Picked>? = null, val query: String = "") : Screen
    /** [into] — набираем предметы в эту папку (касание — добавить/убрать). */
    data class Inventory(val obj: File, val into: File? = null, val query: String? = null) : Screen
}

/**
 * Всё приложение — одно на Android и iPhone. Платформа даёт [host] (камера, файлы,
 * «Поделиться») и [incoming] — описи, присланные через «Поделиться» / «Открыть с помощью».
 */
@Composable
fun OsmotrApp(host: Host, incoming: SnapshotStateList<Picked>, start: List<Screen> = emptyList()) {
    OsmotrTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalHost provides host) { Body(host, incoming, start) }
        }
    }
}

@Composable
private fun Body(host: Host, incoming: SnapshotStateList<Picked>, start: List<Screen>) {
    var canWrite by remember { mutableStateOf(host.canWrite()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canWrite = host.canWrite() }
    if (!canWrite) {
        StorageGate(host)
        return
    }
    Store.ensureRoot()

    val stack = remember { mutableStateListOf<Screen>(Screen.Browser(Store.root)).apply { addAll(start) } }
    val scope = rememberCoroutineScope()
    // Присланные описи — на главный, загрузка с окном прогресса.
    LaunchedEffect(incoming.size) {
        if (incoming.isEmpty()) return@LaunchedEffect
        val got = incoming.toList(); incoming.clear()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        // Фото (WhatsApp, галерея через «Поделиться») — спросить папку; книги — описями.
        val (photos, books) = got.partition { it.isPhoto }
        if (books.isNotEmpty()) InventoryImport.start(host, scope, books) { stack.add(Screen.Inventory(it)) }
        if (photos.isNotEmpty()) stack.add(Screen.Search(Store.root, place = photos))
    }
    if (InventoryImport.jobs.isNotEmpty()) ImportDialog(InventoryImport.jobs, onClose = { InventoryImport.jobs.clear() })
    val snackbar = remember { SnackbarHostState() }
    // Плашка про снятое — всегда про последний предмет: прежняя (и ждущая очереди) снимается.
    val shotBanner = remember { LatestBanner() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Папки могли поменять с компьютера, пока приложение было в фоне: перечитать, а
        // исчезнувшую — сменить ближайшей существующей.
        Store.changed()
        // Описи кучей в одной папке или прямо в корне (прежние версии, файлы по USB) — по своим папкам.
        scope.launch(Dispatchers.IO) { runCatching { Inventory.migrate() }.onFailure { kg.osmotr.core.Telemetry.error("перенос описей", it) } }
        // Отчёты, которые не успели собраться до закрытия, — собрать (отчёт в папке держим свежим).
        scope.launch(Dispatchers.IO) { runCatching { Inventory.resumeReports() }.onFailure { kg.osmotr.core.Telemetry.error("отчёты при запуске", it) } }
        while (stack.size > 1 && stack.last().let { it is Screen.Browser && !it.dir.isDirectory }) stack.removeAt(stack.lastIndex)
        (stack.last() as? Screen.Browser)?.let { top ->
            if (!top.dir.isDirectory) stack[stack.lastIndex] = Screen.Browser(generateSequence(top.dir) { it.parentFile }.first { it.isDirectory || it == Store.root })
        }
        // Вернулись из камеры — снятое уже в папке, папка на экране.
        scope.launch {
            val got = host.afterResume() ?: return@launch
            // Снятое по описи — отчёт «осмотрено / нет» обновляется сам.
            if (got.count > 0) launch(Dispatchers.IO) { Inventory.refreshReportSoon(got.dir) }
            // Снимали из списка описи — остаться в нём (следующая бирка), а что легло — плашкой с «Открыть».
            if (ShootFrom.list == got.dir) {
                ShootFrom.list = null
                // Ушли в камеру из списка и ничего не сняли — заведённая под это папка пуста.
                if (got.count == 0) launch(Dispatchers.IO) { runCatching { Store.pruneEmpty(got.dir) } }
                if (got.count > 0) {
                    // Прежняя плашка (прошлый предмет) — прочь, и та, что ждала очереди, тоже: снимали
                    // быстро 1–5, а плашка показывала второй, и «Открыть» уводило в него (отзыв
                    // 15.10.2026: «хочу через уведомление быстро зайти в 5, а там ещё только 2 висит»).
                    // Без срока плашка с действием висела до нажатия — теперь уходит сама, смахивается,
                    // с крестиком.
                    shotBanner.show(scope, snackbar, "${got.dir.name}: +${plural(got.count, "снимок", "снимка", "снимков")}", "Открыть") {
                        stack.add(Screen.Browser(got.dir))
                    }
                }
                return@launch
            }
            ShootFrom.list = null
            val top = stack.last()
            if (top !is Screen.Browser || top.dir != got.dir) stack.add(Screen.Browser(got.dir))
        }
    }
    // Сообщения от платформы (iPhone: своих всплывающих нет) — той же плашкой внизу.
    LaunchedEffect(Unit) { ToastBus.messages.collect { snackbar.showSnackbar(it) } }
    val failed by Undo.failed.collectAsStateWithLifecycle()
    LaunchedEffect(failed) { if (failed > 0) snackbar.showSnackbar("Не удалось удалить") }
    // Удалено — «Вернуть» внизу: ошибка пальца не стоит снимков.
    val undo by Undo.last.collectAsStateWithLifecycle()
    LaunchedEffect(undo) {
        val t = undo ?: return@LaunchedEffect
        val what = listOfNotNull(
            t.folders.takeIf { it > 0 }?.let { plural(it, "папка", "папки", "папок") },
            t.photos.takeIf { it > 0 || t.folders == 0 }?.let { plural(it, "снимок", "снимка", "снимков") },
        ).joinToString(", ")
        val r = snackbar.showSnackbar("Удалено: $what", actionLabel = "Вернуть", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) Undo.restore(t)
        if (Undo.last.value === t) Undo.last.value = null
    }
    // Папка предмета, которую открыли и покинули пустой (зашли в карточку и вышли, ничего не
    // сняв), — прочь, как только её нет ни в одном экране стека (автор, 13.10.2026: «Если папка
    // остаётся пустой, её надо удалить»). Пока она под другим экраном (набор из описи, просмотр),
    // она на месте. Заведённая человеком папка без привязки к предмету так не убирается — её имя
    // он набрал сам; убирается, когда из неё удалили последнее (Store.pruneEmpty).
    val held = remember { HashSet<File>() }
    val inStack = stack.mapNotNull { (it as? Screen.Browser)?.dir ?: (it as? Screen.Inventory)?.into }.toSet()
    LaunchedEffect(inStack) {
        val left = held - inStack
        held.clear(); held += inStack
        if (left.isNotEmpty()) launch(Dispatchers.IO) {
            left.forEach { d -> runCatching { if (File(d, Inventory.MEMBERS).isFile) Store.pruneEmpty(d) } }
        }
    }
    // Телеметрия: какой экран открыт (вид, без имён) и падение в прошлый раз — предложить отправить.
    val topName = stack.lastOrNull()?.let(::screenName)
    LaunchedEffect(topName, stack.size) { topName?.let { kg.osmotr.core.Telemetry.event("экран", "$it (глубина ${stack.size})") } }
    LaunchedEffect(Unit) {
        val crashed = withContext(Dispatchers.IO) { kg.osmotr.core.Telemetry.unsentCrashes() }
        if (crashed.isEmpty()) return@LaunchedEffect
        val r = snackbar.showSnackbar("В прошлый раз «Осмотр» закрылся с ошибкой", actionLabel = "Отправить", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) sendDiagnostics(host) else withContext(Dispatchers.IO) { kg.osmotr.core.Telemetry.markSent() }
    }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        when (val top = stack.last()) {
            is Screen.Browser -> BrowserScreen(
                dir = top.dir,
                open = { push(Screen.Browser(it)) },
                goTo = { dir -> // переход по пути — стек обрезается до этой папки
                    val i = stack.indexOfLast { it is Screen.Browser && it.dir == dir }
                    if (i >= 0) while (stack.size > i + 1) stack.removeAt(stack.lastIndex) else replaceTop(Screen.Browser(dir))
                },
                camera = { dir -> host.takePhotos(dir) },
                search = { push(Screen.Search(top.dir)) },
                contacts = { push(Screen.Contacts(top.dir)) },
                tree = { push(Screen.Tree(top.dir)) },
                inventory = { push(Screen.Inventory(it)) },
                pickItems = { obj -> push(Screen.Inventory(obj, into = top.dir)) },
                view = { photo -> push(Screen.Viewer(top.dir, photo)) },
                renamed = { replaceTop(Screen.Browser(it)) },
                back = if (stack.size > 1) ({ pop() }) else null,
            )
            // Найденное открывается вместо поиска: «назад» — туда, откуда искали.
            is Screen.Search -> {
                val place = top.place
                // Присланное кладётся один раз (см. TreeScreen): второе касание, пока пишутся снимки, клало их дважды.
                val placed = remember(place) { booleanArrayOf(false) }
                SearchScreen(here = top.here, initial = top.query.orEmpty(), close = { pop() }, place = place?.size,
                    // Дерево — поверх поиска: «назад» из него возвращает к поиску, а не на главный
                    // (отзыв 15.10.2026: «возврат ведёт на главную, а не в предыдущее меню»).
                    tree = place?.let { p -> { push(Screen.Tree(Store.root, place = p)) } },
                    open = { dir ->
                        if (place == null) replaceTop(Screen.Browser(dir))
                        // Присланные фото — в найденную папку снимками; папка открывается сразу, снимки
                        // появляются в ней по мере записи (а не через секунды стоящего экрана).
                        else if (!placed[0]) scope.launch {
                            placed[0] = true
                            replaceTop(Screen.Browser(dir))
                            val n = withContext(Dispatchers.IO) {
                                placePhotos(dir, place)
                            }
                            // Куда легло — плашкой с полным путём, надолго (а не короткой подсказкой).
                            snackbar.showSnackbar("${plural(n, "фото", "фото", "фото")} → ${Store.title(dir)}", duration = SnackbarDuration.Long)
                        }
                    })
            }
            is Screen.Inventory -> InventoryScreen(obj = top.obj, into = top.into, initialQuery = top.query, openFolder = { push(Screen.Browser(it)) },
                folders = { push(Screen.Browser(it)) }, close = { pop() },
                // Папка после набора могла переименоваться — вернуться в неё по новому имени.
                picked = { dir -> pop(); replaceTop(Screen.Browser(dir)) },
                contacts = { push(Screen.Contacts(it)) })
            is Screen.Tree -> {
                val placed = remember(top.place) { booleanArrayOf(false) }
                TreeScreen(current = top.here, place = top.place, open = { d ->
                    val place = top.place
                    // Разложили присланное — дерево и поиск под ним своё отработали: в папку, а «назад»
                    // из неё — туда, откуда начинали, не обратно к раскладке. Снимки пишутся здесь, в
                    // области приложения, — один раз.
                    if (place != null) {
                        if (placed[0]) return@TreeScreen
                        placed[0] = true
                        pop(); if ((stack.last() as? Screen.Search)?.place != null) pop(); push(Screen.Browser(d))
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { placePhotos(d, place) }
                            snackbar.showSnackbar("${plural(n, "фото", "фото", "фото")} → ${Store.title(d)}", duration = SnackbarDuration.Long, withDismissAction = true)
                        }
                    } else {
                        // Путь помнится (автор, 15.10.2026: «выход из папки тоже возвращает на главную,
                        // даже если я из дерева заходил… Путь надо помнить»): папка — поверх дерева, а
                        // дерево запоминает её — «назад» вернёт в дерево, раскрытое до неё и с ней на виду.
                        stack[stack.lastIndex] = top.copy(here = d)
                        push(Screen.Browser(d))
                    }
                }, close = { pop() }, query = top.query, onQuery = { q -> stack[stack.lastIndex] = top.copy(query = q) })
            }
            is Screen.Contacts -> ContactsScreen(dir = top.dir, close = { pop() })
            is Screen.Viewer -> ViewerScreen(dir = top.dir, start = top.start, close = { pop() })
        }
        // Над клавиатурой: вернулись из камеры в список — клавиатура уже открыта под следующую бирку.
        val keys = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        Banners(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = if (keys) 8.dp else 88.dp))
    }
}

/** Без доступа к памяти работать нельзя: снимки ложатся в «Осмотры» в памяти телефона. */
@Composable
private fun StorageGate(host: Host) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Нужен доступ к памяти", style = MaterialTheme.typography.headlineSmall)
        Text("Снимки хранятся в папке «Осмотры» в памяти телефона.",
            Modifier.padding(top = 12.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { host.requestStorage() }) { Text("Разрешить") }
    }
}

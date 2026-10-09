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
import androidx.compose.material3.SnackbarHost
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
    data class Search(val here: File, val query: String? = null) : Screen
    data class Contacts(val dir: File) : Screen
    data class Tree(val here: File) : Screen
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
        InventoryImport.start(host, scope, got) { stack.add(Screen.Inventory(it)) }
    }
    if (InventoryImport.jobs.isNotEmpty()) ImportDialog(InventoryImport.jobs, onClose = { InventoryImport.jobs.clear() })
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Папки могли поменять с компьютера, пока приложение было в фоне: перечитать, а
        // исчезнувшую — сменить ближайшей существующей.
        Store.changed()
        // Описи кучей в одной папке или прямо в корне (прежние версии, файлы по USB) — по своим папкам.
        scope.launch(Dispatchers.IO) { runCatching { Inventory.migrate() } }
        // Отчёты, которые не успели собраться до закрытия, — собрать (отчёт в папке держим свежим).
        scope.launch(Dispatchers.IO) { runCatching { Inventory.resumeReports() } }
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
                if (got.count > 0) {
                    // Прежняя плашка (прошлый предмет) пережила уход в камеру — убрать: сказать про этот.
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar("${got.dir.name}: +${plural(got.count, "снимок", "снимка", "снимков")}", actionLabel = "Открыть")
                    if (r == SnackbarResult.ActionPerformed) stack.add(Screen.Browser(got.dir))
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
        if (r == SnackbarResult.ActionPerformed) Store.restore(t)
        if (Undo.last.value === t) Undo.last.value = null
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
            is Screen.Search -> SearchScreen(here = top.here, initial = top.query.orEmpty(), open = { replaceTop(Screen.Browser(it)) }, close = { pop() })
            is Screen.Inventory -> InventoryScreen(obj = top.obj, into = top.into, initialQuery = top.query, openFolder = { push(Screen.Browser(it)) },
                folders = { push(Screen.Browser(it)) }, close = { pop() },
                // Папка после набора могла переименоваться — вернуться в неё по новому имени.
                picked = { dir -> pop(); replaceTop(Screen.Browser(dir)) })
            is Screen.Tree -> TreeScreen(current = top.here, open = { replaceTop(Screen.Browser(it)) }, close = { pop() })
            is Screen.Contacts -> ContactsScreen(dir = top.dir, close = { pop() })
            is Screen.Viewer -> ViewerScreen(dir = top.dir, start = top.start, close = { pop() })
        }
        // Над клавиатурой: вернулись из камеры в список — клавиатура уже открыта под следующую бирку.
        val keys = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = if (keys) 8.dp else 88.dp))
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

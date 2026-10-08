package kg.osmotr

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

/** Экраны — стек: назад снимает верхний. */
sealed interface Screen {
    data class Browser(val dir: File) : Screen
    data class Viewer(val dir: File, val start: File) : Screen
    data class Search(val here: File) : Screen
    data class Contacts(val dir: File) : Screen
    data class Tree(val here: File) : Screen
    /** [into] — набираем предметы в эту папку (касание — добавить/убрать). */
    data class Inventory(val obj: File, val into: File? = null) : Screen
}

/**
 * Описи, присланные в приложение через «Поделиться» (WhatsApp, Telegram, почта) или
 * «Открыть с помощью», — ждут здесь, пока приложение откроется и получит доступ к памяти.
 */
object Incoming {
    val uris = androidx.compose.runtime.mutableStateListOf<Uri>()

    fun take(intent: Intent?) {
        intent ?: return
        val got = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))?.let(got::add)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let(got::addAll)
            Intent.ACTION_VIEW -> intent.data?.let(got::add)
            else -> return
        }
        // Некоторые мессенджеры кладут файлы только в clipData.
        if (got.isEmpty()) intent.clipData?.let { c -> (0 until c.itemCount).mapNotNullTo(got) { c.getItemAt(it).uri } }
        uris.addAll(got.distinct())
        intent.action = null  // поворот / возврат не загружает второй раз
    }
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Incoming.take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Incoming.take(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF2F81F7), onPrimary = Color.White, secondary = Color(0xFFFFC857),
                background = Color(0xFF0E1116), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF21262D),
            )) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(Store.canWrite(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canWrite = Store.canWrite(context) }
    if (!canWrite) {
        StorageGate { canWrite = Store.canWrite(context) }
        return
    }
    Store.ensureRoot()

    val stack = remember { mutableStateListOf<Screen>(Screen.Browser(Store.root)) }
    val scope = rememberCoroutineScope()
    // Присланные описи — на главный, загрузка с окном прогресса.
    LaunchedEffect(Incoming.uris.size) {
        if (Incoming.uris.isEmpty()) return@LaunchedEffect
        val got = Incoming.uris.toList(); Incoming.uris.clear()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        InventoryImport.start(context, scope, got) { stack.add(Screen.Inventory(it)) }
    }
    if (InventoryImport.jobs.isNotEmpty()) ImportDialog(InventoryImport.jobs, onClose = { InventoryImport.jobs.clear() })
    val snackbar = remember { SnackbarHostState() }
    // Камера — как ожидающая результата, внутри задачи приложения: «назад» из неё — сюда.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val shoot: (File) -> Unit = { dir ->
        SystemCamera.begin(context, dir)
        runCatching { camera.launch(SystemCamera.intent()) }.onFailure {
            SystemCamera.cancel(context)
            scope.launch { snackbar.showSnackbar("На телефоне нет приложения камеры") }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Папки могли поменять с компьютера, пока приложение было в фоне: перечитать, а
        // исчезнувшую — сменить ближайшей существующей.
        Store.changed()
        // Описи кучей в одной папке или прямо в корне (прежние версии, файлы по USB) — по своим папкам.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { Inventory.migrate() }.onFailure { android.util.Log.w("osmotr", "перенос описей", it) } }
        while (stack.size > 1 && stack.last().let { it is Screen.Browser && !it.dir.isDirectory }) stack.removeAt(stack.lastIndex)
        (stack.last() as? Screen.Browser)?.let { top ->
            if (!top.dir.isDirectory) stack[stack.lastIndex] = Screen.Browser(generateSequence(top.dir) { it.parentFile }.first { it.isDirectory || it == Store.root })
        }
        // Вернулись из камеры — снятое уже в папке, папка на экране.
        if (SystemCamera.session(context) == null) return@LifecycleEventEffect
        scope.launch {
            val got = SystemCamera.collect(context) ?: return@launch
            // Снятое по описи — отчёт «осмотрено / нет» обновляется сам.
            if (got.count > 0) launch(kotlinx.coroutines.Dispatchers.IO) { Inventory.refreshReport(got.dir) }
            val top = stack.last()
            if (top !is Screen.Browser || top.dir != got.dir) stack.add(Screen.Browser(got.dir))
        }
    }
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
                camera = shoot,
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
            is Screen.Search -> SearchScreen(here = top.here, open = { replaceTop(Screen.Browser(it)) }, close = { pop() })
            is Screen.Inventory -> InventoryScreen(obj = top.obj, into = top.into, openFolder = { push(Screen.Browser(it)) },
                folders = { push(Screen.Browser(it)) }, close = { pop() },
                // Папка после набора могла переименоваться — вернуться в неё по новому имени.
                picked = { dir -> pop(); replaceTop(Screen.Browser(dir)) })
            is Screen.Tree -> TreeScreen(current = top.here, open = { replaceTop(Screen.Browser(it)) }, close = { pop() })
            is Screen.Contacts -> ContactsScreen(dir = top.dir, close = { pop() })
            is Screen.Viewer -> ViewerScreen(
                dir = top.dir, start = top.start,
                close = { pop() },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}

/** Без доступа к памяти работать нельзя: снимки ложатся в `Осмотры/` в корне памяти. */
@Composable
private fun StorageGate(recheck: () -> Unit) {
    val context = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Нужен доступ к памяти", style = MaterialTheme.typography.headlineSmall)
        Text("Снимки хранятся в папке «Осмотры» в памяти телефона.",
            Modifier.padding(top = 12.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }) { Text("Разрешить") }
    }
}

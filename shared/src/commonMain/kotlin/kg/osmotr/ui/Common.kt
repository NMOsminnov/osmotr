package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ввод имени папки: клавиатура сразу, «Готово» на клавиатуре — подтверждение. */
@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Создать", onDone: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = Store.cleanName(value.text).isNotEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onDone(value.text) }),
                placeholder = { Text("Например, Насос 1") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value.text) }, enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/** Комментарий к папке: несколько строк, пусто — убрать. */
@Composable
fun NoteDialog(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Комментарий") },
        text = {
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                placeholder = { Text("Например: течь по сальнику, шильдик не читается") })
        },
        confirmButton = { TextButton(onClick = { onDone(value) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/**
 * Куда перенести: дерево всех папок разом (путь к [start] раскрыт), касание — выбрать,
 * «Сюда» — перенести. [moving] — переносимые папки: они и всё внутри них приглушены.
 */
@Composable
fun FolderPicker(title: String, start: File, action: String, onPick: (File) -> Unit, onCancel: () -> Unit, moving: Collection<File> = emptyList()) {
    var target by remember { mutableStateOf(start) }
    var creating by remember { mutableStateOf(false) }
    val blocked: (File) -> Boolean = { f -> moving.any { m -> f == m || f.absolutePath.startsWith(m.absolutePath + File.separator) } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                FolderTree(current = start, highlight = target, onPick = { target = it }, disabled = blocked,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(AppIcons.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Новая папка", color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(target) }, enabled = !blocked(target)) { Text(action) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
    if (creating) NameDialog("Новая папка в «${Store.title(target)}»", onDone = { name ->
        Store.createFolder(target, name)?.let { target = it }
        creating = false
    }, onCancel = { creating = false })
}

// ---------- Выгрузка без провода ----------

fun sharePhotos(host: Host, photos: List<File>) { if (photos.isNotEmpty()) host.share(photos, "image/jpeg", "Поделиться") }

/**
 * Папка целиком — ZIP со всей вложенностью, потоком (в память не собирается). Без сжатия:
 * JPEG уже сжат, а без сжатия архив собирается в разы быстрее. [progress] — доля 0..1.
 */
suspend fun zipFolder(host: Host, dir: File, progress: (Float) -> Unit): Zipped =
    zipItems(host, listOf(dir), dir.parentFile ?: dir, if (dir == Store.root) "Осмотры" else dir.name, progress = progress)

/** Выбранные папки и снимки — одним архивом; пути — от [base]. */
private fun ours(name: String) = name == Store.NOTE || name == Store.CONTACTS || name == Inventory.MEMBERS || name == Inventory.BROKEN ||
    ((name.startsWith(Inventory.PREFIX) || name.startsWith("Осмотр — ")) && name.endsWith(".xlsx", true))

/** Готовый архив: файл, сколько в нём снимков и сколько весит. */
data class Zipped(val file: File, val photos: Int, val bytes: Long)

/** [photosSince] — только снимки новее этого времени (выгрузка «новое с прошлой» и «за сегодня»); прочее наше — всё. */
suspend fun zipItems(host: Host, items: List<File>, base: File, name: String, photosSince: Long = 0L,
                     progress: (Float) -> Unit): Zipped = withContext(Dispatchers.IO) {
    val out = File(host.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
    val zip = File(out, Store.cleanName(name).ifEmpty { "Осмотры" } + ".zip")
    // Отчёт осмотра — свежий, в архив.
    items.forEach { Inventory.refreshReport(it) }
    // Только снимки осмотра, комментарии, контакты, описи с отчётами и составы папок — ничего постороннего.
    val photos = items.flatMap { it.walkTopDown().onEnter { d -> !d.name.startsWith(".") || d in items } }
        .filter { Store.isPhoto(it) && it.lastModified() > photosSince || (it.isFile && ours(it.name)) }.distinct()
    val total = photos.sumOf { it.length() }.coerceAtLeast(1)
    var done = 0L
    zip.sink { sink ->
        val z = kg.osmotr.core.ZipWriter(sink)
        for (f in photos) { z.add(f, f.relativeTo(base).path) { n -> done += n; progress(done.toFloat() / total) } }
        z.finish()
    }
    // Проверка готового архива: открыть и сверить с папкой — сколько файлов и байт легло.
    // Иначе обрыв (кончилось место) выглядел бы как обычный архив поменьше.
    val expected = photos.sumOf { it.length() }
    check(done == expected) { "архив неполный: записано ${megabytes(done)} из ${megabytes(expected)}" }
    Zipped(zip, photos.count(Store::isPhoto), zip.length())
}

/** «256,3 МБ», «1,2 ГБ». */
fun megabytes(bytes: Long): String {
    val (v, unit) = if (bytes >= 1L shl 30) bytes / (1L shl 30).toDouble() to "ГБ" else bytes / (1L shl 20).toDouble() to "МБ"
    val tenths = kotlin.math.round(v * 10).toLong()
    return "${tenths / 10},${tenths % 10} $unit"
}

/** Отправить проверенный архив; сколько в нём — видно сразу (не гадать по размеру в мессенджере). */
fun shareChecked(host: Host, z: Zipped) {
    host.toast("Архив проверен: ${plural(z.photos, "снимок", "снимка", "снимков")}, ${megabytes(z.bytes)}", long = true)
    host.share(listOf(z.file), "application/zip", "Отправить архив")
}

/** Отправить файлы (отчёт Excel и т. п.). */
fun shareFiles(host: Host, files: List<File>, type: String) = host.share(files, type)

/** Последнее удалённое — для «Вернуть» внизу экрана (главный экран показывает и возвращает). */
object Undo {
    val last = kotlinx.coroutines.flow.MutableStateFlow<Store.Trashed?>(null)
    /** Не удалилось — сказать прямо, а не молчать. */
    val failed = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun trash(items: Collection<File>) { Store.trash(items)?.let { last.value = it } ?: run { failed.value++ } }
}

/** «12 фото», «1 папка», «5 папок». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = if (m10 == 1 && m100 != 11) one else if (m10 in 2..4 && m100 !in 12..14) few else many
    return "$n $w"
}

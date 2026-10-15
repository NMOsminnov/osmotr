@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/**
 * Структура «Осмотров» целиком — проверить, что где лежит, не открывая каждую папку, и
 * переложить: зажать и перетащить (на плашку «Осмотры» снизу — на верхний уровень). Зажать и
 * отпустить — отметить; отмеченные переносятся вместе. У папки — сколько внутри папок и
 * снимков, значки — комментарий, контакты. Касание — перейти в папку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(current: File, open: (File) -> Unit, close: () -> Unit, place: List<Picked>? = null,
               query: String = "", onQuery: (String) -> Unit = {}) {
    val host = LocalHost.current
    var allOpen by rememberSaveable { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<File>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }
    // Перенос — с «Отменить»: промах пальцем возвращается одним касанием.
    val move: (List<File>, File) -> Unit = { items, to ->
        val back = items.mapNotNull { d -> Store.moveFolder(d, to)?.let { it to (d.parentFile ?: Store.root) } }
        selected.clear()
        if (back.isNotEmpty()) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val what = if (back.size == 1) "«${items.first().name}»" else plural(back.size, "папка", "папки", "папок")
            val r = snackbar.showSnackbar("$what → «${if (to == Store.root) "Осмотры" else to.name}»", actionLabel = "Отменить",
                duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) back.forEach { (moved, from) -> Store.moveFolder(moved, from) }
        }
    }
    Scaffold(
        snackbarHost = { Banners(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Снять выбор") } },
                title = { Text("Отмечено: ${plural(selected.size, "папка", "папки", "папок")}", maxLines = 1) },
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text(if (place == null) "Структура" else "Куда положить: ${plural(place.size, "фото", "фото", "фото")}", maxLines = 1) },
                actions = {
                    IconButton(onClick = { allOpen = !allOpen }) {
                        Icon(if (allOpen) AppIcons.UnfoldLess else AppIcons.UnfoldMore, if (allOpen) "Свернуть всё" else "Раскрыть всё")
                    }
                },
            )
        },
    ) { inner ->
        // Присланные фото: касание папки — окно «сюда / новая папка по инвентарнику или названию»,
        // затем — куда ляжет полным путём и «Положить». Кладётся один раз: повторное касание, пока
        // пишутся снимки (по нескольку мегабайт), раньше клало их второй раз (отзыв 15.10.2026: «они
        // дублируются, типа дважды»).
        var target by remember { mutableStateOf<File?>(null) }
        var confirm by remember { mutableStateOf<Pair<String, suspend () -> File?>?>(null) }
        var placing by remember { mutableStateOf(false) }
        val pick: (File) -> Unit = if (place == null) open else { dir -> if (!placing) target = dir }
        target?.let { dir ->
            PlaceInFolderDialog(dir, place!!.size, place = { where, resolve -> target = null; confirm = where to resolve }, dismiss = { target = null })
        }
        confirm?.let { (where, resolve) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Положить ${plural(place!!.size, "фото", "фото", "фото")} сюда?") },
                text = { DialogBody { Text(where, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) } },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    confirm = null
                    if (placing) return@TextButton
                    placing = true
                    // Папка заводится здесь, а снимки пишет приложение (open): экран дерева закрывается
                    // сразу, и запись в его области отменилась бы на полпути.
                    scope.launch {
                        val dir = resolve() ?: run { placing = false; return@launch }
                        open(dir)
                    }
                }, modifier = Modifier.testTag("положить")) { Text("Положить") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text("Назад") } },
            )
        }
        // «+» у папки: при раскладке — то же окно, что по касанию (сюда или новая папка); в «Структуре» —
        // завести папку и остаться в дереве.
        var creating by remember { mutableStateOf<File?>(null) }
        creating?.let { dir ->
            PlaceInFolderDialog(dir, null, place = { where, resolve ->
                creating = null
                scope.launch {
                    val made = resolve()
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(if (made != null) "Создана: ${Store.title(made)}" else "Не создана: $where", duration = SnackbarDuration.Short, withDismissAction = true)
                }
            }, dismiss = { creating = null })
        }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(inner)) {
            // Поиск по папкам — и при раскладке присланного, и в «Структуре» (отзыв 15.10.2026: «поиск тоже»).
            androidx.compose.material3.OutlinedTextField(query, onQuery,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("поиск в дереве"), singleLine = true,
                placeholder = { Text("Найти папку: название или номер") },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Очистить") } },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            if (place != null) Text("Коснитесь папки — положить сюда. «+» справа — новая папка внутри (по инвентарнику или названию).",
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            FolderTree(current = current, onPick = pick, allOpen = allOpen, onMove = if (place == null && query.isBlank()) move else null, selected = selected,
                filter = query,
                // При раскладке «+» — тем же окном «сюда / новая папка»; в «Структуре» — только новая папка.
                onCreateIn = { d -> if (place != null) { if (!placing) target = d } else creating = d },
                modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

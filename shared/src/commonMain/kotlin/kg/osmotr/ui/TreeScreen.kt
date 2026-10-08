@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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
import kotlinx.coroutines.launch

/**
 * Структура «Осмотров» целиком — проверить, что где лежит, не открывая каждую папку, и
 * переложить: зажать и перетащить (на плашку «Осмотры» снизу — на верхний уровень). Зажать и
 * отпустить — отметить; отмеченные переносятся вместе. У папки — сколько внутри папок и
 * снимков, значки — комментарий, контакты. Касание — перейти в папку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(current: File, open: (File) -> Unit, close: () -> Unit) {
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Снять выбор") } },
                title = { Text("Отмечено: ${plural(selected.size, "папка", "папки", "папок")}", maxLines = 1) },
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Структура") },
                actions = {
                    IconButton(onClick = { allOpen = !allOpen }) {
                        Icon(if (allOpen) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore, if (allOpen) "Свернуть всё" else "Раскрыть всё")
                    }
                },
            )
        },
    ) { inner ->
        FolderTree(current = current, onPick = open, allOpen = allOpen, onMove = move, selected = selected,
            modifier = Modifier.fillMaxSize().padding(inner))
    }
}

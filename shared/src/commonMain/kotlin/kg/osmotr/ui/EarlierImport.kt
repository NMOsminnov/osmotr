package kg.osmotr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «A», «B», … «Z», «AA» — как подписи столбцов Excel. */
private fun letters(c: Int): String {
    var n = c + 1
    val out = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; out.insert(0, 'A' + r); n = (n - 1) / 26 }
    return out.toString()
}

/**
 * Импорт уже отсмотренного (автор, 13.10.2026): таблица человека открывается как в Excel —
 * листы, буквы столбцов, номера строк; человек нажимает столбец с инвентарниками. Строки,
 * объединённые по ширине, — заголовки: они бледные и пропускаются. Сразу видно, сколько
 * нашлось в описи, сколько из этого нового и чего в описи нет; «Отметить» — и готово.
 * Столбец, где совпадений больше всего, выбран заранее — человек может выбрать другой.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarlierImportScreen(obj: File, fileName: String, sheets: List<Xlsx.Sheet>, onClose: () -> Unit, onDone: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var sheetIndex by remember { mutableStateOf(0) }
    val sheet = sheets[sheetIndex]
    val width = remember(sheet) { sheet.rows.maxOfOrNull { it.size } ?: 0 }
    // Столбец по умолчанию — где больше всего совпадений с описью (в фоне: таблица бывает большой).
    val best by produceState<Int?>(null, sheet) {
        value = withContext(Dispatchers.Default) {
            val st = Inventory.status(obj)
            (0 until width).maxByOrNull { c -> Inventory.previewEarlier(obj, sheet, c, st).found.size }
                ?.takeIf { c -> Inventory.previewEarlier(obj, sheet, c, st).found.isNotEmpty() }
        }
    }
    var chosen by remember(sheet) { mutableStateOf<Int?>(null) }
    val column = chosen ?: best
    val preview by produceState<Inventory.Earlier?>(null, sheet, column) {
        value = column?.let { c -> withContext(Dispatchers.Default) { Inventory.previewEarlier(obj, sheet, c) } }
    }
    var saving by remember { mutableStateOf(false) }
    val across = rememberScrollState()
    // Выбранный столбец — на виду: выбранный сам мог оказаться за правым краем, обрезанным.
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(column) {
        val c = column ?: return@LaunchedEffect
        val cell = with(density) { 128.dp.toPx() }
        // Предел прокрутки появляется после раскладки таблицы — дождаться его.
        androidx.compose.runtime.snapshotFlow { across.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
        across.animateScrollTo((c * cell - cell / 2).toInt().coerceIn(0, across.maxValue))
    }

    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Отмена") } },
                    title = {
                        Column {
                            Text("Импорт осмотренного", maxLines = 1)
                            Text(fileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val p = preview
                        when {
                            column == null && best == null && width > 0 -> Text("Нажмите столбец, где записаны инвентарные номера.",
                                style = MaterialTheme.typography.bodyMedium)
                            p == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            else -> {
                                Text("Столбец ${letters(column ?: 0)}: в описи нашлось ${p.found.size}" +
                                    if (p.already > 0) " (уже осмотрено ${p.already})" else "", style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(
                                    "не нашлось в описи: ${p.unknown.size}".takeIf { p.unknown.isNotEmpty() },
                                    "строк-заголовков пропущено: ${p.skipped}".takeIf { p.skipped > 0 },
                                ).joinToString(" · ").ifEmpty { "Все значения столбца нашлись в описи." },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (p.unknown.isNotEmpty()) Text("Например: " + p.unknown.take(3).joinToString(", ") { "«${it.take(24)}»" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button(
                            onClick = {
                                val c = column ?: return@Button
                                saving = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { Inventory.importEarlier(obj, sheet, c, fileName) }
                                    saving = false
                                    onDone(r.fresh)
                                }
                            },
                            enabled = !saving && (preview?.fresh ?: 0) > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(when {
                                saving -> "Отмечаем…"
                                (preview?.fresh ?: 0) > 0 -> "Отметить осмотренными: ${preview!!.fresh}"
                                preview != null && preview!!.found.isNotEmpty() -> "Всё найденное уже осмотрено"
                                else -> "Нечего отмечать"
                            })
                        }
                    }
                }
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                if (sheets.size > 1) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(sheets) { i, s ->
                        FilterChip(selected = i == sheetIndex, onClick = { sheetIndex = i }, label = { Text(s.name, maxLines = 1) })
                    }
                }
                if (width == 0) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Лист пустой.") }
                    return@Column
                }
                val numW = 44.dp; val cellW = 128.dp
                // Шапка — буквы столбцов; нажатие выбирает столбец.
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.width(numW).height(36.dp))
                    Row(Modifier.horizontalScroll(across)) {
                        for (c in 0 until width) {
                            val on = c == column
                            Box(Modifier.width(cellW).height(36.dp).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { chosen = c }, contentAlignment = Alignment.Center) {
                                Text(letters(c), fontWeight = FontWeight.SemiBold,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(sheet.rows, key = { i, _ -> i }) { r, row ->
                        val wide = r in sheet.wide
                        Row(Modifier.fillMaxWidth().height(40.dp)) {
                            Box(Modifier.width(numW).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Text("${r + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (wide) {
                                // Объединено по ширине — заголовок раздела: пропускается.
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart) {
                                    Text("${row.firstOrNull { it.isNotBlank() }.orEmpty()}  · заголовок, пропускается", maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else Row(Modifier.horizontalScroll(across)) {
                                for (c in 0 until width) {
                                    val on = c == column
                                    Box(Modifier.width(cellW).fillMaxSize()
                                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                        .clickable { chosen = c }.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        Text(row.getOrNull(c).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

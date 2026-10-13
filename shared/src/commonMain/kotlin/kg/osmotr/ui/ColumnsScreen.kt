package kg.osmotr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Inventory.Field
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Столбцы описи» — как приложение прочло файл описи, и поправить (автор, 13.10.2026: «Есть ли
 * возможность отредактировать чтение файла, например если автоматически не тот столбец
 * считался»). Лист как в Excel: над столбцами — что в них взято (подбор — бледно, указанное —
 * ярко), у строк — шапка, служебное выше неё и точка у каждой строки, ставшей предметом. Нажатие
 * на букву — что в этом столбце; на номер строки — шапка или первая строка предметов; лист можно
 * не брать. Итог пересчитывается сразу: сколько предметов, что подозрительно, у скольких папок
 * предмет при таком чтении пропадёт. «Сохранить» — один раз, в журнал. [ask] — открыто потому,
 * что список сам не нашёлся: так и сказано сверху.
 */
@Composable
fun ColumnsScreen(obj: File, ask: Boolean = false, onClose: () -> Unit, onSaved: () -> Unit = onClose) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    class Loaded(val file: File, val sheets: List<Xlsx.Sheet>, val saved: Inventory.Choices, val bound: List<String>, val previous: Boolean)
    val loaded by produceState<Loaded?>(null, obj) {
        value = withContext(Dispatchers.IO) {
            Inventory.fileIn(obj)?.let { f ->
                Loaded(f, runCatching { Xlsx.sheets(f.readBytes()) }.getOrDefault(emptyList()), Inventory.choicesOf(obj), Inventory.boundIn(obj), Inventory.hasPrevious(obj))
            }
        }
    }
    val l = loaded
    var draft by remember(l) { mutableStateOf(l?.saved ?: Inventory.Choices()) }
    val reading by produceState<Inventory.Reading?>(null, l, draft) {
        val src = l ?: return@produceState
        value = withContext(Dispatchers.Default) { Inventory.read(src.sheets, draft) }
    }
    // Лист по умолчанию: где больше предметов; не нашлось нигде — где больше заполненных строк.
    var sheetIndex by remember(l) { mutableStateOf<Int?>(null) }
    LaunchedEffect(reading) {
        val r = reading ?: return@LaunchedEffect
        if (sheetIndex == null) sheetIndex = r.sheets.indices.maxByOrNull { k ->
            r.sheets[k].items.size * 100_000 + r.sheets[k].sheet.rows.count { row -> row.any(String::isNotBlank) }
        } ?: 0
    }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var colAsk by remember { mutableStateOf<Int?>(null) }
    var rowAsk by remember { mutableStateOf<Int?>(null) }
    val changed = l != null && draft != l.saved
    fun back() { if (changed) leaving = true else onClose() }

    Dialog(onDismissRequest = { if (!saving) back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val r = reading
        val k = sheetIndex
        val sr = if (r != null && k != null) r.sheets.getOrNull(k) else null
        val t = sr?.table
        // Выбор листа сейчас: не подошедший к этому файлу — как пустой (правка начинается с чистого).
        fun current(): Inventory.SheetChoice = sr?.let { s -> draft.of(s.sheet.name)?.takeUnless { s.stale } ?: Inventory.SheetChoice(s.sheet.name) }
            ?: Inventory.SheetChoice("")
        fun put(c: Inventory.SheetChoice) { draft = draft.with(c.copy(sign = "", signRows = 0)) }
        val fieldAt: Map<Int, Field> = t?.columns?.entries?.associate { (f, c) -> c to f }.orEmpty()
        val manual: Map<Int, Field> = current().columns.filterValues { it >= 0 }.entries
            .mapNotNull { (n, c) -> Field.entries.firstOrNull { it.name == n }?.let { c to it } }.toMap()
        val width = remember(sr?.sheet) { sr?.sheet?.let(::usedWidth) ?: 0 }
        val itemRows = remember(sr) { sr?.items?.map { it.row - 1 }?.toSet().orEmpty() }
        val across = rememberScrollState()
        val list = rememberLazyListState()
        // Открыли лист — к шапке (на большом листе сверху бывает десяток служебных строк).
        LaunchedEffect(k, t != null) { t?.let { list.scrollToItem((it.headerRow - 1).coerceAtLeast(0)) } }

        TableFrame(
            title = "Столбцы описи", subtitle = l?.file?.name ?: "Открываем…", onBack = ::back, backEnabled = !saving,
            top = {
                if (r != null && r.sheets.size > 1) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(r.sheets) { i, s ->
                        FilterChip(i == k, { sheetIndex = i }, {
                            Text(s.sheet.name + "  ·  " + if (s.table == null) "не взят" else s.items.size.toString(), maxLines = 1)
                        }, Modifier.testTag("лист ${s.sheet.name}"))
                    }
                }
            },
            panel = {
                if (r == null || sr == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@TableFrame }
                if (ask && r.items.isEmpty()) Text("Список предметов сам не нашёлся. Нажмите букву столбца с инвентарными номерами или наименованиями.",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t == null) "Лист «${sr.sheet.name}» не взят в опись" else "Лист «${sr.sheet.name}»: ${plural(sr.items.size, "предмет", "предмета", "предметов")}",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val skip = current().skip
                    Switch(!skip, { on -> put(current().copy(skip = !on)) }, Modifier.testTag("брать лист"))
                }
                if (r.sheets.size > 1) Text("Всего в описи: ${plural(r.items.size, "предмет", "предмета", "предметов")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val keys = remember(r) { r.items.map { Inventory.key(Inventory.id(it)) }.toSet() }
                val lost = l?.bound.orEmpty().count { Inventory.key(it) !in keys }
                val warnings = Inventory.warnings(r) + listOfNotNull(
                    "У ${plural(lost, "папки", "папок", "папок")} предмет при таком чтении не находится в описи (было папок с предметами: ${l?.bound?.size})"
                        .takeIf { lost > 0 })
                warnings.forEach { w ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AppIcons.Warning, null, Modifier.padding(top = 2.dp, end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Text(w, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (sr.items.isNotEmpty()) {
                    Text("Как прочитано:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sr.items.take(3).forEach { i ->
                        Text(listOf(i.number, i.inventory.ifEmpty { "без инв. №" }, i.name.ifEmpty { "без наименования" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Нажмите букву столбца — укажете, что в нём. Номер строки — шапка или первая строка предметов. " +
                    "Яркая подпись — указано вами, бледная — решило приложение.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (l?.previous == true) TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Inventory.restorePrevious(obj); Inventory.refreshReportSoon(obj) }
                        host.toast("Возвращён прежний файл описи"); onSaved()
                    }
                }) { Icon(AppIcons.History, null); Text("  Вернуть прежний файл описи") }
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { draft = Inventory.Choices() }, enabled = !saving && draft.sheets.isNotEmpty(),
                        modifier = Modifier.weight(1f)) { Text("Как решит приложение", maxLines = 2) }
                    Button(
                        onClick = {
                            val src = l ?: return@Button
                            saving = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // Отпечаток верха листа — у каждого выбора, который сейчас применяется;
                                    // не подошедший к этому файлу и не тронутый — как был (вдруг подойдёт к следующему).
                                    val stale = reading?.sheets?.filter { it.stale }?.map { it.sheet.name }?.toSet().orEmpty()
                                    val sealed = Inventory.Choices(draft.sheets.map { c ->
                                        val s = src.sheets.firstOrNull { it.name == c.sheet }
                                        if (s == null || c.sheet in stale) c else Inventory.sealed(s, c.copy(sign = "", signRows = 0))
                                    })
                                    Inventory.setChoices(obj, sealed)
                                    Inventory.refreshReportSoon(obj)
                                }
                                saving = false
                                host.toast("Столбцы описи сохранены: ${plural(reading?.items?.size ?: 0, "предмет", "предмета", "предметов")}")
                                onSaved()
                            }
                        },
                        enabled = !saving && changed && (r?.items?.isNotEmpty() == true),
                        modifier = Modifier.weight(1f).testTag("сохранить"),
                    ) { Text(if (saving) "Сохраняем…" else "Сохранить") }
                }
            },
        ) { m ->
            when {
                r == null || sr == null -> Box(m.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Открываем файл…") }
                width == 0 -> Box(m.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Лист пустой.") }
                else -> SheetGrid(sr.sheet, width, across, selected = colAsk, modifier = m, state = list,
                    marks = { c -> fieldAt[c]?.let { ColumnMark(shortTitle(it), manual[c] == it) } },
                    look = { row ->
                        when {
                            t == null -> RowLook.OTHER
                            row == t.headerRow -> RowLook.HEADER
                            row in itemRows -> RowLook.ITEM
                            row < t.body -> RowLook.ABOVE
                            else -> RowLook.OTHER
                        }
                    },
                    onColumn = { colAsk = it }, onRow = { rowAsk = it })
            }
        }

        colAsk?.let { c ->
            val cur = current()
            val at = fieldAt[c]
            val body = t?.body ?: 0
            val samples = sr?.sheet?.rows?.drop(body)?.map { it.getOrElse(c) { "" }.trim() }?.filter(String::isNotEmpty)?.take(3).orEmpty()
            fun choose(f: Field?) {
                val cols = cur.columns.filter { (n, v) -> v != c && (f == null || n != f.name) }
                put(cur.copy(skip = false, columns = if (f == null) cols else cols + (f.name to c)))
                colAsk = null
            }
            AlertDialog(
                onDismissRequest = { colAsk = null },
                title = { Text("Столбец ${Inventory.letter(c)}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (samples.isNotEmpty()) Text("В нём: " + samples.joinToString(", ") { "«${it.take(24)}»" },
                            Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // «Не брать» и «как решит приложение» — сверху: под девятью полями на телефоне их
                        // приходилось искать прокруткой (нашла матрица путей).
                        Column(Modifier.padding(bottom = 4.dp)) {
                            if (at != null) TextButton(onClick = {
                                put(cur.copy(columns = cur.columns.filter { (n, v) -> v != c && n != at.name } + (at.name to -1))); colAsk = null
                            }) { Text("Не брать этот столбец") }
                            if (cur.columns.any { (n, v) -> v == c || n == at?.name }) TextButton(onClick = { choose(null) }) { Text("Как решит приложение") }
                        }
                        Field.entries.forEach { f ->
                            Row(Modifier.fillMaxWidth().clickable { choose(f) }.padding(vertical = 2.dp).testTag("поле ${f.title}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(at == f, { choose(f) })
                                Column {
                                    Text(f.title, fontWeight = if (at == f) FontWeight.SemiBold else null)
                                    Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { colAsk = null }) { Text("Закрыть") } },
            )
        }

        rowAsk?.let { row ->
            val cur = current()
            fun set(c: Inventory.SheetChoice) { put(c); rowAsk = null }
            AlertDialog(
                onDismissRequest = { rowAsk = null },
                title = { Text("Строка ${row + 1}") },
                text = {
                    Column {
                        sr?.sheet?.rows?.getOrNull(row)?.filter(String::isNotBlank)?.take(4)?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · ") { s -> s.take(20) }, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { set(cur.copy(skip = false, header = row, body = null)) }) { Text("Это шапка таблицы") }
                        TextButton(onClick = { set(cur.copy(skip = false, body = row, header = (cur.header ?: t?.headerRow)?.takeIf { it in 0 until row } ?: -1)) }) {
                            Text("С этой строки — предметы")
                        }
                        TextButton(onClick = { set(cur.copy(skip = false, header = -1, body = row)) }) { Text("Шапки нет, предметы — с этой строки") }
                        if (cur.header != null || cur.body != null) TextButton(onClick = { set(cur.copy(header = null, body = null)) }) { Text("Как решит приложение") }
                    }
                },
                confirmButton = { TextButton(onClick = { rowAsk = null }) { Text("Закрыть") } },
            )
        }

        if (leaving) AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Не сохранять изменения?") },
            text = { Text("Указанное на этом экране пропадёт, опись останется прочитанной как раньше.") },
            confirmButton = { TextButton(onClick = { leaving = false; onClose() }) { Text("Не сохранять") } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Остаться") } },
        )
    }
}

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что помнит экран описи объекта, пока приложение открыто: фильтры и место в списке. */
private class InventoryView {
    var query by mutableStateOf("")
    var list by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var priority by mutableStateOf<String?>(null)
    var onlyLeft by mutableStateOf(false)
    var onlyBroken by mutableStateOf(false)
    var sort by mutableStateOf(Sort.NUMBER)
    val scroll = LazyListState()
}
private val views = HashMap<File, InventoryView>()

private enum class Sort(val title: String) { NUMBER("по №"), PRIORITY("по приоритету"), COST("по стоимости") }

/** «1 044 321,55» — деньги по-русски: тысячи через пробел, два знака после запятой. */
fun money(v: Double?): String {
    if (v == null) return ""
    val cents = kotlin.math.round(kotlin.math.abs(v) * 100).toLong()
    val int = (cents / 100).toString().reversed().chunked(3).joinToString("\u00A0").reversed()
    return (if (v < 0) "-" else "") + int + "," + (cents % 100).toString().padStart(2, '0')
}

/**
 * Осмотр по описи объекта: ходим по списку и снимаем. Сверху — поиск (набрал номер с бирки —
 * предмет тут же), сколько осмотрено, фильтры (список, статус, «только неосмотренные»),
 * порядок (№, приоритет, стоимость). Касание — папка предмета (нет — создаётся) с «Снимать»;
 * вернулся — тот же список на том же месте, предмет уже отмечен. Зажатие — отметить несколько и
 * «В одну папку» (похожие предметы — три одинаковых юнита) или «Нерабочие» — разом, без захода в папки.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InventoryScreen(obj: File, into: File? = null, initialQuery: String? = null, openFolder: (File) -> Unit, folders: (File) -> Unit, close: () -> Unit,
                    picked: (File) -> Unit = {}) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // Набор в папку — свой список с начала (сверху — что уже в ней), опись помнит своё место.
    val v = remember(obj, into) { if (into != null) InventoryView() else views.getOrPut(obj) { InventoryView() } }
    LaunchedEffect(initialQuery) { initialQuery?.let { v.query = it } }
    val version by Store.version.collectAsStateWithLifecycle()
    var reparse by remember { mutableStateOf(0) }
    data class Data(val parsed: List<Inventory.Parsed>, val status: Inventory.Status)
    val data by produceState<Data?>(null, obj, version, reparse) {
        value = withContext(Dispatchers.IO) {
            Data(Inventory.filesIn(obj).map { Inventory.parse(it) }, Inventory.status(obj)).also {
                runCatching { Inventory.writeReport(obj) }  // отчёт — всегда свежий
            }
        }
    }
    val selected = remember(obj) { mutableStateListOf<Inventory.Item>() }
    var importing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf<String?>(null) }  // «Не найдено» — своя папка с названием от человека

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }
    // Набор в папку: она переименовывается по составу — держим её текущее имя.
    var target by remember(into) { mutableStateOf(into) }
    val inTarget by produceState(emptyList<Inventory.Item>(), target, version) {
        value = target?.let { t -> withContext(Dispatchers.IO) { runCatching { Inventory.itemsIn(t) }.getOrDefault(emptyList()) } }.orEmpty()
    }
    val targetKeys = remember(inTarget) { inTarget.map { Search.compact(it.inventory) }.toSet() }
    fun inHere(i: Inventory.Item) = Search.compact(i.inventory) in targetKeys
    // Порядок набора — по составу при входе: отмеченная строка остаётся под пальцем, а не улетает вверх.
    var first by remember(into) { mutableStateOf<List<Inventory.Item>?>(null) }
    LaunchedEffect(inTarget) { if (first == null && inTarget.isNotEmpty()) first = inTarget }
    // Отметки копятся и применяются разом («Готово» или «назад»): ключ — инвентарник, значение — быть ли в папке.
    val pending = remember(into) { androidx.compose.runtime.mutableStateMapOf<String, Pair<Inventory.Item, Boolean>>() }
    fun wanted(i: Inventory.Item) = pending[Search.compact(i.inventory)]?.second ?: inHere(i)
    val count = inTarget.size + pending.values.count { (i, on) -> on && !inHere(i) } - pending.values.count { (i, on) -> !on && inHere(i) }
    fun toggleInTarget(i: Inventory.Item) {
        val k = Search.compact(i.inventory); val on = !wanted(i)
        if (on == inHere(i)) pending.remove(k) else pending[k] = i to on
    }
    var applying by remember { mutableStateOf(false) }
    fun finish() {
        val t = target ?: return
        if (pending.isEmpty()) { picked(t); return }
        if (applying) return
        applying = true
        val add = pending.values.filter { it.second }.map { it.first }
        val drop = pending.values.filterNot { it.second }.map { it.first }
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                var dir = if (add.isEmpty()) t else Inventory.addTo(t, add)
                // Последний предмет папки не убирается (removeFrom сам это стережёт).
                drop.forEach { dir = Inventory.removeFrom(dir, it) }
                Inventory.refreshReport(dir); dir
            }
            pending.clear(); applying = false; target = out; picked(out)
        }
    }
    BackHandler(enabled = target != null) { finish() }

    fun load() = host.pickBooks(false) { got ->
        val book = got.firstOrNull() ?: return@pickBooks
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Inventory.attachBook(book.bytes, book.name, obj) } }
            importing = false
            r.onFailure { host.toast("${book.name}: ${it.message}", long = true) }
            if (r.isSuccess) reparse++
        }
    }

    val d = data
    val all = d?.parsed?.flatMap { it.items }.orEmpty()
    val st = d?.status
    // Спорные столбцы — сразу спросить, а не показывать опись с пустыми местами.
    // Окно столбцов само не всплывает: подбор решает сам; проверить и исправить — «Проверить опись».

    // Список на экране — в фоне: поиск с опечатками по 5–6 тыс. предметов не должен тормозить ввод.
    val shown by produceState(emptyList<Inventory.Item>(), d, v.query, v.list, v.status, v.priority, v.onlyLeft, v.onlyBroken, v.sort, first) {
        value = withContext(Dispatchers.Default) {
        val q = v.query.trim()
        var items = all.asSequence()
            .filter { v.list == null || it.list == v.list }
            .filter { v.status == null || it.status == v.status }
            .filter { v.priority == null || it.priority == v.priority }
            .filter { !v.onlyLeft || st?.inspected(it) != true }
            .filter { !v.onlyBroken || st?.broken(it) == true }
            .toList()
        if (q.isNotEmpty()) {
            // Номер с бирки, инвентарник — без разделителей; название и место — по ключевым словам
            // в любом порядке, с опечатками; точное — первым.
            items = items.mapNotNull { i -> Search.scoreItem(i, q).takeIf { it > 0 }?.let { i to it } }
                .sortedByDescending { it.second }.map { it.first }
        } else items = when (v.sort) {
            Sort.NUMBER -> items
            Sort.PRIORITY -> items.sortedWith(compareBy<Inventory.Item> { it.priority.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number.toIntOrNull() ?: 0 })
            Sort.COST -> items.sortedByDescending { it.initial ?: it.sum ?: 0.0 }
        }
        first?.takeIf { target != null && q.isEmpty() }?.let { was ->
            val keys = was.map { Search.compact(it.inventory) }.toSet(); val names = was.map { Search.compact(it.name) }.toSet()
            items = items.sortedBy { if (Search.compact(it.inventory) in keys) 0 else if (Search.compact(it.name) in names) 1 else 2 }
        }
        items
        }
    }

    Scaffold(
        topBar = {
            if (target != null) TopAppBar(
                navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Готово") } },
                title = { Column {
                    Text(target!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("в папке: $count", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                } },
                actions = {
                    val plus = pending.values.count { it.second }; val minus = pending.size - plus
                    TextButton(onClick = ::finish, enabled = !applying) {
                        Text("Готово" + listOfNotNull(plus.takeIf { it > 0 }?.let { " +$it" }, minus.takeIf { it > 0 }?.let { " −$it" }).joinToString(""))
                    }
                },
            ) else if (selected.isNotEmpty()) TopAppBar(
                navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Снять выбор") } },
                // Одно число, как в галерее: рядом с «Нерабочие» «Отмечено: 3» переносилось на две строки.
                title = { Text("${selected.size}", maxLines = 1) },
                actions = {
                    // Все отмеченные уже нерабочие — кнопка снимает отметку, иначе — ставит.
                    val allBroken = st != null && selected.all(st::broken)
                    TextButton(onClick = {
                        val items = selected.toList(); selected.clear()
                        scope.launch {
                            withContext(Dispatchers.IO) { Inventory.setBroken(obj, items, !allBroken) }
                            host.toast(if (allBroken) "Снова рабочие: ${items.size}" else "Нерабочие: ${items.size}")
                        }
                    }) {
                        Icon(AppIcons.Block, null, tint = if (allBroken) MaterialTheme.colorScheme.primary else BROKEN_RED)
                        Text(if (allBroken) "  Рабочие" else "  Нерабочие", color = if (allBroken) MaterialTheme.colorScheme.primary else BROKEN_RED)
                    }
                },
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Column { Text(obj.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Опись", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
                actions = {
                    // Выгрузка этой описи — отчёт, всё архивом, новое с прошлой выгрузки, за сегодня.
                    if (!d?.parsed.isNullOrEmpty()) IconButton(onClick = { exporting = true }) { Icon(Icons.Default.Share, "Выгрузить опись") }
                    // Папки этой описи — как раньше в «Осмотрах».
                    IconButton(onClick = { folders(obj) }) { Icon(AppIcons.Folder, "Папки описи") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(if (d?.parsed.isNullOrEmpty()) "Загрузить опись" else "Заменить файл описи") },
                                leadingIcon = { Icon(AppIcons.UploadFile, null) }, onClick = { menu = false; load() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (target == null && selected.size > 1) ExtendedFloatingActionButton(
                onClick = {
                    val items = selected.toList(); selected.clear()
                    scope.launch {
                        val dir = withContext(Dispatchers.IO) { Inventory.group(obj, items).also { Inventory.refreshReport(it) } }
                        openFolder(dir)
                    }
                },
                icon = { Icon(AppIcons.CreateNewFolder, null) }, text = { Text("В одну папку (${selected.size})") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when {
                d == null || importing -> ParseProgress(obj, Modifier.fillMaxSize())
                d.parsed.isNotEmpty() && d.parsed.all { it.items.isEmpty() } -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("В файле не нашли список предметов", style = MaterialTheme.typography.titleMedium)
                    Text("Нужна таблица с инвентарными номерами и наименованиями.", Modifier.padding(top = 8.dp, bottom = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::load) { Icon(AppIcons.UploadFile, null); Text("  Заменить файл") }
                }
                d.parsed.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Описи нет", style = MaterialTheme.typography.titleMedium)
                    Text("Excel (.xlsx) со списком: инвентарник, наименование, стоимость, приоритет.",
                        Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::load) { Icon(AppIcons.UploadFile, null); Text("  Загрузить опись") }
                }
                else -> {
                    // Поиск — первым: номер с бирки набирается сразу.
                    TextField(v.query, { v.query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (v.query.isNotEmpty()) IconButton(onClick = { v.query = "" }) { Icon(Icons.Default.Close, "Очистить") } },
                        placeholder = { Text("Инв. № или название", maxLines = 1) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    val scope2 = all.filter { (v.list == null || it.list == v.list) && (v.status == null || it.status == v.status) }
                    val done = scope2.count { st?.inspected(it) == true }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        val today = scope2.count { st?.today(it) == true }
                        val broken = scope2.count { st?.broken(it) == true }
                        Text("Осмотрено $done из ${scope2.size}" + (if (today > 0) " · сегодня +$today" else "") + (if (broken > 0) " · нерабочих $broken" else ""),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { if (scope2.isEmpty()) 0f else done.toFloat() / scope2.size }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    // По приоритетам: «П1 5/7»; касание — только этот приоритет в списке.
                    val byPriority = scope2.groupBy { it.priority }.filterKeys { it.isNotEmpty() }.entries.sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key })).associate { it.key to it.value }
                    if (byPriority.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        byPriority.forEach { (p, items) ->
                            val ok = items.count { st?.inspected(it) == true }
                            FilterChip(v.priority == p, { v.priority = if (v.priority == p) null else p }, {
                                Text("П$p  $ok/${items.size}", color = if (ok == items.size) Color(0xFF3FB950) else Color.Unspecified)
                            })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Порядок — одной кнопкой: касание — следующий (№ → приоритет → стоимость).
                        if (v.query.isBlank()) FilterChip(true, { v.sort = Sort.entries[(v.sort.ordinal + 1) % Sort.entries.size] },
                            { Text(v.sort.title) })  // стрелку «↕» iPhone рисует смайликом
                        FilterChip(v.onlyLeft, { v.onlyLeft = !v.onlyLeft }, { Text("Не осмотрено") })
                        // Нерабочие — когда есть хоть один (или фильтр уже включён).
                        val brokenHere = scope2.count { st?.broken(it) == true }
                        if (brokenHere > 0 || v.onlyBroken) FilterChip(v.onlyBroken, { v.onlyBroken = !v.onlyBroken }, { Text("Нерабочие $brokenHere", color = BROKEN_RED) })
                        val lists = all.map { it.list }.distinct()
                        if (lists.size > 1) lists.forEach { l -> FilterChip(v.list == l, { v.list = if (v.list == l) null else l }, { Text(l) }) }
                        all.filter { v.list == null || it.list == v.list }.map { it.status }.filter { it.isNotEmpty() }.distinct().takeIf { it.size > 1 }
                            ?.forEach { s -> FilterChip(v.status == s, { v.status = if (v.status == s) null else s }, { Text(s) }) }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = v.scroll, contentPadding = PaddingValues(bottom = 96.dp)) {
                        // Ищут, а в описи нет (или нашлось не то) — своя папка с названием от человека.
                        if (target == null && v.query.isNotBlank() && shown.isEmpty()) item(key = "none") {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("В описи нет «${v.query.trim()}»", style = MaterialTheme.typography.titleMedium)
                                Button(onClick = { newFolder = v.query.trim() }, Modifier.padding(top = 16.dp)) {
                                    Icon(AppIcons.CreateNewFolder, null); Text("  Создать папку")
                                }
                            }
                        }
                        items(shown, key = { it.list + "|" + it.row }) { i ->
                            val n = st?.photosOf(i) ?: 0
                            val here = target != null && wanted(i)
                            val picked = i in selected || here
                            // В режиме набора — где предмет сейчас, если не здесь (добавление перенесёт его снимки сюда).
                            val elsewhere = if (target != null && !inHere(i)) st?.folderOf(i)?.takeIf { it.isDirectory } else null
                            Row(Modifier.fillMaxWidth()
                                .background(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .combinedClickable(
                                    onClick = {
                                        if (target != null) toggleInTarget(i)
                                        else if (selected.isNotEmpty()) { if (picked) selected.remove(i) else selected.add(i) }
                                        else {
                                            // Нашёл по номеру — поиск очищается: вернулся и сразу набираешь следующий.
                                            keyboard?.hide(); val searched = v.query.isNotBlank(); if (searched) v.query = ""
                                            scope.launch { openFolder(withContext(Dispatchers.IO) { Inventory.openFolder(obj, i) }) }
                                        }
                                    },
                                    onLongClick = { if (target != null) toggleInTarget(i) else if (picked) selected.remove(i) else selected.add(i) },
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Осмотрено — зелёная отметка с числом снимков; нет — пустой кружок.
                                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                    if (target != null) Icon(if (here) AppIcons.CheckBox else AppIcons.CheckBoxOutlineBlank,
                                        if (here) "В этой папке" else "Не в этой папке",
                                        tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    else if (n > 0) {
                                        Icon(Icons.Default.CheckCircle, "Осмотрено", tint = Color(0xFF3FB950), modifier = Modifier.size(26.dp))
                                        Text("$n", Modifier.align(Alignment.BottomEnd), style = MaterialTheme.typography.labelSmall, color = Color(0xFF3FB950))
                                    } else Icon(AppIcons.RadioButtonUnchecked, "Не осмотрено", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(i.number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(i.inventory.ifEmpty { "без инв. №" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (i.priority.isNotEmpty()) Text("П${i.priority}", Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)).padding(horizontal = 6.dp),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        if (st?.broken(i) == true) BrokenLabel()
                                    }
                                    Text(i.name + (i.qty.toIntOrNull()?.takeIf { it > 1 }?.let { " · $it шт" } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (i.place.isNotEmpty()) Text(i.place, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val cost = listOfNotNull(i.initial?.let { "первонач. ${money(it)}" }, i.sum?.takeIf { it != i.initial }?.let { "сумма ${money(it)}" })
                                    if (cost.isNotEmpty()) Text(cost.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    elsewhere?.let { Text("сейчас в папке «${it.name}»", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                        if (target == null && v.query.isNotBlank() && shown.isNotEmpty()) item(key = "other") {
                            TextButton(onClick = { newFolder = v.query.trim() }, Modifier.fillMaxWidth().padding(8.dp)) {
                                Icon(AppIcons.CreateNewFolder, null); Text("  Нет нужного? Не найдено — создать папку")
                            }
                        }
                    }
                }
            }
        }
    }

    if (exporting) ExportDialog(obj, onClose = { exporting = false })
    newFolder?.let { initial ->
        NameDialog("Название папки", initial = initial, confirm = "Создать", onDone = { name ->
            newFolder = null; v.query = ""; keyboard?.hide()
            scope.launch {
                val dir = withContext(Dispatchers.IO) {
                    var n = 1; var made: File? = null
                    while (made == null && n < 100) { made = Store.createFolder(obj, if (n == 1) name else "$name ($n)"); n++ }
                    made
                }
                dir?.let(openFolder)
            }
        }, onCancel = { newFolder = null })
    }
}

/**
 * Выгрузка этой описи: отчёт Excel; всё архивом (отчёт, опись, снимки, комментарии); только
 * новое с прошлой выгрузки — после второго выезда не пересылать всё заново; за сегодня.
 * Подписи — сколько снимков уйдёт, чтобы было видно до отправки.
 */
@Composable
private fun ExportDialog(obj: File, onClose: () -> Unit) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    data class Counts(val all: Int, val since: Int?, val last: Long?, val today: Int)
    val counts by produceState<Counts?>(null) {
        value = withContext(Dispatchers.IO) {
            val last = Inventory.lastExport(obj)
            Counts(Inventory.photosSince(obj, 0), last?.let { Inventory.photosSince(obj, it) }, last, Inventory.photosSince(obj, Platform.startOfDay()))
        }
    }
    var zipping by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun zip(since: Long, suffix: String) {
        zipping = 0f
        scope.launch {
            val at = Platform.nowMs()
            runCatching { zipItems(host, listOf(obj), obj.parentFile ?: obj, obj.name + suffix, photosSince = since) { zipping = it } }
                .onSuccess { z -> withContext(Dispatchers.IO) { Inventory.markExport(obj, at) }; zipping = null; onClose(); shareChecked(host, z) }
                .onFailure { zipping = null; error = it.message }
        }
    }
    val c = counts
    AlertDialog(
        onDismissRequest = { if (zipping == null) onClose() },
        title = { Text("Выгрузить опись") },
        text = {
            Column {
                when {
                    zipping != null -> {
                        Text("Собираем архив… ${((zipping ?: 0f) * 100).toInt()} %")
                        LinearProgressIndicator(progress = { zipping ?: 0f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    error != null -> Text("Не получилось: $error", color = MaterialTheme.colorScheme.error)
                    c == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        @Composable fun option(title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
                                Text(title, style = MaterialTheme.typography.titleSmall,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        option("Отчёт Excel", "что осмотрено, что нет — один файл") {
                            scope.launch {
                                val reports = withContext(Dispatchers.IO) { Inventory.writeReport(obj); Inventory.filesIn(obj).map(Inventory::reportFile).filter { it.isFile } }
                                onClose(); if (reports.isNotEmpty()) shareFiles(host, reports, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            }
                        }
                        option("Всё архивом", "отчёт, опись, комментарии и ${plural(c.all, "снимок", "снимка", "снимков")}") { zip(0L, "") }
                        c.last?.let { last ->
                            option("Новое с прошлой выгрузки", "с ${WHEN_SHORT.format(last)}: ${plural(c.since ?: 0, "снимок", "снимка", "снимков")} и отчёт",
                                enabled = (c.since ?: 0) > 0) { zip(last, " — новое") }
                        }
                        option("За сегодня", "${plural(c.today, "снимок", "снимка", "снимков")} и отчёт", enabled = c.today > 0) {
                            zip(Platform.startOfDay(), " — " + DAY_SHORT.format(Platform.nowMs()))
                        }
                    }
                }
            }
        },
        confirmButton = { if (zipping == null) TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

private object WHEN_SHORT { fun format(ms: Long) = Platform.format(ms, "dd.MM HH:mm") }
private object DAY_SHORT { fun format(ms: Long) = Platform.format(ms, "dd.MM.yyyy") }

/**
 * Как идёт разбор описи: шкала, доля и что делается («Читаем лист «Опись»» → «Ищем столбцы» →
 * «Собираем список»). 5–6 тыс. строк читаются секунды — человек видит, что дело идёт, а не висит.
 */
@Composable
fun ParseProgress(dir: File?, modifier: Modifier = Modifier, compact: Boolean = false, file: File? = null) {
    val all by Inventory.parsing.collectAsStateWithLifecycle()
    val mine = file?.let { all[it.absolutePath] } ?: all.values.firstOrNull { file == null && (dir == null || it.file.parentFile == dir) }
    if (compact) {
        Column(modifier) {
            Text(mine?.let { "${it.stage} — ${(it.fraction * 100).toInt()} %" } ?: "Открываем…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (mine != null) LinearProgressIndicator(progress = { mine.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        return
    }
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mine?.stage ?: "Открываем опись…", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (mine != null) {
                LinearProgressIndicator(progress = { mine.fraction }, Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text("${(mine.fraction * 100).toInt()} %", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}




/** Красный «нерабочих» — тот же оттенок в описи, в папке предмета и на кнопках. */
val BROKEN_RED = Color(0xFFF85149)

@Composable
fun BrokenLabel() = Text("не работает", Modifier.clip(RoundedCornerShape(6.dp)).background(BROKEN_RED.copy(alpha = 0.2f)).padding(horizontal = 6.dp),
    style = MaterialTheme.typography.labelSmall, color = BROKEN_RED, maxLines = 1)

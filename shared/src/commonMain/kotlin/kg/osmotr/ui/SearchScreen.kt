package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Поиск папок и предметов: поле с клавиатурой сразу, результаты по мере набора; касание — в папку.
 * Открыт внутри описи — ищет только в ней (автор, 09.10.2026: «поиск работает и по другим описям»);
 * «Везде» — по всем. С главного — везде.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(here: File, initial: String = "", open: (File) -> Unit, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(initial) }
    val obj by produceState<File?>(null, here) { value = withContext(Dispatchers.IO) { if (here == Store.root) null else Inventory.objectOf(here) } }
    var everywhere by rememberSaveable { mutableStateOf(false) }
    val scope0 = obj?.takeIf { !everywhere }
    val index by produceState<List<Search.Entry>?>(null) { value = withContext(Dispatchers.IO) { Search.index() } }
    val itemIndex by produceState<List<Search.ItemEntry>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { Search.items() }.getOrDefault(emptyList()).also { all -> Search.prepare(all.map { it.item }) } }
    }
    val itemHits by produceState(emptyList<Search.ItemEntry>(), itemIndex, query, scope0) {
        val pool = scope0?.let { o -> itemIndex.filter { it.obj == o } } ?: itemIndex
        value = withContext(Dispatchers.Default) { Search.findItems(pool, query) { isActive } }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hits by produceState(emptyList<Search.Hit>(), index, query, scope0) {
        val i = index ?: return@produceState
        val inside = scope0?.let { it.absolutePath + File.separator }
        val pool = if (inside == null) i else i.filter { it.dir.absolutePath.startsWith(inside) }
        value = withContext(Dispatchers.Default) { Search.find(pool, query, here) }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            TextField(
                query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text("Поиск", maxLines = 1) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )
        }
        // Внутри описи — где искать: только в ней или везде.
        obj?.let { o ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!everywhere, { everywhere = false }, { Text("В описи «${o.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    Modifier.weight(1f, fill = false), shape = androidx.compose.foundation.shape.CircleShape)
                FilterChip(everywhere, { everywhere = true }, { Text("Везде") }, shape = androidx.compose.foundation.shape.CircleShape)
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            if (query.isNotBlank() && index != null && hits.isEmpty() && itemHits.isEmpty()) Text("Ничего не найдено", Modifier.align(Alignment.TopCenter).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                // Предметы описей — первыми: нашёл — сразу в папку предмета, к снимкам.
                if (itemHits.isNotEmpty()) item(key = "items-h") { Section("Опись") }
                items(itemHits, key = { "i:" + it.obj.path + it.item.list + it.item.row }) { e ->
                    ItemResult(e, query) {
                        keyboard?.hide()
                        scope.launch { open(withContext(Dispatchers.IO) { Inventory.openFolder(e.obj, e.item) }) }
                    }
                }
                if (itemHits.isNotEmpty() && hits.isNotEmpty()) item(key = "folders-h") { Section("Папки") }
                items(hits, key = { it.entry.dir.path }) { hit -> Result(hit, query) { keyboard?.hide(); open(hit.entry.dir) } }
            }
        }
    }
}

@Composable
private fun Result(hit: Search.Hit, query: String, onClick: () -> Unit) {
    val e = hit.entry
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Folder, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text(marked(e.name, query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(e.where, plural(e.photos, "фото", "фото", "фото")).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.StartEllipsis)
            hit.noteLine?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(AppIcons.Notes, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(marked(line, query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ItemResult(e: Search.ItemEntry, query: String, onClick: () -> Unit) {
    val i = e.item
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Checklist, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(marked("${i.inventory}  ·  № ${i.number}" + if (i.priority.isNotEmpty()) "  ·  П${i.priority}" else "", query, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(marked(i.name, query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(Store.title(e.obj), i.list, i.initial?.let { money(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Выделить в [text] слова запроса — видно, почему папка нашлась. */
private fun marked(text: String, query: String, hit: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val low = Search.norm(text)
    Search.norm(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }.forEach { t ->
        var i = low.indexOf(t)
        while (i >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hit), i, i + t.length)
            i = low.indexOf(t, i + t.length)
        }
    }
}

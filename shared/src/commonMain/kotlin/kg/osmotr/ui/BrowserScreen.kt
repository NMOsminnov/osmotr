@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Папка: путь вверху (касание сегмента — туда), сетка — сначала вложенные папки с обложкой и
 * числом снимков, потом снимки. Долгое касание снимка — выбор нескольких. «Снимать» — камера
 * прямо в эту папку.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    dir: File,
    open: (File) -> Unit,
    goTo: (File) -> Unit,
    camera: (File) -> Unit,
    search: () -> Unit,
    contacts: () -> Unit,
    tree: () -> Unit,
    inventory: (File) -> Unit,
    view: (File) -> Unit,
    renamed: (File) -> Unit,
    pickItems: (File) -> Unit,
    back: (() -> Unit)?,
) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val version by Store.version.collectAsStateWithLifecycle()
    // Последнее содержимое папки — сразу, без пустого кадра; свежее подменит его по готовности.
    val listing by produceState(Visited.listing[dir], dir, version) {
        value = withContext(Dispatchers.IO) { Store.list(dir) }.also { Visited.listing[dir] = it }
    }
    val selected: SnapshotStateList<File> = remember(dir) { emptyList<File>().toMutableStateList() }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var movingFolder by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    val note by produceState<String?>(null, dir, version) { value = withContext(Dispatchers.IO) { Store.note(dir) } }
    val people by produceState(emptyList<Store.Contact>(), dir, version) { value = withContext(Dispatchers.IO) { Store.contacts(dir) } }
    // Опись объекта (если она в этой папке) и предметы описи, лежащие в этой папке.
    data class Card(val progress: Pair<Int, Int>?, val items: List<Inventory.Item>, val similar: List<Inventory.Item>)
    val card by produceState<Card?>(null, dir, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val own = if (dir == Store.root) emptyList() else Inventory.filesIn(dir)  // в корне описей не бывает
                val progress = if (own.isEmpty()) null else {
                    val st = Inventory.status(dir); val all = own.flatMap { Inventory.parse(it).items }
                    all.count(st::inspected) to all.size
                }
                val items = if (dir == Store.root) emptyList() else Inventory.itemsIn(dir)
                val similar = if (items.isEmpty()) emptyList() else Inventory.objectOf(dir.parentFile!!)?.let { o -> Inventory.similar(o, items, Inventory.status(o)) }.orEmpty()
                Card(progress, items, similar)
            }.getOrNull()
        }
    }
    var zipping by remember { mutableStateOf<Float?>(null) }
    var zipError by remember { mutableStateOf<String?>(null) }
    // Новые описи: сразу несколько файлов, каждый — своя опись, без вопросов (та же загрузка,
    // что из «Поделиться» в мессенджере — InventoryImport).
    val pickInventory = { host.pickBooks(true) { InventoryImport.start(host, scope, it, inventory) } }
    // Прикрепить опись к папке, начатой без неё.
    val attachInventory = {
        host.pickBooks(false) { got ->
            val book = got.firstOrNull() ?: return@pickBooks
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { Inventory.attachBook(book.bytes, book.name, dir) } }
                    .onSuccess { inventory(dir) }.onFailure { zipError = "${book.name} — ${it.message}" }
            }
        }
    }
    // Прогресс описей у папок (на главном — описи карточками).
    // Сначала — какие папки описи (быстро, чтобы не прыгали из плиток в строки), потом — сколько осмотрено.
    val progress by produceState(emptyMap<File, Pair<Int, Int>?>(), listing) {
        val l = listing ?: return@produceState
        // Пока пересчитывается — прежние цифры, а не «Открываем…» у всех строк (список не мигает).
        val was = value
        value = withContext(Dispatchers.IO) { l.folders.filter { Inventory.fileIn(it.dir) != null }.associate { it.dir to was[it.dir] } }
        value = withContext(Dispatchers.IO) {
            l.folders.mapNotNull { t -> kotlinx.coroutines.yield(); Inventory.fileIn(t.dir)?.let { f -> runCatching {
                val st = Inventory.status(t.dir); val items = Inventory.parse(f).items
                t.dir to (items.count(st::inspected) to items.size)
            }.getOrNull() } }.toMap()
        }
    }
    val selecting = selected.isNotEmpty()
    // Порядок на экране — папки, затем снимки: по нему выбор протяжкой отмечает диапазон.
    // Описи — первыми, строками во всю ширину; потом папки плитками.
    val inventories = listing?.folders.orEmpty().filter { it.dir in progress }
    val plain = listing?.folders.orEmpty().filter { it.dir !in progress }
    val order = remember(listing, progress.keys) { listing?.let { l -> (inventories + plain).map { it.dir } + l.photos }.orEmpty() }
    // Положение в папке помнится: вышел во вложенную или в просмотр, вернулся — на том же месте
    // (автор, 08.10.2026: «запоминание положения при входе/выходе из папки»).
    val grid = remember(dir) { Visited.position[dir].let { LazyGridState(it?.first ?: 0, it?.second ?: 0) } }
    DisposableEffect(dir) { onDispose { Visited.position[dir] = grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset } }
    val afterLongPress = remember { AfterLongPress() }
    fun tap(action: () -> Unit) { if (afterLongPress.skip) afterLongPress.skip = false else action() }
    BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(f: File) { if (f in selected) selected.remove(f) else selected.add(f) }

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Снять выбор") } },
                title = { Text(what(selected), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { selected.clear(); selected.addAll(order) }) { Icon(Icons.Default.SelectAll, "Выбрать все") }
                    IconButton(onClick = { moving = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Переместить") }
                    IconButton(onClick = {
                        val items = selected.toList()
                        // С папками — одним архивом со вложенностью; только снимки — как есть.
                        if (items.none { it.isDirectory }) sharePhotos(host, items)
                        else scope.launch {
                            zipping = 0f
                            val zip = runCatching { zipItems(host, items, dir, Store.title(dir)) { zipping = it } }
                            zipping = null
                            zip.onSuccess { shareChecked(host, it) }.onFailure { zipError = it.message ?: "архив не собрался" }
                        }
                    }) { Icon(Icons.Default.Share, "Отправить") }
                    IconButton(onClick = { Undo.trash(selected.toList()); selected.clear() }) { Icon(Icons.Default.Delete, "Удалить") }
                },
            ) else TopAppBar(
                navigationIcon = { if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Breadcrumbs(dir, goTo) },
                actions = {
                    IconButton(onClick = search) { Icon(Icons.Default.Search, "Поиск") }
                    IconButton(onClick = tree) { Icon(Icons.Default.AccountTree, "Структура") }
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "Новая папка") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                        DropdownMenu(menu, { menu = false }) {
                            // Опись — только где она есть (на главном описи — строками и «Новая опись»).
                            if (dir != Store.root) Inventory.objectOf(dir)?.let { obj ->
                                DropdownMenuItem({ Text("Опись") }, leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                    onClick = { menu = false; inventory(obj) })
                            }
                            // Папка без описи — прикрепить (начали снимать без неё, опись прислали потом).
                            if (dir != Store.root && Inventory.objectOf(dir) == null) DropdownMenuItem({ Text("Прикрепить опись") },
                                leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                                onClick = { menu = false; attachInventory() })
                            // Папка внутри описи — отметить, какие предметы в ней лежат.
                            Inventory.objectOf(dir)?.takeIf { it != dir }?.let { obj ->
                                DropdownMenuItem({ Text("Предметы из описи") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { menu = false; pickItems(obj) })
                            }
                            DropdownMenuItem({ Text("Отправить архивом") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                menu = false
                                scope.launch {
                                    zipping = 0f
                                    val zip = runCatching { zipFolder(host, dir) { zipping = it } }
                                    zipping = null
                                    zip.onSuccess { shareChecked(host, it) }.onFailure { zipError = it.message ?: "архив не собрался" }
                                }
                            })
                            if (dir != Store.root) {
                                DropdownMenuItem({ Text("Контакты") }, leadingIcon = { Icon(Icons.Default.Person, null) },
                                    onClick = { menu = false; contacts() })
                                DropdownMenuItem({ Text(if (note == null) "Добавить комментарий" else "Комментарий") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) }, onClick = { menu = false; editingNote = true })
                                DropdownMenuItem({ Text("Переместить папку") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                    onClick = { menu = false; movingFolder = true })
                                DropdownMenuItem({ Text("Переименовать") }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                    onClick = { menu = false; rename = true })
                                DropdownMenuItem({ Text("Удалить папку") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; deleteFolder = true })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // На главном — новая опись (у каждой описи своя папка); внутри — снимать сюда.
            if (!selecting) {
                if (dir == Store.root) ExtendedFloatingActionButton(
                    onClick = { pickInventory() },
                    icon = { Icon(Icons.Default.Checklist, null) },
                    text = { Text("Новая опись", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ) else ExtendedFloatingActionButton(
                    onClick = { camera(dir) },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    text = { Text("Снимать", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                )
            }
        },
    ) { inner ->
        val l = listing
        Box(Modifier.fillMaxSize().padding(inner)) {
            // В папке предмета или с описью — карточки вместо «пусто».
            if (l != null && l.folders.isEmpty() && l.photos.isEmpty() && card?.items.isNullOrEmpty() && card?.progress == null) Empty(dir == Store.root)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                state = grid,
                // Зажал — отмечено; повёл, не отпуская, — отмечается всё по пути (снимки и папки).
                modifier = Modifier.fillMaxSize().dragToSelect(grid, order, selected, afterLongPress),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                note?.let { text ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "note", contentType = "note") {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { editingNote = true }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Notes, null, tint = MaterialTheme.colorScheme.secondary)
                            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                card?.progress?.let { (done, total) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "inventory", contentType = "inventory") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable { inventory(dir) }.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Опись: осмотрено $done из $total", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                            LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
                card?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "item", contentType = "item") {
                        ItemCard(items, card?.similar.orEmpty(), pick = { Inventory.objectOf(dir.parentFile!!)?.let(pickItems) }, add = { more ->
                            scope.launch { withContext(Dispatchers.IO) { Inventory.addTo(dir, more).also { Inventory.refreshReport(it) } }.let(renamed) }
                        }, remove = { one ->
                            scope.launch { withContext(Dispatchers.IO) { Inventory.removeFrom(dir, one).also { Inventory.refreshReport(it) } }.let(renamed) }
                        })
                    }
                }
                if (people.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "people", contentType = "people") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = contacts).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.secondary)
                        Text(people.first().let { listOf(it.name, it.phone).filter(String::isNotBlank).joinToString(" · ") } +
                            if (people.size > 1) " · ещё ${people.size - 1}" else "",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (l != null) {
                    items(inventories, key = { keyOf(it.dir) }, span = { GridItemSpan(maxLineSpan) }, contentType = { "inventory-folder" }) { tile ->
                        InventoryRow(tile, tile.dir in selected, progress[tile.dir], onClick = { tap { if (selecting) toggle(tile.dir) else inventory(tile.dir) } })
                    }
                    items(plain, key = { keyOf(it.dir) }, contentType = { "folder" }) { tile ->
                        FolderCard(tile, tile.dir in selected, onClick = { tap { if (selecting) toggle(tile.dir) else open(tile.dir) } })
                    }
                    if (l.folders.isNotEmpty() && l.photos.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Text(plural(l.photos.size, "снимок", "снимка", "снимков"), Modifier.padding(4.dp, 10.dp, 4.dp, 2.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(l.photos, key = { keyOf(it) }, contentType = { "photo" }) { photo ->
                        PhotoCell(photo, photo in selected, Modifier.clickable { tap { if (selecting) toggle(photo) else view(photo) } })
                    }
                }
            }
            zipping?.let { p ->
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp).navigationBarsPadding()) {
                    Text("Собираю архив…")
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }

    zipError?.let { e ->
        AlertDialog(onDismissRequest = { zipError = null }, title = { Text("Архив не отправлен") },
            text = { Text("Сборка не удалась: $e. Проверьте свободную память телефона.") },
            confirmButton = { TextButton(onClick = { zipError = null }) { Text("Понятно") } })
    }
    if (newFolder) NameDialog("Новая папка в «${Store.title(dir)}»", onDone = { name ->
        newFolder = false
        Store.createFolder(dir, name)?.let(open)
    }, onCancel = { newFolder = false })
    if (rename) NameDialog("Переименовать", initial = dir.name, confirm = "Готово", onDone = { name ->
        rename = false
        Store.renameFolder(dir, name)?.let(renamed)
    }, onCancel = { rename = false })
    if (deleteFolder) {
        val count = remember(dir) { Store.photoTree(dir).size }
        AlertDialog(
            onDismissRequest = { deleteFolder = false },
            title = { Text("Удалить «${dir.name}»?") },
            text = { Text("Вместе со всем содержимым: ${plural(count, "снимок", "снимка", "снимков")}.") },
            confirmButton = { TextButton(onClick = {
                deleteFolder = false
                val parent = dir.parentFile ?: Store.root
                Undo.trash(listOf(dir))
                goTo(parent)
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteFolder = false }) { Text("Отмена") } },
        )
    }
    if (editingNote) NoteDialog(note.orEmpty(), onDone = { editingNote = false; Store.setNote(dir, it) }, onCancel = { editingNote = false })
    if (movingFolder) FolderPicker("Переместить «${dir.name}»", dir.parentFile ?: Store.root, "Сюда",
        onPick = { to -> movingFolder = false; Store.moveFolder(dir, to)?.let(renamed) },
        onCancel = { movingFolder = false }, moving = listOf(dir))
    if (moving) FolderPicker("Переместить: ${what(selected)}", dir, "Сюда",
        onPick = { to ->
            moving = false
            if (to != dir) {
                val (folders, photos) = selected.partition { it.isDirectory }
                Store.move(photos, to)
                folders.forEach { Store.moveFolder(it, to) }  // в саму себя и во вложенную — не перенесётся
                selected.clear()
            }
        },
        onCancel = { moving = false }, moving = selected.filter { it.isDirectory })
}

@Composable
private fun Breadcrumbs(dir: File, goTo: (File) -> Unit) {
    val chain = generateSequence(dir) { if (it == Store.root) null else it.parentFile }.toList().reversed()
    // Путь длиннее строки — видна его конечная часть: текущая папка.
    val scroll = rememberScrollState()
    LaunchedEffect(dir, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        chain.forEachIndexed { i, d ->
            if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == chain.lastIndex
            Text(
                if (d == Store.root) "Осмотры" else d.name,
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !last) { goTo(d) }.padding(horizontal = 2.dp, vertical = 4.dp),
                style = if (last) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FolderCard(tile: Store.FolderTile, selected: Boolean, onClick: () -> Unit) {
    val host = LocalHost.current
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        tile.cover?.let { cover ->
            AsyncImage(
                ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(Store.thumbOrPhoto(cover)).size(Store.THUMB_PX).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Folder, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
            if (tile.note != null) Icon(Icons.AutoMirrored.Filled.Notes, "Есть комментарий", Modifier.size(20.dp), tint = Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(tile.dir.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    plural(tile.photos, "фото", "фото", "фото"),
                    tile.folders.takeIf { it > 0 }?.let { plural(it, "папка", "папки", "папок") },
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
            )
        }
        // Выбрана — рамка и отметка, без заливки: подпись на обложке остаётся читаемой.
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
            Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Опись — строкой во всю ширину: название, сколько осмотрено, полоса; касание — сразу в опись. */
@Composable
private fun InventoryRow(tile: Store.FolderTile, selected: Boolean, progress: Pair<Int, Int>?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.Checklist, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(tile.dir.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress == null) ParseProgress(tile.dir, compact = true)  // опись ещё читается — шкала, а не тишина
            else {
                val (done, total) = progress
                Text("осмотрено $done из $total · " + plural(tile.photos, "фото", "фото", "фото"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = Color(0xFF3FB950))
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Отпускание пальца после зажатия касанием не считается: зажатие ставит отметку ([dragToSelect]),
 * а плитка видела бы в том же отпускании касание и тут же снимала бы её — выбрать простым
 * зажатием было нельзя (автор, 08.10.2026: «выбор на зажатии на 1 не работает»).
 * Плитка спрашивает [skip] перед своим касанием; сетка ставит его на зажатии и снимает,
 * когда жест кончился (плитка своё отпускание к тому времени уже обработала).
 */
private class AfterLongPress { var skip = false }

/**
 * Предмет описи в его папке: инвентарник, наименование, стоимость, приоритет; в папке
 * нескольких — все, с «убрать». «Ещё такие же в описи» — одним касанием сюда же.
 */
@Composable
private fun ItemCard(items: List<Inventory.Item>, similar: List<Inventory.Item>, pick: () -> Unit, add: (List<Inventory.Item>) -> Unit, remove: (Inventory.Item) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${i.inventory}  ·  № ${i.number}" + if (i.priority.isNotEmpty()) "  ·  П${i.priority}" else "",
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(i.name + (i.qty.toIntOrNull()?.takeIf { it > 1 }?.let { " · $it шт" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.place.isNotEmpty()) Text(i.place, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    val cost = listOfNotNull(i.initial?.let { "первонач. ${money(it)}" }, i.sum?.takeIf { it != i.initial }?.let { "сумма ${money(it)}" })
                    if (cost.isNotEmpty()) Text(cost.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (items.size > 1) IconButton(onClick = { remove(i) }) { Icon(Icons.Default.Close, "Убрать из папки", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (similar.isNotEmpty()) TextButton(onClick = { add(similar) }) {
            Icon(Icons.Default.Add, null); Text("  Ещё такие же в описи: ${similar.size} — сюда же")
        }
        // Вручную — по всему списку описи: отметить, что ещё лежит в этой папке.
        TextButton(onClick = pick) { Icon(Icons.Default.Checklist, null); Text("  Добавить из описи") }
    }
}

/** Где был в каждой папке и что в ней лежало — пока приложение открыто. */
private object Visited {
    val position = HashMap<File, Pair<Int, Int>>()
    val listing = HashMap<File, Store.Listing>()
}

private fun keyOf(f: File) = if (f.isDirectory) "d:" + f.path else f.path

/** «2 папки, 15 снимков». */
private fun what(items: List<File>): String {
    val folders = items.count { it.isDirectory }; val photos = items.size - folders
    return listOfNotNull(
        folders.takeIf { it > 0 }?.let { plural(it, "папка", "папки", "папок") },
        photos.takeIf { it > 0 || folders == 0 }?.let { plural(it, "снимок", "снимка", "снимков") },
    ).joinToString(", ")
}

/**
 * Выбор зажатием с протяжкой, как в галерее: долгое касание отмечает элемент, движение пальца,
 * не отпуская, — диапазон от него до элемента под пальцем; у края сетка прокручивается сама.
 */
private fun Modifier.dragToSelect(grid: LazyGridState, order: List<File>, selected: SnapshotStateList<File>, after: AfterLongPress): Modifier =
    composed {
        val haptic = LocalHapticFeedback.current
        var scrollSpeed by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(scrollSpeed) {
            while (scrollSpeed != 0f) { grid.scrollBy(scrollSpeed); delay(10) }
        }
        val edge = with(LocalDensity.current) { 56.dp.toPx() }
        pointerInput(order) {
            fun at(p: Offset): Int? {
                val key = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    p.x >= item.offset.x && p.x < item.offset.x + item.size.width &&
                        p.y >= item.offset.y && p.y < item.offset.y + item.size.height
                }?.key ?: return null
                return order.indexOfFirst { keyOf(it) == key }.takeIf { it >= 0 }
            }
            var start: Int? = null
            var before: Set<File> = emptySet()
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    at(p)?.let { i ->
                        after.skip = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        before = selected.toSet()
                        start = i
                        if (order[i] in selected) selected.remove(order[i]) else selected.add(order[i])
                    }
                },
                onDrag = { change, _ ->
                    val s0 = start ?: return@detectDragGesturesAfterLongPress
                    val y = change.position.y
                    scrollSpeed = when { y < edge -> -(edge - y) / 4f; y > size.height - edge -> (y - (size.height - edge)) / 4f; else -> 0f }
                    at(change.position)?.let { i ->
                        val range = order.subList(minOf(s0, i), maxOf(s0, i) + 1).toSet()
                        val want = before + range
                        selected.removeAll { it !in want }
                        want.forEach { if (it !in selected) selected.add(it) }
                    }
                },
                onDragEnd = { start = null; scrollSpeed = 0f; after.skip = false },
                onDragCancel = { start = null; scrollSpeed = 0f; after.skip = false },
            )
        }
    }

@Composable
private fun PhotoCell(photo: File, selected: Boolean, modifier: Modifier) {
    val host = LocalHost.current
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(Store.thumbOrPhoto(photo)).size(Store.THUMB_PX).build(),
            contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
            Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Empty(root: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (root) "Создайте папку" else "Пусто",
            Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Загрузка одного файла описи: имя, что с ним, разбираемый файл (для шкалы), итог. */
class ImportJob(val name: String) {
    enum class State { WAITING, WORKING, DONE, FAILED }
    var state by mutableStateOf(State.WAITING)
    var file by mutableStateOf<File?>(null)
    var dir: File? = null
    var items by mutableStateOf(0)
    var error by mutableStateOf("")
}

/**
 * Загрузка описей — из «Новая опись» и из «Поделиться» (WhatsApp, Telegram, почта, файлы):
 * каждый файл — своя опись; по две разом (5–6 тыс. строк каждая, память телефона не
 * резиновая), остальные в очереди; окно — строкой на файл (шкала, итог или причина).
 */
object InventoryImport {
    val jobs = androidx.compose.runtime.mutableStateListOf<ImportJob>()

    fun start(host: Host, scope: kotlinx.coroutines.CoroutineScope, books: List<Picked>, open: (File) -> Unit) {
        if (books.isEmpty()) return
        jobs.clear()
        val mine = books.map { ImportJob(it.name) }
        jobs.addAll(mine)
        scope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(2)
            books.zip(mine).map { (book, job) ->
                launch {
                    gate.acquire()
                    try {
                        job.state = ImportJob.State.WORKING
                        withContext(Dispatchers.IO) { runCatching { Inventory.importBook(book.bytes, book.name) { job.file = it } } }
                            .onSuccess { job.items = it.items.size; job.dir = job.file?.parentFile; job.state = ImportJob.State.DONE }
                            .onFailure { job.error = it.message ?: "не получилось"; job.state = ImportJob.State.FAILED }
                    } finally { gate.release() }
                }
            }.forEach { it.join() }
            // Одна и без ошибок — сразу в неё; несколько — окно закроется само, если всё хорошо.
            if (mine.all { it.state == ImportJob.State.DONE }) {
                jobs.clear()
                if (mine.size == 1) mine.single().dir?.let(open)
                else host.toast("Добавлено: ${plural(mine.size, "опись", "описи", "описей")}")
            }
        }
    }
}

/** Окно загрузки описей: каждый файл — строкой со своей шкалой; закрыть — когда всё кончилось. */
@Composable
fun ImportDialog(jobs: List<ImportJob>, onClose: () -> Unit) {
    val finished = jobs.all { it.state == ImportJob.State.DONE || it.state == ImportJob.State.FAILED }
    AlertDialog(
        onDismissRequest = { if (finished) onClose() },
        title = {
            val ok = jobs.count { it.state == ImportJob.State.DONE }
            Text(when {
                !finished -> "Добавляем ${plural(jobs.size, "опись", "описи", "описей")}"
                ok == jobs.size -> "Описи добавлены"
                ok == 0 -> if (jobs.size == 1) "Опись не добавлена" else "Описи не добавлены"
                else -> "Добавлено $ok из ${jobs.size}"
            })
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(jobs.size) { k ->
                    val j = jobs[k]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(j.name.ifEmpty { "Файл без имени" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (j.state) {
                            ImportJob.State.WAITING -> Text("в очереди", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ImportJob.State.WORKING -> ParseProgress(null, compact = true, file = j.file ?: File("/нет"))
                            ImportJob.State.DONE -> Text("✓ ${plural(j.items, "предмет", "предмета", "предметов")}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF3FB950))
                            ImportJob.State.FAILED -> Text("✗ ${j.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { if (finished) TextButton(onClick = onClose) { Text("Готово") } },
    )
}

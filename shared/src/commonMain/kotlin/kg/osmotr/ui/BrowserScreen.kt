@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import okio.Path.Companion.toPath
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
    // Папку переименовывают (сменился состав) — старую не перечитывать: её уже нет, и экран
    // показал бы пустую сетку и «один предмет по имени», а через миг — новый состав.
    val listing by produceState(Visited.listing[dir], dir, version) {
        withContext(Dispatchers.IO) { if (dir.isDirectory) Store.list(dir) else null }?.let { value = it; Visited.listing[dir] = it }
    }
    val selected: SnapshotStateList<File> = remember(dir) { emptyList<File>().toMutableStateList() }
    // Убранные из папки — прячутся сразу по касанию (уезжают анимацией), не дожидаясь диска;
    // без ключа по папке: она переименуется, а пометка должна дожить до нового состава.
    val leaving = remember { mutableStateListOf<String>() }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var movingFolder by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val voices by produceState(emptyList<File>(), dir, version) { if (dir.isDirectory) value = withContext(Dispatchers.IO) { Store.voiceNotes(dir) } }
    val note by produceState<String?>(null, dir, version) { if (dir.isDirectory) value = withContext(Dispatchers.IO) { Store.note(dir) } }
    val people by produceState(emptyList<Store.Contact>(), dir, version) { if (dir.isDirectory) value = withContext(Dispatchers.IO) { Store.contacts(dir) } }
    // Опись объекта (если она в этой папке) и предметы описи, лежащие в этой папке; прошлое — сразу.
    val card by produceState(Visited.card[dir], dir, version) {
        withContext(Dispatchers.IO) { if (dir.isDirectory) cardOf(dir) else null }?.let { value = it; Visited.card[dir] = it }
    }
    LaunchedEffect(card) { card?.items?.map { Inventory.key(Inventory.id(it)) }?.toSet()?.let { here -> leaving.retainAll(here) } }
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
                    IconButton(onClick = { selected.clear(); selected.addAll(order) }) { Icon(AppIcons.SelectAll, "Выбрать все") }
                    IconButton(onClick = { moving = true }) { Icon(AppIcons.DriveFileMove, "Переместить") }
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
                // Папка предмета — заголовок сам предмет и его опись (касание — в опись), а не обрывок пути.
                title = {
                    val items = card?.items.orEmpty()
                    val obj = card?.obj
                    if (items.isNotEmpty() && obj != null) Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { inventory(obj) }.padding(horizontal = 2.dp)) {
                        Text(items.first().inventory.ifEmpty { "№ ${items.first().number}" } + if (items.size > 1) " +${items.size - 1} шт" else "",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Опись «${obj.name}»", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Breadcrumbs(dir, goTo)
                },
                actions = {
                    val itemFolder = !card?.items.isNullOrEmpty()
                    IconButton(onClick = search) { Icon(Icons.Default.Search, "Поиск") }
                    if (!itemFolder) IconButton(onClick = tree) { Icon(AppIcons.AccountTree, "Структура") }
                    if (!itemFolder) IconButton(onClick = { newFolder = true }) { Icon(AppIcons.CreateNewFolder, "Новая папка") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                        DropdownMenu(menu, { menu = false }) {
                            // Опись — только где она есть (на главном описи — строками и «Новая опись»).
                            if (dir != Store.root) Inventory.objectOf(dir)?.let { obj ->
                                DropdownMenuItem({ Text("Опись") }, leadingIcon = { Icon(AppIcons.Checklist, null) },
                                    onClick = { menu = false; inventory(obj) })
                            }
                            // Папка без описи — прикрепить (начали снимать без неё, опись прислали потом).
                            if (dir != Store.root && Inventory.objectOf(dir) == null) DropdownMenuItem({ Text("Прикрепить опись") },
                                leadingIcon = { Icon(AppIcons.UploadFile, null) },
                                onClick = { menu = false; attachInventory() })
                            // Папка внутри описи — отметить, какие предметы в ней лежат.
                            Inventory.objectOf(dir)?.takeIf { it != dir }?.let { obj ->
                                DropdownMenuItem({ Text("Предметы из описи") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { menu = false; pickItems(obj) })
                            }
                            // В папке предмета значков меньше — новая папка и структура здесь.
                            if (!card?.items.isNullOrEmpty()) {
                                DropdownMenuItem({ Text("Новая папка") }, leadingIcon = { Icon(AppIcons.CreateNewFolder, null) }, onClick = { menu = false; newFolder = true })
                                DropdownMenuItem({ Text("Структура") }, leadingIcon = { Icon(AppIcons.AccountTree, null) }, onClick = { menu = false; tree() })
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
                                    leadingIcon = { Icon(AppIcons.Notes, null) }, onClick = { menu = false; editingNote = true })
                                DropdownMenuItem({ Text("Диктофон") }, leadingIcon = { Icon(AppIcons.Mic, null) }, onClick = { menu = false; recording = true })
                                DropdownMenuItem({ Text("Переместить папку") }, leadingIcon = { Icon(AppIcons.DriveFileMove, null) },
                                    onClick = { menu = false; movingFolder = true })
                                DropdownMenuItem({ Text("Переименовать") }, leadingIcon = { Icon(AppIcons.DriveFileRenameOutline, null) },
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
                    icon = { Icon(AppIcons.Checklist, null) },
                    text = { Text("Новая опись", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ) else ExtendedFloatingActionButton(
                    onClick = { camera(dir) },
                    icon = { Icon(AppIcons.CameraAlt, null) },
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
                columns = GridCells.Adaptive(104.dp),
                state = grid,
                // Зажал — отмечено; повёл, не отпуская, — отмечается всё по пути (снимки и папки).
                modifier = Modifier.fillMaxSize().dragToSelect(grid, order, selected, afterLongPress),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                note?.let { text ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "note", contentType = "note") {
                        Row(Modifier.fillMaxWidth().panel()
                            .clickable { editingNote = true }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(AppIcons.Notes, null, tint = MaterialTheme.colorScheme.secondary)
                            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (voices.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "voice", contentType = "voice") {
                    VoiceNotesCard(voices)
                }
                card?.progress?.let { (done, total) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "inventory", contentType = "inventory") {
                        Column(Modifier.fillMaxWidth().panel(tint = MaterialTheme.colorScheme.primaryContainer)
                            .clickable { inventory(dir) }.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(AppIcons.Checklist, null, tint = MaterialTheme.colorScheme.primary)
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
                            scope.launch { withContext(Dispatchers.IO) { Inventory.addTo(dir, more).also { warmFolder(it); Inventory.refreshReportSoon(it) } }.let(renamed) }
                        }, remove = { one ->
                            leaving += Inventory.key(Inventory.id(one))
                            scope.launch { withContext(Dispatchers.IO) { Inventory.removeFrom(dir, one).also { warmFolder(it); Inventory.refreshReportSoon(it) } }.let(renamed) }
                        }, leaving = leaving, shot = { card?.status?.inspected(it) == true }, broken = { card?.status?.broken(it) == true }, setBroken = { one, on ->
                            card?.obj?.let { o -> scope.launch { withContext(Dispatchers.IO) { Inventory.setBroken(o, listOf(one), on); Inventory.refreshReportSoon(o) } } }
                        })
                    }
                }
                if (people.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "people", contentType = "people") {
                    Row(Modifier.fillMaxWidth().panel()
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
                        Box(Modifier.animateItem(fadeInSpec = motion(), placementSpec = motion(), fadeOutSpec = motion())) {
                            InventoryRow(tile, tile.dir in selected, progress[tile.dir], onClick = { tap { if (selecting) toggle(tile.dir) else inventory(tile.dir) } })
                        }
                    }
                    items(plain, key = { keyOf(it.dir) }, contentType = { "folder" }) { tile ->
                        Box(Modifier.animateItem(fadeInSpec = motion(), placementSpec = motion(), fadeOutSpec = motion())) {
                            FolderCard(tile, tile.dir in selected, onClick = { tap { if (selecting) toggle(tile.dir) else open(tile.dir) } })
                        }
                    }
                    if (l.folders.isNotEmpty() && l.photos.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Text(plural(l.photos.size, "снимок", "снимка", "снимков"), Modifier.padding(4.dp, 10.dp, 4.dp, 2.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(l.photos, key = { keyOf(it) }, contentType = { "photo" }) { photo ->
                        PhotoCell(photo, photo in selected, Modifier.animateItem(fadeInSpec = motion(), placementSpec = motion(), fadeOutSpec = motion())
                            .clickable { tap { if (selecting) toggle(photo) else view(photo) } })
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
    if (recording) VoiceRecorderDialog(dir, onClose = { recording = false })
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
    // Обрезанное слева — растворяется, а не рубится посреди слова.
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        if (scroll.value > 0) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 32.dp.toPx() / size.width to Color.Black), blendMode = BlendMode.DstIn)
    }
    Row(fade.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
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
    // Снимок сверху, подпись под ним: имя поверх пёстрой обложки не читалось.
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.panel(shape, tint = if (selected) MaterialTheme.colorScheme.primaryContainer else null).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            tile.cover?.let { cover ->
                AsyncImage(thumbRequest(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } ?: Icon(AppIcons.Folder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.secondary)
            if (tile.note != null) Icon(AppIcons.Notes, "Есть комментарий", Modifier.align(Alignment.TopStart).padding(6.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(3.dp).size(16.dp), tint = Color.White)
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(tile.dir.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, minLines = 2)
            Text(listOfNotNull(plural(tile.photos, "фото", "фото", "фото"), tile.folders.takeIf { it > 0 }?.let { plural(it, "папка", "папки", "папок") })
                .joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Опись — строкой во всю ширину: название, сколько осмотрено, полоса; касание — сразу в опись. */
@Composable
private fun InventoryRow(tile: Store.FolderTile, selected: Boolean, progress: Pair<Int, Int>?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().panel(tint = if (selected) MaterialTheme.colorScheme.primaryContainer else null)
        .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (selected) Icons.Default.CheckCircle else AppIcons.Checklist, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(tile.dir.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress == null) ParseProgress(tile.dir, compact = true)  // опись ещё читается — шкала, а не тишина
            else {
                val (done, total) = progress
                Text("осмотрено $done из $total · " + plural(tile.photos, "фото", "фото", "фото"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = DONE_GREEN)
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
private fun ItemCard(items: List<Inventory.Item>, similar: List<Inventory.Item>, pick: () -> Unit, add: (List<Inventory.Item>) -> Unit, remove: (Inventory.Item) -> Unit,
                     leaving: List<String>, shot: (Inventory.Item) -> Boolean, broken: (Inventory.Item) -> Boolean, setBroken: (Inventory.Item, Boolean) -> Unit) {
    // Состав меняется плавно: карточка растёт и сжимается, убранный уезжает, а не пропадает.
    Column(Modifier.fillMaxWidth().panel()
        .animateContentSize(motion()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Последний оставшийся — без «убрать»: папка без предмета не бывает.
        val staying = items.count { Inventory.key(Inventory.id(it)) !in leaving }
        items.forEach { i -> key(Inventory.key(Inventory.id(i))) {
          AnimatedVisibility(Inventory.key(Inventory.id(i)) !in leaving, enter = fadeIn(motion()) + expandVertically(motion()),
              exit = fadeOut(motion()) + shrinkVertically(motion())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${i.inventory}  ·  № ${i.number}" + if (i.priority.isNotEmpty()) "  ·  П${i.priority}" else "",
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(i.name + (i.qty.toIntOrNull()?.takeIf { it > 1 }?.let { " · $it шт" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.place.isNotEmpty()) Text(i.place, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    val cost = listOfNotNull(i.initial?.let { "первонач. ${money(it)}" }, i.sum?.takeIf { it != i.initial }?.let { "сумма ${money(it)}" })
                    if (cost.isNotEmpty()) Text(cost.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Нерабочее (на списание) — одним касанием, тут же и снять; только после снимков.
                    val off = broken(i); val can = shot(i)
                    val haptic = LocalHapticFeedback.current
                    FilterChip(off, { haptic.performHapticFeedback(if (off) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); setBroken(i, !off) }, {
                        Text(when { off -> "Нерабочее, на списание"; can -> "Отметить: нерабочее"; else -> "Нерабочее — сначала снимки" },
                            color = if (off) BROKEN_RED else Color.Unspecified)
                    }, enabled = can || off,
                        leadingIcon = { Icon(AppIcons.Block, null, Modifier.size(18.dp), tint = if (off) BROKEN_RED else MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BROKEN_RED.copy(alpha = 0.18f)))
                }
                if (staying > 1) IconButton(onClick = { remove(i) }) { Icon(Icons.Default.Close, "Убрать из папки", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
          }
        } }
        if (similar.isNotEmpty()) TextButton(onClick = { add(similar) }) {
            Icon(Icons.Default.Add, null); Text("  Такие же в описи: ${similar.size} — добавить сюда")
        }
        // Вручную — по всему списку описи: отметить, что ещё лежит в этой папке.
        TextButton(onClick = pick) { Icon(AppIcons.Checklist, null); Text("  Добавить из описи") }
    }
}

/** Где был в каждой папке и что в ней лежало — пока приложение открыто. */
private object Visited {
    val position = HashMap<File, Pair<Int, Int>>()
    val listing = HashMap<File, Store.Listing>()
    val card = HashMap<File, Card>()
}

/** Опись объекта (если она в этой папке) и предметы описи, лежащие в этой папке. */
private data class Card(val progress: Pair<Int, Int>?, val items: List<Inventory.Item>, val similar: List<Inventory.Item>,
                        val obj: File? = null, val status: Inventory.Status? = null)

private fun cardOf(dir: File): Card? = runCatching {
    val own = if (dir == Store.root) emptyList() else Inventory.filesIn(dir)  // в корне описей не бывает
    val progress = if (own.isEmpty()) null else {
        val st = Inventory.status(dir); val all = own.flatMap { Inventory.parse(it).items }
        all.count(st::inspected) to all.size
    }
    val items = if (dir == Store.root) emptyList() else Inventory.itemsIn(dir)
    val obj = if (items.isEmpty()) null else Inventory.objectOf(dir.parentFile!!)
    val st = obj?.let(Inventory::status)
    val similar = if (obj == null || st == null) emptyList() else Inventory.similar(obj, items, st)
    Card(progress, items, similar, obj, st)
}.getOrNull()

/**
 * Папку переименовали (состав предметов поменялся) — содержимое и карточку прочитать до перехода:
 * иначе под новым именем она открывалась пустой, а заполнялась кадром позже. Вызывать в фоне.
 */
fun warmFolder(dir: File) {
    runCatching { Visited.listing[dir] = Store.list(dir) }
    cardOf(dir)?.let { Visited.card[dir] = it }
}

/**
 * Ключ в сетке — по имени, не по пути и без обращения к диску: папку переименовали (сменился
 * состав) — снимки в ней те же, и сетка не перерисовывает их как новые.
 */
private fun keyOf(f: File) = if (f.extension.lowercase() in PHOTO_KEYS) f.name else "d:" + f.name
private val PHOTO_KEYS = setOf("jpg", "jpeg", "heic", "heif")

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
            thumbRequest(photo),
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
        Icon(AppIcons.CreateNewFolder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            ImportJob.State.DONE -> Text("✓ ${plural(j.items, "предмет", "предмета", "предметов")}", style = MaterialTheme.typography.bodySmall, color = DONE_GREEN)
                            ImportJob.State.FAILED -> Text("✗ ${j.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { if (finished) TextButton(onClick = onClose) { Text("Готово") } },
    )
}

/**
 * Миниатюра — с ключом памяти по имени снимка: после переименования папки путь другой, а
 * картинка та же — берётся из памяти сразу, без пустого квадрата и повторной загрузки.
 */
@Composable
private fun thumbRequest(photo: File) = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
    .data(Store.thumbOrPhoto(photo).path.toPath()).size(Store.THUMB_PX)
    .memoryCacheKey("thumb:" + photo.name).placeholderMemoryCacheKey("thumb:" + photo.name).build()

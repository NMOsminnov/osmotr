package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Вся структура папок: кто чей ребёнок, сколько внутри снимков и папок (со вложенными). */
class Tree(
    val children: Map<File, List<File>>,
    val photos: Map<File, Int>,
    val folders: Map<File, Int>,
    val notes: Set<File>,
    val contacts: Set<File>,
) {
    companion object {
        /** Один обход «Осмотров»; папок — сотни, в памяти — пустяк. */
        fun build(): Tree {
            val children = HashMap<File, List<File>>()
            val photos = HashMap<File, Int>()
            val folders = HashMap<File, Int>()
            val notes = HashSet<File>(); val contacts = HashSet<File>()
            fun walk(dir: File): Pair<Int, Int> {
                val kids = Store.foldersIn(dir)
                children[dir] = kids
                if (File(dir, Store.NOTE).isFile) notes += dir
                if (File(dir, Store.CONTACTS).isFile) contacts += dir
                var p = dir.listFiles()?.count(Store::isPhoto) ?: 0
                var f = kids.size
                kids.forEach { val (kp, kf) = walk(it); p += kp; f += kf }
                photos[dir] = p; folders[dir] = f
                return p to f
            }
            walk(Store.root)
            return Tree(children, photos, folders, notes, contacts)
        }
    }
}

/**
 * Дерево папок: вся структура разом, ветки раскрываются стрелкой, касание — выбрать (окно
 * переноса) или перейти (обзор). У папки — сколько внутри папок и снимков. Путь к [current]
 * раскрыт, [highlight] подсвечена; [disabled] — куда нельзя.
 *
 * С [onMove] — перенос перетаскиванием (автор, 08.10.2026): зажал и повёл — папка под пальцем;
 * отмеченные ([selected]) едут все вместе; подсвечена папка, куда можно; задержался над
 * свёрнутой на 0,2 с — раскрылась; у края — прокрутка; снизу выезжает «Осмотры» — верхний
 * уровень без прокрутки вверх. Зажал и отпустил, не двигая, — папка отмечена.
 */
@Composable
fun FolderTree(
    current: File, onPick: (File) -> Unit, modifier: Modifier = Modifier, highlight: File = current,
    disabled: (File) -> Boolean = { false },
    allOpen: Boolean? = null,
    onMove: ((List<File>, File) -> Unit)? = null,
    selected: SnapshotStateList<File>? = null,
) {
    val version by Store.version.collectAsStateWithLifecycle()
    val tree by produceState<Tree?>(null, version) { value = withContext(Dispatchers.IO) { Tree.build() } }
    val open = remember { mutableStateListOf<File>() }
    LaunchedEffect(current) {
        generateSequence(current) { if (it == Store.root) null else it.parentFile }.forEach { if (it !in open) open.add(it) }
    }
    LaunchedEffect(allOpen, tree) {
        val all = tree ?: return@LaunchedEffect
        when (allOpen) {
            true -> all.children.forEach { (d, kids) -> if (kids.isNotEmpty() && d !in open) open.add(d) }
            false -> { open.clear(); generateSequence(current) { if (it == Store.root) null else it.parentFile }.forEach { open.add(it) } }
            null -> Unit
        }
    }
    val t = tree ?: return
    data class Line(val dir: File, val depth: Int)
    val rows = remember(t, open.toList()) {
        buildList {
            fun add(dir: File, depth: Int) {
                add(Line(dir, depth))
                if (dir in open || dir == Store.root) t.children[dir].orEmpty().forEach { add(it, depth + 1) }
            }
            add(Store.root, 0)
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { rows.indexOfFirst { it.dir == highlight }.takeIf { it > 3 }?.let { list.scrollToItem(it - 3) } }

    // ---- Перенос ----
    var carried by remember { mutableStateOf<List<File>>(emptyList()) }
    var target by remember { mutableStateOf<File?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var scrollSpeed by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    val skipTap = remember { BooleanArray(1) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val homeBar = with(density) { 64.dp.toPx() }
    val edge = with(density) { 48.dp.toPx() }
    fun canDrop(items: List<File>, to: File) = items.isNotEmpty() && items.all { d ->
        to != d && to != d.parentFile && !to.absolutePath.startsWith(d.absolutePath + File.separator)
    }
    LaunchedEffect(scrollSpeed) { while (scrollSpeed != 0f) { list.scrollBy(scrollSpeed); delay(10) } }
    LaunchedEffect(target) {
        val over = target ?: return@LaunchedEffect
        delay(200)
        if (target == over && over != Store.root && over !in open && t.children[over].orEmpty().isNotEmpty()) open.add(over)
    }
    val rowsNow by rememberUpdatedState(rows)
    val moveNow by rememberUpdatedState(onMove)
    val dragModifier = if (onMove == null) Modifier else Modifier.pointerInput(Unit) {
        fun rowAt(y: Float): File? = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?.key?.let { k -> rowsNow.firstOrNull { it.dir.path == k }?.dir }
        detectDragGesturesAfterLongPress(
            onDragStart = { p ->
                val d = rowAt(p.y)
                if (d != null && d != Store.root) {
                    // Отмеченные — едут все вместе (если тащат одну из них), иначе — одна.
                    carried = if (selected != null && d in selected) selected.toList() else listOf(d)
                    finger = p; skipTap[0] = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, _ ->
                if (carried.isEmpty()) return@detectDragGesturesAfterLongPress
                finger = change.position
                val y = finger.y
                val overHome = y > boxHeight - homeBar
                scrollSpeed = when { overHome -> 0f; y < edge -> -(edge - y) / 4f; y > boxHeight - homeBar - edge -> (y - (boxHeight - homeBar - edge)) / 4f; else -> 0f }
                val over = (if (overHome) Store.root else rowAt(y))?.takeIf { canDrop(carried, it) }
                if (over != target) { target = over; if (over != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            },
            onDragEnd = {
                val items = carried; val to = target
                carried = emptyList(); target = null; scrollSpeed = 0f; skipTap[0] = false
                if (to != null) moveNow?.invoke(items, to)
                // Зажал и отпустил, не двигая, — строка видит это как касание и отмечает
                // папку (ниже, в её clickable): жест к этому времени уже снят.
            },
            onDragCancel = { carried = emptyList(); target = null; scrollSpeed = 0f; skipTap[0] = false },
        )
    }

    Box(modifier.onHeight { boxHeight = it }) {
        // Пока папка «в руке», дерево пальцем не листается: иначе прокрутка перехватывала
        // движение и отменяла перенос; у края листает автопрокрутка.
        LazyColumn(Modifier.fillMaxSize().then(dragModifier), state = list, userScrollEnabled = carried.isEmpty(),
            contentPadding = PaddingValues(bottom = if (onMove != null) 72.dp else 0.dp)) {
            items(rows, key = { it.dir.path }) { r ->
                val kids = t.children[r.dir].orEmpty().isNotEmpty()
                val expanded = r.dir in open || r.dir == Store.root
                val on = r.dir == highlight && carried.isEmpty() && selected.isNullOrEmpty()
                val blocked = disabled(r.dir)
                // «В руке» — только приглушена, касание принимает: иначе «зажал и отпустил» не
                // доходило до строки, и папка не отмечалась.
                val off = blocked || r.dir in carried
                val dropHere = r.dir == target
                val picked = selected != null && r.dir in selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(when {
                            dropHere -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            picked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            on -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else -> Color.Transparent
                        })
                        .then(if (dropHere) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(enabled = !blocked) {
                            when {
                                // Касание сразу после зажатия — «зажал и отпустил»: отметить.
                                skipTap[0] -> {
                                    skipTap[0] = false
                                    if (selected != null && r.dir != Store.root) { if (picked) selected.remove(r.dir) else selected.add(r.dir) }
                                }
                                // В режиме выбора касание отмечает, а не переходит.
                                selected != null && selected.isNotEmpty() && r.dir != Store.root ->
                                    if (picked) selected.remove(r.dir) else selected.add(r.dir)
                                else -> onPick(r.dir)
                            }
                        }
                        .padding(end = 10.dp)
                        .then(if (off) Modifier.alpha(0.35f) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width((r.depth * 16).dp))
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = kids && r.dir != Store.root) { if (expanded) open.remove(r.dir) else open.add(r.dir) },
                        contentAlignment = Alignment.Center) {
                        if (kids && r.dir != Store.root) Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            if (expanded) "Свернуть" else "Раскрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(when { picked -> Icons.Default.CheckCircle; r.dir == Store.root -> Icons.Default.Home; else -> AppIcons.Folder },
                        null, Modifier.size(20.dp),
                        tint = if (picked || on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                    Text(
                        if (r.dir == Store.root) "Осмотры" else r.dir.name,
                        Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (on || picked) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant
                    if (r.dir in t.notes) Icon(AppIcons.Notes, "Есть комментарий", Modifier.padding(end = 6.dp).size(16.dp), tint = dim)
                    if (r.dir in t.contacts) Icon(Icons.Default.Person, "Есть контакты", Modifier.padding(end = 8.dp).size(16.dp), tint = dim)
                    // Сколько внутри папок и снимков; пусто — приглушено: сразу видно, где не снимали.
                    val nf = t.folders[r.dir] ?: 0
                    val np = t.photos[r.dir] ?: 0
                    if (nf > 0) Count(AppIcons.Folder, nf, "папок внутри")
                    if (np > 0) Count(AppIcons.PhotoLibrary, np, "снимков внутри")
                    else Text("пусто", style = MaterialTheme.typography.labelMedium, color = dim.copy(alpha = 0.5f))
                }
            }
        }

        // Верхний уровень — плашкой снизу, пока папка в руке: не листать дерево вверх.
        AnimatedVisibility(carried.isNotEmpty() && onMove != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            val canHome = canDrop(carried, Store.root)
            val overHome = target == Store.root
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(if (overHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .then(if (!canHome) Modifier.alpha(0.4f) else Modifier).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Home, null, tint = if (overHome) Color.White else MaterialTheme.colorScheme.primary)
                Text(if (canHome) "Осмотры — верхний уровень" else "Уже на верхнем уровне",
                    color = if (overHome) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            }
        }

        // Папка под пальцем, пока её несут.
        if (carried.isNotEmpty()) {
            Row(Modifier.offset { IntOffset(0, (finger.y - 28.dp.toPx()).roundToInt()) }.padding(horizontal = 24.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(AppIcons.Folder, null, tint = Color.White)
                val what = if (carried.size == 1) carried.single().name else plural(carried.size, "папка", "папки", "папок")
                Text(what + (target?.let { "  →  " + (if (it == Store.root) "Осмотры" else it.name) } ?: ""),
                    color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Count(icon: ImageVector, n: Int, what: String) {
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, what, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$n", Modifier.padding(start = 3.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Высота блока в пикселях — для плашки «верхний уровень» и автопрокрутки. */
private fun Modifier.onHeight(set: (Float) -> Unit) = this.then(
    Modifier.onSizeChanged { set(it.height.toFloat()) })

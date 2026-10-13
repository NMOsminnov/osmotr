package kg.osmotr.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kg.osmotr.core.Inventory
import kg.osmotr.core.Xlsx

/*
 * Таблица человека «как в Excel» — общая для импорта осмотренного и для «Столбцов описи»:
 * буквы столбцов, номера строк, общая прокрутка вбок, подписи полей над столбцами, вид строки
 * (шапка, выше шапки, предмет, прочее). И рамка экрана с таблицей, которая сама решает, где
 * панель итогов: стоя — внизу, лёжа (экран шире, чем выше) — справа: таблице нужна высота, а
 * лёжа её мало (автор, 13.10.2026: «стоит продумать горизонтальный режим для таблиц»).
 */

/** Ширина листа — до последнего столбца, где хоть что-то есть: пустые хвосты строк не рисуются. */
fun usedWidth(sheet: Xlsx.Sheet): Int {
    var w = 0
    for (r in sheet.rows) { var i = r.size - 1; while (i >= w && r[i].isBlank()) i--; if (i + 1 > w) w = i + 1 }
    return w
}

/** Вид строки: шапка, служебное выше шапки, строка-предмет, прочее в теле (итоги, пустые, повторы шапки). */
enum class RowLook { HEADER, ABOVE, ITEM, OTHER }

/** Подпись над столбцом: какое поле в нём и указано ли оно человеком (иначе — подбор). */
class ColumnMark(val label: String, val manual: Boolean)

/** Короткие названия полей — для подписей над столбцами. */
fun shortTitle(f: Inventory.Field) = when (f) {
    Inventory.Field.NUMBER -> "№"
    Inventory.Field.INVENTORY -> "Инв. №"
    Inventory.Field.NAME -> "Наименование"
    Inventory.Field.INITIAL -> "Стоимость"
    Inventory.Field.SUM -> "Сумма"
    Inventory.Field.PRIORITY -> "Приоритет"
    Inventory.Field.LIST -> "Список"
    Inventory.Field.QUANTITY -> "Кол-во"
    Inventory.Field.PLACE -> "Место"
}

private val NUM_W = 48.dp
private val CELL_W = 128.dp
private val ROW_H = 40.dp

/**
 * Лист: шапка с буквами (и подписями полей), строки с номерами. Нажатие на столбец — [onColumn],
 * на номер строки — [onRow] (null — номера не нажимаются). [wideNote] — строки, объединённые по
 * ширине, рисуются одной полосой с этой пометкой (null — как обычные).
 */
@Composable
fun SheetGrid(
    sheet: Xlsx.Sheet, width: Int, across: ScrollState, selected: Int?,
    modifier: Modifier = Modifier,
    marks: (Int) -> ColumnMark? = { null },
    look: (Int) -> RowLook = { RowLook.ITEM },
    wideNote: String? = null,
    onColumn: (Int) -> Unit,
    onRow: ((Int) -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
) {
    val c = MaterialTheme.colorScheme
    val labelled = (0 until width).any { marks(it) != null }
    Column(modifier.testTag("таблица")) {
        Row(Modifier.fillMaxWidth().background(c.surfaceVariant)) {
            Box(Modifier.width(NUM_W).height(if (labelled) 56.dp else 36.dp))
            Row(Modifier.horizontalScroll(across)) {
                for (col in 0 until width) {
                    val on = col == selected
                    val m = marks(col)
                    Column(Modifier.width(CELL_W).height(if (labelled) 56.dp else 36.dp).background(if (on) c.primary else c.surfaceVariant)
                        .clickable { onColumn(col) }.testTag("столбец ${Inventory.letter(col)}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(Inventory.letter(col), fontWeight = FontWeight.SemiBold, color = if (on) c.onPrimary else c.onSurfaceVariant)
                        if (m != null) Text(m.label, Modifier.testTag("подпись столбца").padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (m.manual) c.tertiary else c.secondaryContainer).padding(horizontal = 6.dp),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (m.manual) c.onTertiary else c.onSecondaryContainer)
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(sheet.rows.size, key = { it }) { r ->
                val row = sheet.rows[r]
                val kind = look(r)
                Row(Modifier.fillMaxWidth().height(ROW_H)) {
                    Box(Modifier.width(NUM_W).fillMaxHeight().background(if (kind == RowLook.HEADER) c.tertiary else c.surfaceVariant)
                        .let { if (onRow != null) it.clickable { onRow(r) } else it }.testTag("строка ${r + 1}"),
                        contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Строка дала предмет — точка у номера: видно, что взято, а что пропущено.
                            if (kind == RowLook.ITEM && onRow != null) Box(Modifier.padding(end = 3.dp).size(5.dp).clip(CircleShape).background(DONE_GREEN))
                            Text("${r + 1}", style = MaterialTheme.typography.labelSmall,
                                color = if (kind == RowLook.HEADER) c.onTertiary else c.onSurfaceVariant)
                        }
                    }
                    if (wideNote != null && r in sheet.wide) {
                        Box(Modifier.fillMaxSize().background(c.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text("${row.firstOrNull { it.isNotBlank() }.orEmpty()}  · $wideNote", maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    } else Row(Modifier.horizontalScroll(across).alpha(when (kind) { RowLook.ABOVE -> 0.45f; RowLook.OTHER -> 0.7f; else -> 1f })) {
                        for (col in 0 until width) {
                            val on = col == selected
                            Box(Modifier.width(CELL_W).fillMaxHeight()
                                .background(when { kind == RowLook.HEADER -> c.tertiaryContainer; on -> c.primaryContainer; else -> c.surface })
                                .clickable { onColumn(col) }.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                Text(row.getOrNull(col).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (kind == RowLook.HEADER) FontWeight.SemiBold else null,
                                    color = if (kind == RowLook.HEADER) c.onTertiaryContainer else c.onSurface)
                            }
                        }
                    }
                }
                HorizontalDivider(color = c.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/**
 * Рамка экрана с таблицей: шапка, над таблицей — [top] (листы), сама таблица, панель итогов
 * ([panel] — прокручивается) и подвал с главным действием ([footer] — всегда на виду, вне
 * прокрутки). Лёжа панель — справа во всю высоту, стоя — внизу, не выше половины экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableFrame(
    title: String, subtitle: String, onBack: () -> Unit, backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    top: @Composable ColumnScope.() -> Unit = {},
    panel: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    grid: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        Modifier.exposeTags(),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = actions,
            )
        },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            val side = maxWidth > maxHeight && maxWidth >= 560.dp
            val panelW = minOf(360.dp, maxWidth * 0.42f); val panelH = maxHeight * 0.4f
            if (side) Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) { top(); grid(Modifier.weight(1f)) }
                VerticalDivider()
                Surface(Modifier.width(panelW).fillMaxHeight().testTag("панель"), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), content = panel)
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                top()
                grid(Modifier.weight(1f))
                Surface(Modifier.fillMaxWidth().testTag("панель"), tonalElevation = 3.dp) {
                    Column {
                        Column(Modifier.heightIn(max = panelH).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp), content = panel)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                    }
                }
            }
        }
    }
}

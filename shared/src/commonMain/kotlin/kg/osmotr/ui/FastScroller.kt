package kg.osmotr.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ползунок быстрой прокрутки у правого края длинного списка (осматривающая, 15.10.2026: «возможность
 * прокрутки самих списков»; автор: «выбираем опись из списка и внутри неё прокрутка»). Опись в
 * сотни строк пальцем листается минутами; ползунок — взял и протащил, рядом — где окажешься
 * ([label] по ключу строки: номер, инвентарник).
 *
 * Виден, пока список листают и пока его тащат; без движения гаснет и касаний не перехватывает —
 * правый край строк остаётся строкам. Короткий список (меньше [minItems]) — без ползунка.
 */
@Composable
fun FastScroller(state: LazyListState, modifier: Modifier = Modifier, minItems: Int = 40, label: (Any) -> String? = { null }) {
    val total = state.layoutInfo.totalItemsCount
    if (total < minItems) return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) shown = true else { delay(1500); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 300), label = "ползунок")
    // Где список сейчас — доля от 0 до 1: первая видимая строка с учётом сдвига внутри неё.
    val listFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            val span = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            ((state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)) / span).coerceIn(0f, 1f)
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight().width(44.dp).testTag("ползунок списка")) {
        val thumbPx = with(density) { 52.dp.toPx() }
        val travel = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else listFraction
        val y = (fraction * travel).roundToInt()
        val grab = if (alpha > 0.5f || dragging) Modifier.pointerInput(total, travel) {
            detectVerticalDragGestures(
                onDragStart = { dragging = true; dragFraction = listFraction; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
            ) { change, dy ->
                change.consume()
                dragFraction = (dragFraction + dy / travel).coerceIn(0f, 1f)
                val target = (dragFraction * (total - 1)).roundToInt()
                scope.launch { state.scrollToItem(target) }
            }
        } else Modifier
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.size(44.dp, 52.dp).graphicsLayer { this.alpha = alpha }.then(grab)) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).width(if (dragging) 8.dp else 5.dp).fillMaxHeight()
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.7f)))
        }
        // Куда приедешь — подписью слева от ползунка, пока его тащат.
        if (dragging) {
            val at = state.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { label(it.key) }
            if (at != null) Text(at, Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 52.dp.roundToPx() }, y + with(density) { 10.dp.roundToPx() }) }
                .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

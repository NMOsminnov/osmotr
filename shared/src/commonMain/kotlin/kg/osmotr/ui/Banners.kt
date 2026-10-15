package kg.osmotr.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Плашки внизу — смахиваются в любую сторону и закрываются крестиком (отзыв 15.10.2026: «это
 * уведомление можно сделать, чтобы его можно было смахнуть, а то пока я его не открою, оно висит
 * и потом мне кнопку „добавить в одну папку“ перекрывает»; автор: «Уведомления оптимизируем.
 * Ибо они мешают сильно»). Плашка не должна держать экран: её можно убрать одним движением.
 */
@Composable
fun Banners(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        key(data) {
            val swipe = rememberSwipeToDismissBoxState()
            LaunchedEffect(swipe.currentValue) {
                if (swipe.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
            }
            SwipeToDismissBox(swipe, backgroundContent = {}, modifier = Modifier.testTag("плашка")) {
                Snackbar(data, Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

/**
 * Плашка «только про последнее»: новая снимает прежнюю и ту, что ждала очереди (снимали быстро
 * 1–5 — плашка показывала второй, «Открыть» уводило в него; отзыв 15.10.2026). Короткая, с
 * крестиком, смахивается ([Banners]).
 */
class LatestBanner {
    private var job: kotlinx.coroutines.Job? = null
    fun show(scope: kotlinx.coroutines.CoroutineScope, state: SnackbarHostState, message: String, action: String? = null, onAction: () -> Unit = {}) {
        job?.cancel()
        state.currentSnackbarData?.dismiss()
        job = scope.launch {
            val r = state.showSnackbar(message, actionLabel = action, withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onAction()
        }
    }
}

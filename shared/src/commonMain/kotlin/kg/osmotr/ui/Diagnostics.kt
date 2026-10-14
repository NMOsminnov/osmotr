package kg.osmotr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kg.osmotr.core.File
import kg.osmotr.core.Platform
import kg.osmotr.core.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Архив диагностики — собрать и отдать «Поделиться»; отправленные падения больше не предлагаются. */
suspend fun sendDiagnostics(host: Host) {
    val zip = withContext(Dispatchers.IO) {
        runCatching {
            Telemetry.pack(File(host.cacheDir, "Осмотр — диагностика ${Platform.format(Platform.nowMs(), "yyyy-MM-dd HH-mm")}.zip"))
        }.onFailure { Telemetry.error("диагностика", it) }.getOrNull()
    }
    if (zip == null) { host.toast("Диагностика не собралась", long = true); return }
    Telemetry.event("диагностика", "отправлена")
    host.share(listOf(zip), "application/zip", "Диагностика «Осмотра»")
    withContext(Dispatchers.IO) { Telemetry.markSent() }
}

/**
 * «Диагностика» из меню: что уйдёт и чего в ней нет — до отправки (автор: «Телеметрия доступна
 * вся, кроме личных данных»).
 */
@Composable
fun DiagnosticsDialog(onClose: () -> Unit) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Диагностика") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Архив для разбора ошибок: журнал работы приложения, отчёты о сбоях, версия и модель телефона.")
                Text("Ваших описей, снимков, комментариев, контактов и названий папок в нём нет.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { sendDiagnostics(host); busy = false; onClose() }
            }) { Text(if (busy) "Собираем…" else "Отправить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } },
    )
}

/** Как назвать экран в журнале: вид, без имён папок. */
fun screenName(s: Screen): String = when (s) {
    is Screen.Browser -> if (s.dir == kg.osmotr.core.Store.root) "главная" else "папка"
    is Screen.Viewer -> "просмотр снимка"
    is Screen.Search -> "поиск"
    is Screen.Contacts -> "контакты"
    is Screen.Tree -> "структура"
    is Screen.Inventory -> if (s.into != null) "набор в папку" else "опись"
}

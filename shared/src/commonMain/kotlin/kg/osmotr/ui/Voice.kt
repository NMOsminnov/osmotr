package kg.osmotr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kg.osmotr.core.File
import kg.osmotr.core.Platform
import kg.osmotr.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** «0:42», «12:05». */
fun clock(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/**
 * Диктофон: большая кнопка — запись, ещё раз — стоп и в папку. Пока пишет — время и полоска
 * громкости (видно, что микрофон слышит). Закрыли во время записи — записанное сохраняется.
 */
@Composable
fun VoiceRecorderDialog(dir: File, onClose: () -> Unit) {
    val host = LocalHost.current
    var file by remember { mutableStateOf<File?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var denied by remember { mutableStateOf(false) }
    val recording = file != null
    val shown by animateFloatAsState(level, motion())

    fun stop() {
        val f = file ?: return
        file = null
        if (host.stopRecording()) { Store.voiceSaved(f); host.toast("Записано: ${clock(now - started)}") }
        // Запись уже закрыта (свернули приложение — сохранилась там) — не трогать; пустую — убрать.
        else if (!Store.isVoice(f)) f.delete()
    }
    fun start() {
        val f = Store.newVoiceFile(dir)
        if (host.startRecording(f)) { file = f; started = Platform.nowMs(); now = started; denied = false } else denied = true
    }
    LaunchedEffect(recording) {
        while (file != null) { now = Platform.nowMs(); level = host.recordingLevel(); delay(80) }
        level = 0f
    }
    DisposableEffect(Unit) { onDispose { stop() } }

    AlertDialog(
        onDismissRequest = { stop(); onClose() },
        title = { Text("Диктофон") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (recording) clock(now - started) else "Нажмите и говорите", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(84.dp).clip(CircleShape).background(if (recording) BROKEN_RED else MaterialTheme.colorScheme.primary)
                    .clickable { if (recording) stop() else start() }, contentAlignment = Alignment.Center) {
                    Icon(if (recording) AppIcons.Stop else AppIcons.Mic, if (recording) "Остановить" else "Записать", Modifier.size(40.dp), tint = Color.White)
                }
                LinearProgressIndicator(progress = { if (recording) shown else 0f }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = BROKEN_RED, trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
                if (denied) Text("Разрешите приложению доступ к микрофону и нажмите ещё раз.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                else Text(if (recording) "Нажмите ещё раз — запись ляжет в папку." else "Запись ляжет в эту папку рядом со снимками.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { stop(); onClose() }) { Text(if (recording) "Готово" else "Закрыть") } },
    )
}

/** Голосовые заметки папки: прослушать (касание — играть или остановить), длительность, удалить. */
@Composable
fun VoiceNotesCard(notes: List<File>, modifier: Modifier = Modifier) {
    val host = LocalHost.current
    var playing by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { host.stopPlaying() } }
    Column(modifier.fillMaxWidth().panel().animateContentSize(motion()).padding(vertical = 4.dp)) {
        notes.forEach { f ->
            val length by produceState(0L, f) { value = withContext(Dispatchers.IO) { host.duration(f) } }
            val on = playing == f
            Row(Modifier.fillMaxWidth().clickable {
                if (on) { host.stopPlaying(); playing = null } else { playing = f; host.play(f) { if (playing == f) playing = null } }
            }.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (on) AppIcons.Pause else AppIcons.Play, if (on) "Остановить" else "Слушать", tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Голосовая заметка", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(Platform.format(f.lastModified(), "dd.MM HH:mm"), length.takeIf { it > 0 }?.let(::clock)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (on) { host.stopPlaying(); playing = null }; Undo.trash(listOf(f)) }) {
                    Icon(Icons.Default.Delete, "Удалить заметку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

package kg.osmotr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kg.osmotr.core.Inventory
import kg.osmotr.core.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import osmotr.shared.generated.resources.Res

/**
 * Первый общий экран (проверка цепочки iPhone): пробная опись → список предметов с поиском.
 * Дальше сюда переезжают экраны Android-приложения.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF2F81F7), onPrimary = Color.White, secondary = Color(0xFFFFC857),
        background = Color(0xFF0E1116), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF21262D),
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var items by remember { mutableStateOf<List<Inventory.Item>?>(null) }
            var progress by remember { mutableStateOf(0f) }
            var query by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val book = Res.readBytes("files/sample.xlsx")
                items = withContext(Dispatchers.IO) { Inventory.parseBook(book) { p, _ -> progress = p } }
            }
            Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                Text("Осмотр", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                val all = items
                if (all == null) {
                    Text("Читаем опись — ${(progress * 100).toInt()} %", Modifier.padding(16.dp))
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(16.dp))
                    return@Column
                }
                TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true,
                    placeholder = { Text("Инв. № или название") })
                val shown = if (query.isBlank()) all else all.mapNotNull { i -> Search.scoreItem(i, query).takeIf { it > 0 }?.let { i to it } }
                    .sortedByDescending { it.second }.map { it.first }
                Text("Предметов: ${shown.size} из ${all.size}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn {
                    items(shown, key = { it.list + "|" + it.row }) { i ->
                        Column(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("${i.number}  ${i.inventory.ifEmpty { "без инв. №" }}", fontWeight = FontWeight.SemiBold)
                            Text(i.name)
                            if (i.place.isNotEmpty()) Text(i.place, color = MaterialTheme.colorScheme.secondary)
                            Text("→ папка «${Inventory.folderName(i)}»", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

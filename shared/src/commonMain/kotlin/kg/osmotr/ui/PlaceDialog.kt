package kg.osmotr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Присланные фото через дерево: касание папки — «положить сюда» или завести внутри новую папку —
 * по инвентарнику (папка предмета описи, как в поиске) или по названию (обещание автора
 * осматривающей, 15.10.2026: «создание папки по инвентарнику добавлю, чтобы можно было и через
 * дерево добавлять. Ну и по названию тоже»). Куда ляжет — полным путём, до «Положить».
 *
 * [place] получает, как найти папку: она заводится только после выбора (как в поиске).
 */
@Composable
fun PlaceInFolderDialog(dir: File, count: Int, place: (where: String, resolve: suspend () -> File?) -> Unit, dismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // Опись, в которой папка: сама она или ближайшая выше, где лежит файл описи.
    val obj = remember(dir) {
        generateSequence(dir) { it.parentFile }.takeWhile { it != Store.root && it.path.startsWith(Store.root.path) }
            .firstOrNull { Inventory.fileIn(it) != null }
    }
    val items by produceState(emptyList<Inventory.Item>(), obj) {
        value = obj?.let { o -> withContext(Dispatchers.IO) { runCatching { Inventory.fileIn(o)?.let { Inventory.parse(it).items } }.getOrNull() } }.orEmpty()
    }
    val q = text.trim()
    val found = remember(q, items) {
        if (q.isEmpty()) emptyList() else {
            val k = Inventory.key(q)
            items.filter { it.inventory.isNotEmpty() && (Inventory.key(it.inventory) == k || Inventory.key(it.inventory).contains(k)) }
                .distinctBy { Inventory.key(Inventory.id(it)) }.sortedBy { if (Inventory.key(it.inventory) == k) 0 else 1 }.take(4)
        }
    }
    val here = if (dir == Store.root) "Осмотры" else Store.title(dir)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${plural(count, "фото", "фото", "фото")} — куда?") },
        text = { DialogBody {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dir != Store.root) Row(Modifier.fillMaxWidth().clickable { place(here) { dir } }.padding(vertical = 10.dp).testTag("положить сюда"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Text("  Сюда: $here", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("новая папка поле"), singleLine = true,
                    label = { Text(if (obj != null) "Новая папка: инвентарник или название" else "Новая папка: название") })
                found.forEach { i ->
                    Row(Modifier.fillMaxWidth().clickable {
                        place("${Store.title(obj!!)} › предмет ${i.inventory} — ${i.name}") { withContext(Dispatchers.IO) { Inventory.openFolder(obj, i) } }
                    }.padding(vertical = 8.dp).testTag("папка предмета"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                        Text("  ${i.inventory} — ${i.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (q.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable {
                    place("$here › $q (новая папка)") { withContext(Dispatchers.IO) { Store.createFolder(dir, q) ?: File(dir, Store.cleanName(q)).takeIf { it.isDirectory } } }
                }.padding(vertical = 8.dp).testTag("новая папка по названию"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                    Text("  Новая папка «$q» в «$here»", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Назад") } },
    )
}

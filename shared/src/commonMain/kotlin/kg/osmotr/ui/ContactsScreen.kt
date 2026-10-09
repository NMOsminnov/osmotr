@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package kg.osmotr.ui

import kg.osmotr.core.File
import kg.osmotr.core.Inventory
import kg.osmotr.core.Platform
import kg.osmotr.core.Search
import kg.osmotr.core.Store
import kg.osmotr.core.Xlsx
import kotlinx.coroutines.IO

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Контакты людей по папке — книгой Excel `Контакты.xlsx` в самой папке: открывается и
 * правится на компьютере, приложение читает правки. Касание телефона — звонок, почты — письмо;
 * человека можно взять из телефонной книги (без разрешения на все контакты).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(dir: File, close: () -> Unit) {
    val host = LocalHost.current
    val version by Store.version.collectAsStateWithLifecycle()
    val list by produceState<List<Store.Contact>?>(null, dir, version) { value = withContext(Dispatchers.IO) { Store.contacts(dir) } }
    var editing by remember { mutableStateOf<Pair<Int, Store.Contact>?>(null) }  // индекс −1 — новый
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    // Из телефонной книги: доступ только к выбранному, без разрешения на все контакты.
    val pickPhone = host.pickPhone
    fun pick() { pickPhone?.invoke { name, phone -> editing = -1 to Store.Contact(name = name, phone = phone) } }
    fun save(all: List<Store.Contact>) = Store.setContacts(dir, all)

    // Правка — вместо списка, а не поверх: касания не должны доходить до списка под ним.
    editing?.let { (i, c) ->
        ContactEditor(c, isNew = i < 0,
            onDone = { edited -> editing = null; val all = l(list).toMutableList(); if (i < 0) all += edited else all[i] = edited; save(all) },
            onDelete = { editing = null; save(l(list).filterIndexed { j, _ -> j != i }) },
            onCancel = { editing = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Column { Text("Контакты"); Text(Store.title(dir), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
                actions = { IconButton(onClick = { runCatching { pick() } }) { Icon(AppIcons.Contacts, "Из телефонной книги") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = -1 to Store.Contact() }, icon = { Icon(AppIcons.PersonAdd, null) },
                text = { Text("Добавить") }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)
        },
    ) { inner ->
        val l = list.orEmpty()
        Box(Modifier.fillMaxSize().padding(inner)) {
            // Пусто — что здесь и зачем, одной строкой; «Добавить» — внизу.
            if (list != null && l.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(AppIcons.Contacts, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Контактов пока нет", style = MaterialTheme.typography.titleMedium)
                Text("Кто отвечает за объект: ФИО, должность, телефон. Касание телефона — звонок.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(l) { i, c ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { editing = i to c }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.secondary)
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "Без имени" }, fontWeight = FontWeight.SemiBold)
                                if (c.role.isNotBlank()) Text(c.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (c.phone.isNotBlank()) Link(Icons.Default.Phone, c.phone) {
                            runCatching { links.openUri("tel:" + c.phone.filter { it.isDigit() || it == '+' }) }
                        }
                        if (c.email.isNotBlank()) Link(Icons.Default.Email, c.email) {
                            runCatching { links.openUri("mailto:" + c.email.trim()) }
                        }
                        if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun l(list: List<Store.Contact>?) = list.orEmpty()

@Composable
private fun Link(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Правка контакта — отдельным экраном, а не окном: в окне клавиатура закрывала «Готово» и
 * нижние поля (найдено проверкой на эмуляторе). «Готово» — в шапке, всегда на виду; поля
 * прокручиваются над клавиатурой; «Далее» на клавиатуре — к следующему полю, на последнем —
 * сохранить.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditor(initial: Store.Contact, isNew: Boolean, onDone: (Store.Contact) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew && initial.blank) first.requestFocus() }
    BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Отмена") } },
                title = { Text(if (isNew) "Новый контакт" else "Контакт") },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
                    TextButton(onClick = { onDone(c) }, enabled = !c.blank) { Text("Готово", fontWeight = FontWeight.SemiBold) }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("ФИО", c.name, KeyboardType.Text, KeyboardCapitalization.Words, Modifier.focusRequester(first)) { c = c.copy(name = it) }
            Field("Должность", c.role, KeyboardType.Text, KeyboardCapitalization.Sentences) { c = c.copy(role = it) }
            Field("Телефон", c.phone, KeyboardType.Phone) { c = c.copy(phone = it) }
            Field("Почта", c.email, KeyboardType.Email) { c = c.copy(email = it) }
            Field("Примечание", c.note, KeyboardType.Text, KeyboardCapitalization.Sentences, last = true,
                onLast = { if (!c.blank) onDone(c) }) { c = c.copy(note = it) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить контакт?") },
        text = { Text(c.name.ifBlank { c.phone }) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, caps: KeyboardCapitalization = KeyboardCapitalization.None,
                  modifier: Modifier = Modifier, last: Boolean = false, onLast: () -> Unit = {}, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = !last,
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = caps, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { onLast() }))
}

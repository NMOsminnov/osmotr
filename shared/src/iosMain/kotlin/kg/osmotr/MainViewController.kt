package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.window.ComposeUIViewController
import kg.osmotr.core.File
import kg.osmotr.core.Store
import kg.osmotr.ui.IosHost
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import kg.osmotr.ui.Screen
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Описи, присланные в приложение («Открыть в «Осмотр»»), — ждут, пока откроется экран. */
val incoming = mutableStateListOf<Picked>()

/** Присланный файл (Swift уже скопировал его к себе): в очередь загрузки описей. */
fun receiveFile(path: String, name: String) {
    val f = File(path)
    runCatching { incoming.add(Picked(name, f.readBytes())) }
    f.delete()
}

/**
 * С какого экрана начать — для проверки на симуляторе (`simctl launch … -screen inventory -path
 * «Склад»`): опись, папка, просмотр, поиск, дерево, контакты. Пути — от «Осмотров».
 */
private fun startScreens(): List<Screen> {
    val args = platform.Foundation.NSProcessInfo.processInfo.arguments.map { it.toString() }
    fun arg(name: String) = args.indexOf("-$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    // Самопроверка журнала (подпись и отпечатки на этом iPhone) — итог в системный журнал, его читает CI.
    if ("-selftest" in args) kg.osmotr.core.Platform.log(kg.osmotr.core.Journal.selfTest())
    val kind = arg("screen") ?: return emptyList()
    val path = arg("path")?.let { File(Store.root, it) } ?: Store.root
    return when (kind) {
        "folder" -> listOf(Screen.Browser(path))
        "inventory" -> listOf(Screen.Inventory(path, query = arg("query")))
        "view" -> listOf(Screen.Browser(path.parentFile!!), Screen.Viewer(path.parentFile!!, path))
        "search" -> listOf(Screen.Search(path, query = arg("query")))
        "tree" -> listOf(Screen.Tree(path))
        "contacts" -> listOf(Screen.Browser(path), Screen.Contacts(path))
        else -> emptyList()
    }
}

/**
 * Вход для iPhone (Swift-обёртка показывает этот контроллер). «Осмотры» — в папке приложения:
 * видна в «Файлах» («На iPhone → Осмотр») и через кабель на компьютере.
 */
fun MainViewController(): platform.UIKit.UIViewController {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    Store.init(File(docs), File(support))
    val start = startScreens()
    return ComposeUIViewController { OsmotrApp(IosHost, incoming, start) }
}

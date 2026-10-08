package kg.osmotr

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.window.ComposeUIViewController
import kg.osmotr.core.File
import kg.osmotr.core.Store
import kg.osmotr.ui.IosHost
import kg.osmotr.ui.OsmotrApp
import kg.osmotr.ui.Picked
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Описи, присланные в приложение («Открыть в «Осмотр»»), — ждут, пока откроется экран. */
val incoming = mutableStateListOf<Picked>()

/**
 * Вход для iPhone (Swift-обёртка показывает этот контроллер). «Осмотры» — в папке приложения:
 * видна в «Файлах» («На iPhone → Осмотр») и через кабель на компьютере.
 */
fun MainViewController() = ComposeUIViewController {
    OsmotrApp(IosHost, incoming)
}.also {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    Store.init(File(docs), File(support))
}

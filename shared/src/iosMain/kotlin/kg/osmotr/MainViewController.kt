package kg.osmotr

import androidx.compose.ui.window.ComposeUIViewController
import kg.osmotr.ui.App

/** Вход для iPhone: Swift-обёртка (iosApp) показывает этот контроллер. */
fun MainViewController() = ComposeUIViewController { App() }

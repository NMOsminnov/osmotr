import SwiftUI
import Shared

/// Обёртка для iPhone: весь интерфейс — общий, на Compose (shared/…/ui/App.kt).
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup { ComposeView().ignoresSafeArea(.all) }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

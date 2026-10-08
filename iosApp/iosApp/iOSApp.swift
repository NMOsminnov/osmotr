import SwiftUI
import Shared

/// Обёртка для iPhone: весь интерфейс — общий, на Compose (shared/…/ui/OsmotrApp.kt).
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                // «Открыть в «Осмотр»» / «Поделиться» из WhatsApp, «Файлов», почты: опись — в общий код.
                .onOpenURL { url in Incoming.shared.receive(url: url) }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// Присланный файл: скопировать к себе (доступ к чужому файлу — только пока открыт), отдать Kotlin.
final class Incoming {
    static let shared = Incoming()
    func receive(url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: url, to: copy)
            MainViewControllerKt.receiveFile(path: copy.path, name: url.lastPathComponent)
        } catch {
            NSLog("osmotr: не открыть %@: %@", url.path, error.localizedDescription)
        }
    }
}

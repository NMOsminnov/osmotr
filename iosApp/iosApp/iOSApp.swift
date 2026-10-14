import SwiftUI
import MetricKit
import CryptoKit
import Shared

/// Обёртка для iPhone: весь интерфейс — общий, на Compose (shared/…/ui/OsmotrApp.kt).
@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                // «Открыть в «Осмотр»» из «Файлов», почты: опись или фото — в общий код; «osmotr://incoming» —
                // расширение «Поделиться» положило фото во «Входящие».
                .onOpenURL { url in
                    if url.scheme == "osmotr" { SharedInbox.collect() } else { Incoming.shared.receive(url: url) }
                }
                // Вернулись в «Осмотр» — забрать присланное расширением, если ссылка не сработала.
                .onChange(of: phase) { p in if p == .active { SharedInbox.collect() } }
        }
    }
}

/// «Входящие» общей с расширением «Поделиться» папки (App Group «group.<идентификатор>», её
/// заводит установщик при подписи): каждое фото — в общий код (оттуда — «Куда положить») и прочь.
enum SharedInbox {
    static func collect() {
        guard let id = Bundle.main.bundleIdentifier,
              let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group." + id) else { return }
        let dir = root.appendingPathComponent("Входящие", isDirectory: true)
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for f in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            MainViewControllerKt.receiveFile(path: f.path, name: f.lastPathComponent)   // прочтёт и удалит
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()
        // Папки приложения готовит Kotlin (MainViewController) — отчёты системы слушаем после него.
        SystemReports.shared.start()
        return controller
    }
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

/// Отчёты системы о приложении (MetricKit): падения и зависания, которые Kotlin не поймает (сбой
/// памяти, убийство системой, сторож главного потока), расход памяти и батареи, время запуска.
/// iOS отдаёт их раз в сутки и после сбоя — при следующем запуске; кладём файлами в папку
/// телеметрии, «Диагностика» забирает их в архив. Личного в них нет: стеки, модель, версия.
final class SystemReports: NSObject, MXMetricManagerSubscriber {
    static let shared = SystemReports()
    private var folder: URL { URL(fileURLWithPath: MainViewControllerKt.telemetryDir()).appendingPathComponent("metrickit") }

    private var started = false

    func start() {
        guard !started else { return }
        started = true
        MXMetricManager.shared.add(self)
        // Отчёты, пришедшие, пока приложение не слушало (iOS 14+ хранит прошлые).
        save(MXMetricManager.shared.pastDiagnosticPayloads.map { $0.jsonRepresentation() }, kind: "diag")
        save(MXMetricManager.shared.pastPayloads.map { $0.jsonRepresentation() }, kind: "metrics")
    }

    func didReceive(_ payloads: [MXMetricPayload]) { save(payloads.map { $0.jsonRepresentation() }, kind: "metrics") }
    func didReceive(_ payloads: [MXDiagnosticPayload]) { save(payloads.map { $0.jsonRepresentation() }, kind: "diag") }

    private func save(_ items: [Data], kind: String) {
        guard !items.isEmpty else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Имя — по отпечатку содержимого: прошлые отчёты iOS отдаёт при каждом запуске, второй раз не пишем.
        for data in items {
            let hash = SHA256.hash(data: data).prefix(8).map { String(format: "%02x", $0) }.joined()
            let file = folder.appendingPathComponent("\(kind)-\(hash).json")
            if !FileManager.default.fileExists(atPath: file.path) { try? data.write(to: file) }
        }
        // Не копить без конца: хватит последних тридцати.
        if let all = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil), all.count > 30 {
            let dated = all.map { ($0, (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
            dated.sorted { $0.1 < $1.1 }.prefix(all.count - 30).forEach { try? FileManager.default.removeItem(at: $0.0) }
        }
    }
}

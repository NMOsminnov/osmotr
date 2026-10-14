import UIKit
import UniformTypeIdentifiers

/// «Поделиться» → «Осмотр» (WhatsApp, «Фото», любое приложение): фото — во «Входящие» общей с
/// «Осмотром» папки (App Group «group.<идентификатор приложения>» — её заводит установщик при подписи,
/// isideload), затем открыть «Осмотр»: там поиск «Куда положить» (автор, 14.10.2026: «добавление фото
/// из WhatsApp через поделиться»; «Вот теперь давай уже и под iOS изменения доделаем»).
final class ShareViewController: UIViewController {
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.text = "Передаём фото в «Осмотр»…"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.font = .preferredFont(forTextStyle: .headline)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
        Task { await take() }
    }

    /// Папка «Входящие» общей группы; нет группы (поставлено без неё) — nil.
    static func inbox() -> URL? {
        guard let id = Bundle.main.bundleIdentifier else { return nil }
        // Идентификатор расширения — «<приложение>.share»: группа — по приложению.
        let app = id.hasSuffix(".share") ? String(id.dropLast(".share".count)) : id
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group." + app) else { return nil }
        let dir = root.appendingPathComponent("Входящие", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func take() async {
        guard let inbox = Self.inbox() else {
            finish("Не получилось: «Осмотр» поставлен без общей папки. Переустановите его установщиком — и попробуйте снова.", open: false)
            return
        }
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
            .filter { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }
        var saved = 0
        for p in providers {
            if let data = await load(p) {
                let name = UUID().uuidString + "." + ext(of: data)
                if (try? data.write(to: inbox.appendingPathComponent(name))) != nil { saved += 1 }
            }
        }
        if saved == 0 { finish("Фото не нашлось — попробуйте ещё раз.", open: false); return }
        finish("Фото в «Осмотре»: \(saved). Выберите, куда положить.", open: true)
    }

    /// Фото — файлом, данными или картинкой (мессенджеры отдают по-разному).
    private func load(_ p: NSItemProvider) async -> Data? {
        await withCheckedContinuation { done in
            p.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { item, _ in
                switch item {
                case let url as URL: done.resume(returning: try? Data(contentsOf: url))
                case let data as Data: done.resume(returning: data)
                case let image as UIImage: done.resume(returning: image.jpegData(compressionQuality: 0.92))
                default: done.resume(returning: nil)
                }
            }
        }
    }

    private func ext(of d: Data) -> String {
        let b = [UInt8](d.prefix(12))
        if b.count >= 4, b[0] == 0x89, b[1] == 0x50 { return "png" }
        if b.count >= 12, b[8] == 0x57, b[9] == 0x45 { return "webp" }
        if b.count >= 8, b[4] == 0x66, b[5] == 0x74 { return "heic" }
        return "jpg"
    }

    private func finish(_ text: String, open: Bool) {
        DispatchQueue.main.async {
            self.label.text = text
            if open { self.openApp() }
            DispatchQueue.main.asyncAfter(deadline: .now() + (open ? 0.8 : 2.5)) {
                self.extensionContext?.completeRequest(returningItems: nil)
            }
        }
    }

    /// Открыть «Осмотр»: у расширения своего способа нет — через приложение в цепочке ответчиков
    /// (openURL:options:completionHandler:, так делают расширения «Поделиться» и в iOS 18+).
    private func openApp() {
        guard let url = URL(string: "osmotr://incoming") else { return }
        let sel = NSSelectorFromString("openURL:options:completionHandler:")
        var r: UIResponder? = self
        while let x = r {
            if x.responds(to: sel), let app = x as? NSObject, NSStringFromClass(type(of: app)).contains("Application") {
                typealias Open = @convention(c) (AnyObject, Selector, URL, NSDictionary, ((Bool) -> Void)?) -> Void
                let f = unsafeBitCast(app.method(for: sel), to: Open.self)
                f(app, sel, url, NSDictionary(), nil)
                return
            }
            r = x.next
        }
    }
}

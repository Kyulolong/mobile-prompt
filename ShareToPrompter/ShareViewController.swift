import UIKit
import UniformTypeIdentifiers

/// Receives text from the system share sheet and drops it into the App Group
/// inbox; the main app turns inbox entries into library scripts on launch /
/// foreground. No UI — the sheet closes as soon as the text is stored.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        processAndFinish()
    }

    private func processAndFinish() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []
        let group = DispatchGroup()
        var texts: [String] = []
        let lock = NSLock()

        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) { item, _ in
                var text: String?
                if let s = item as? String {
                    text = s
                } else if let d = item as? Data, let s = String(data: d, encoding: .utf8) {
                    text = s
                } else if let url = item as? URL, url.isFileURL,
                          let s = try? String(contentsOf: url, encoding: .utf8) {
                    text = s
                }
                if let text {
                    lock.lock(); texts.append(text); lock.unlock()
                }
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            if !texts.isEmpty, let shared = UserDefaults(suiteName: "group.com.teaminpact.mobileprompt") {
                var inbox = shared.stringArray(forKey: "sharedInbox") ?? []
                inbox.append(contentsOf: texts)
                shared.set(inbox, forKey: "sharedInbox")
            }
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}

import Foundation
import Combine

struct SavedScript: Identifiable, Codable, Equatable {
    var id = UUID()
    var text: String
    var updatedAt: Date

    /// Display title: first non-empty line, truncated.
    var title: String {
        let line = text.split(separator: "\n")
            .map { ScriptMarkup.stripMarkers(String($0)).trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty } ?? ""
        if line.isEmpty { return AppSettings.tr("새 대본") }
        return line.count > 28 ? String(line.prefix(28)) + "…" : line
    }
}

/// A script that just arrived through the `voiceprompter://` handoff; the
/// editor for `scriptID` consumes it once (autostart + return link).
struct PendingHandoff {
    let scriptID: UUID
    let autostart: Bool
    let returnURL: URL?
}

/// Script library persisted as JSON in Documents. Also drains the share-
/// extension inbox (App Group) into new scripts.
final class ScriptStore: ObservableObject {
    @Published private(set) var scripts: [SavedScript] = []
    @Published var pendingHandoff: PendingHandoff?

    static let appGroupID = "group.com.teaminpact.mobileprompt"
    static let inboxKey = "sharedInbox"

    private let fileURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("scripts.json")
    }()

    init() {
        load()
        migrateLegacyScriptIfNeeded()
        drainSharedInbox()
    }

    // MARK: CRUD

    @discardableResult
    func add(text: String = "") -> SavedScript {
        let s = SavedScript(text: text, updatedAt: Date())
        scripts.insert(s, at: 0)
        save()
        return s
    }

    func update(id: UUID, text: String) {
        guard let i = scripts.firstIndex(where: { $0.id == id }) else { return }
        guard scripts[i].text != text else { return }
        scripts[i].text = text
        scripts[i].updatedAt = Date()
        save()
    }

    func delete(ids: Set<UUID>) {
        scripts.removeAll { ids.contains($0.id) }
        save()
    }

    /// A fresh copy at the top of the library.
    @discardableResult
    func duplicate(id: UUID) -> SavedScript? {
        guard let source = script(id: id) else { return nil }
        return add(text: source.text)
    }

    /// Script text from a file: UTF-8 (with or without BOM), UTF-16 with a
    /// BOM, else EUC-KR for old Korean .txt files; as a last resort UTF-8 with
    /// replacement characters, which beats refusing the file.
    static func decodeText(_ data: Data) -> String? {
        let bytes = [UInt8](data.prefix(4))
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { return String(data: data.dropFirst(3), encoding: .utf8) }
        if bytes.starts(with: [0xFE, 0xFF]) { return String(data: data.dropFirst(2), encoding: .utf16BigEndian) }
        if bytes.starts(with: [0xFF, 0xFE]) { return String(data: data.dropFirst(2), encoding: .utf16LittleEndian) }
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        let eucKR = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.EUC_KR.rawValue)))
        if let korean = String(data: data, encoding: eucKR) { return korean }
        return String(decoding: data, as: UTF8.self)
    }

    func script(id: UUID) -> SavedScript? {
        scripts.first { $0.id == id }
    }

    // MARK: persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let list = try? JSONDecoder().decode([SavedScript].self, from: data) else { return }
        scripts = list
    }

    private func save() {
        if let data = try? JSONEncoder().encode(scripts) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    /// v1.0 kept a single script in UserDefaults — carry it into the library once.
    private func migrateLegacyScriptIfNeeded() {
        let d = UserDefaults.standard
        guard !d.bool(forKey: "libraryMigrated") else { return }
        d.set(true, forKey: "libraryMigrated")
        let old = d.string(forKey: "scriptText") ?? ""
        if !old.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            add(text: old)
        }
    }

    /// Texts shared via the share extension land in the App Group; turn each
    /// into a script. Call on launch and on foreground.
    func drainSharedInbox() {
        guard let shared = UserDefaults(suiteName: Self.appGroupID) else { return }
        guard let inbox = shared.stringArray(forKey: Self.inboxKey), !inbox.isEmpty else { return }
        shared.removeObject(forKey: Self.inboxKey)
        for text in inbox.reversed() where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            add(text: text)
        }
    }
}

import Foundation
import Combine

struct SavedScript: Identifiable, Codable, Equatable {
    var id = UUID()
    var text: String
    var updatedAt: Date

    /// Display title: first non-empty line, truncated.
    var title: String {
        let line = text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty } ?? ""
        if line.isEmpty { return AppSettings.tr("새 대본") }
        return line.count > 28 ? String(line.prefix(28)) + "…" : line
    }
}

/// Script library persisted as JSON in Documents. Also drains the share-
/// extension inbox (App Group) into new scripts.
final class ScriptStore: ObservableObject {
    @Published private(set) var scripts: [SavedScript] = []

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

    func delete(at offsets: IndexSet) {
        scripts.remove(atOffsets: offsets)
        save()
    }

    func delete(ids: Set<UUID>) {
        scripts.removeAll { ids.contains($0.id) }
        save()
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

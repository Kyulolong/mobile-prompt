import SwiftUI
import UniformTypeIdentifiers

/// Script library — the app's home screen.
struct ContentView: View {
    @EnvironmentObject private var store: ScriptStore
    // Observed so the home screen re-renders (title, row titles) the moment
    // the app language changes in settings.
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @State private var path = NavigationPath()
    @State private var showSettings = false
    @State private var selection = Set<UUID>()
    @State private var editMode: EditMode = .inactive
    @State private var query = ""
    @State private var showImporter = false
    @State private var importFailed = false

    /// Scripts are small, so anything past this is refused, not truncated.
    private static let importLimit = 1_048_576
    private static let importTypes: [UTType] = [.plainText, .text, UTType("net.daringfireball.markdown")].compactMap { $0 }

    private var filteredScripts: [SavedScript] {
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? store.scripts : store.scripts.filter { $0.text.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.scripts.isEmpty {
                    emptyState
                } else {
                    // Bind selection ONLY in edit mode — a permanently attached
                    // Set-selection binding makes normal taps select (and
                    // swallow NavigationLink) instead of navigating.
                    List(selection: editMode == .active ? $selection : nil) {
                        ForEach(filteredScripts) { script in
                            NavigationLink(value: script.id) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(script.title)
                                        .font(.headline)
                                        .lineLimit(1)
                                    Text(script.updatedAt, format: .dateTime.month().day().hour().minute())
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .padding(.vertical, 4)
                            }
                            .tag(script.id)
                            .swipeActions(edge: .leading) {
                                Button {
                                    store.duplicate(id: script.id)
                                } label: {
                                    Label("복제", systemImage: "doc.on.doc")
                                }
                                .tint(.blue)
                            }
                            .contextMenu {
                                Button {
                                    store.duplicate(id: script.id)
                                } label: {
                                    Label("복제", systemImage: "doc.on.doc")
                                }
                                Button {
                                    withAnimation {
                                        selection = [script.id]
                                        editMode = .active
                                    }
                                } label: {
                                    Label("여러 개 선택", systemImage: "checkmark.circle")
                                }
                                Button(role: .destructive) {
                                    store.delete(ids: [script.id])
                                } label: {
                                    Label("삭제", systemImage: "trash")
                                }
                            }
                        }
                        .onDelete { offsets in
                            // Offsets index the filtered list, so map them to ids.
                            let visible = filteredScripts
                            store.delete(ids: Set(offsets.compactMap { $0 < visible.count ? visible[$0].id : nil }))
                        }
                    }
                    .environment(\.editMode, $editMode)
                    .searchable(text: $query, prompt: Text("대본 검색"))
                }
            }
            .navigationTitle(AppSettings.tr("보이스 프롬프터"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if editMode == .active {
                        Button("완료") {
                            withAnimation {
                                editMode = .inactive
                                selection = []
                            }
                        }
                    } else {
                        Button {
                            showSettings = true
                        } label: {
                            Image(systemName: "gearshape")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if editMode == .active {
                        Button(role: .destructive) {
                            withAnimation {
                                store.delete(ids: selection)
                                selection = []
                                editMode = .inactive
                            }
                        } label: {
                            Text("삭제 (\(selection.count))")
                                .foregroundStyle(selection.isEmpty ? Color.secondary : .red)
                        }
                        .disabled(selection.isEmpty)
                    } else {
                        // Tap: new script. Long-press: more ways to get one.
                        Menu {
                            Button {
                                newScript()
                            } label: {
                                Label("새 대본", systemImage: "square.and.pencil")
                            }
                            Button {
                                showImporter = true
                            } label: {
                                Label("텍스트 파일 가져오기…", systemImage: "doc.badge.plus")
                            }
                        } label: {
                            Image(systemName: "plus")
                        } primaryAction: {
                            newScript()
                        }
                    }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: Self.importTypes) { result in
                importFile(result)
            }
            .alert("파일을 읽지 못했어요", isPresented: $importFailed) {
                Button("확인") {}
            }
            .sheet(isPresented: $showSettings) { SettingsSheet() }
            .navigationDestination(for: UUID.self) { id in
                ScriptEditorView(scriptID: id)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { store.drainSharedInbox() }
        }
        // Perplz (or any app) hands a script over via voiceprompter://script?s=…
        .onOpenURL { url in
            guard let request = HandoffRequest.parse(url) else { return }
            let script = store.add(text: request.text)
            store.pendingHandoff = PendingHandoff(scriptID: script.id, autostart: request.autostart, returnURL: request.returnURL)
            withAnimation {
                editMode = .inactive
                selection = []
                path = NavigationPath()
                path.append(script.id)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 18) {
            Image(systemName: "text.append")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("아직 대본이 없어요")
                .font(.headline)
            Text("새 대본을 만들거나, 다른 앱에서\n텍스트를 공유해 가져올 수 있어요")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button {
                newScript()
            } label: {
                Label("새 대본", systemImage: "plus")
                    .frame(minWidth: 160, minHeight: 46)
            }
            .buttonStyle(.borderedProminent)
            Button {
                showImporter = true
            } label: {
                Label("텍스트 파일 가져오기…", systemImage: "doc.badge.plus")
                    .frame(minWidth: 160, minHeight: 40)
            }
            .buttonStyle(.bordered)
        }
    }

    private func newScript() {
        let s = store.add()
        path.append(s.id)
    }

    /// A picked .txt/.md becomes a new script and opens in the editor.
    private func importFile(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), data.count <= Self.importLimit,
              let text = ScriptStore.decodeText(data),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            importFailed = true
            return
        }
        let s = store.add(text: text)
        path.append(s.id)
    }
}

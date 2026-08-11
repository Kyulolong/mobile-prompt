import SwiftUI

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
                        ForEach(store.scripts) { script in
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
                            .contextMenu {
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
                        .onDelete { store.delete(at: $0) }
                    }
                    .environment(\.editMode, $editMode)
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
                        Button {
                            newScript()
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsSheet() }
            .navigationDestination(for: UUID.self) { id in
                ScriptEditorView(scriptID: id)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { store.drainSharedInbox() }
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
        }
    }

    private func newScript() {
        let s = store.add()
        path.append(s.id)
    }
}

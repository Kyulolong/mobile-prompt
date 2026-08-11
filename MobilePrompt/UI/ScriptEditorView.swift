import SwiftUI

/// Edits one script from the library and launches the prompter.
struct ScriptEditorView: View {
    @EnvironmentObject private var store: ScriptStore
    // Observed so the title re-localizes immediately on app-language change.
    @EnvironmentObject private var settings: AppSettings
    let scriptID: UUID
    @State private var showPrompter = false
    @FocusState private var editorFocused: Bool

    private var textBinding: Binding<String> {
        Binding(
            get: { store.script(id: scriptID)?.text ?? "" },
            set: { store.update(id: scriptID, text: $0) }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            TextEditor(text: textBinding)
                .focused($editorFocused)
                .font(.system(size: 17))
                .lineSpacing(5)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, 12)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 16)
                .overlay(alignment: .topLeading) {
                    if textBinding.wrappedValue.isEmpty {
                        Text("여기에 대본을 붙여넣으세요…")
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 34)
                            .padding(.top, 8)
                            .allowsHitTesting(false)
                    }
                }

            HStack(spacing: 12) {
                Button {
                    textBinding.wrappedValue = AppSettings.sampleScript
                } label: {
                    Label("샘플 넣기", systemImage: "doc.text")
                        .frame(maxWidth: .infinity, minHeight: 46)
                }
                .buttonStyle(.bordered)

                Button(role: .destructive) {
                    textBinding.wrappedValue = ""
                } label: {
                    Image(systemName: "trash")
                        .frame(width: 46, height: 46)
                }
                .buttonStyle(.bordered)
                .disabled(textBinding.wrappedValue.isEmpty)

                Button {
                    editorFocused = false
                    showPrompter = true
                } label: {
                    Label("시작", systemImage: "video.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 46)
                }
                .buttonStyle(.borderedProminent)
                .disabled(textBinding.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(16)
        }
        .navigationTitle(store.script(id: scriptID)?.title ?? AppSettings.tr("대본"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if editorFocused {
                    Button("완료") { editorFocused = false }
                }
            }
        }
        .fullScreenCover(isPresented: $showPrompter) {
            PrompterView(script: textBinding.wrappedValue)
        }
    }
}

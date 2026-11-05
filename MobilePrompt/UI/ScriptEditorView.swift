import SwiftUI

/// Edits one script from the library and launches the prompter.
struct ScriptEditorView: View {
    @EnvironmentObject private var store: ScriptStore
    // Observed so the title re-localizes immediately on app-language change.
    @EnvironmentObject private var settings: AppSettings
    let scriptID: UUID
    @State private var showPrompter = false
    @State private var showMarkupHelp = false
    /// Where "Back to Perplz" goes after a recording, when this script came by handoff.
    @State private var returnURL: URL?
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
                HStack(spacing: 14) {
                    Button {
                        showMarkupHelp = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .accessibilityLabel(Text("대본 표시 문법"))
                    if editorFocused {
                        Button("완료") { editorFocused = false }
                    }
                }
            }
        }
        .sheet(isPresented: $showMarkupHelp) {
            MarkupHelpSheet()
                .presentationDetents([.medium, .large])
        }
        .fullScreenCover(isPresented: $showPrompter) {
            PrompterView(script: textBinding.wrappedValue, returnURL: returnURL)
        }
        .onAppear {
            guard let pending = store.pendingHandoff, pending.scriptID == scriptID else { return }
            store.pendingHandoff = nil
            returnURL = pending.returnURL
            if pending.autostart { showPrompter = true }
        }
    }
}

/// The two bits of markup a script understands (see ScriptMarkup).
private struct MarkupHelpSheet: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    row(code: "**강조**", title: "굵게", body: "별표 두 개로 감싸면 굵게 보여요. 읽는 단어예요.")
                    row(code: "[쉬고]", title: "메모", body: "대괄호 안은 흐리게 보이고, 읽지 않아요. 쉬는 자리나 메모에 쓰세요.")
                } footer: {
                    Text("녹화 자막(SRT)에는 표시 기호가 들어가지 않아요.")
                }
            }
            .navigationTitle("대본 표시 문법")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func row(code: String, title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Text(verbatim: code)
                    .font(.system(.body, design: .monospaced).weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 6))
                Text(title).font(.headline)
            }
            Text(body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

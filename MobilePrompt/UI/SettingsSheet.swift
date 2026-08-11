import SwiftUI

/// Live-adjustable prompter settings (also reachable mid-session).
/// `compact`: half-height only, with the screen behind still visible and
/// interactive — used inside the prompter so changes can be previewed live.
struct SettingsSheet: View {
    var compact = false
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("글자") {
                    HStack {
                        Text("크기")
                        Slider(value: $settings.fontSize, in: 18...56, step: 1)
                        Text("\(Int(settings.fontSize))")
                            .monospacedDigit()
                            .frame(width: 32, alignment: .trailing)
                    }
                    Picker("하이라이트 색", selection: $settings.highlightStyle) {
                        Text("노랑").tag("yellow")
                        Text("초록").tag("green")
                        Text("하늘").tag("cyan")
                        Text("분홍").tag("pink")
                        Text("없음").tag("none")
                    }
                    Toggle("거울 모드 (좌우 반전)", isOn: $settings.mirrored)
                }
                Section("화면") {
                    HStack {
                        Text("배경 어둡기")
                        Slider(value: $settings.scrimOpacity, in: 0...0.7)
                    }
                    HStack {
                        Text("읽는 줄 위치")
                        Slider(value: $settings.readingLineFrac, in: 0.15...0.6)
                    }
                }
                Section {
                    HStack {
                        Text("좌우 폭")
                        Slider(value: $settings.portraitWidthFrac, in: 0.4...1.0)
                        Text("\(Int(settings.portraitWidthFrac * 100))%")
                            .monospacedDigit()
                            .frame(width: 48, alignment: .trailing)
                    }
                    HStack {
                        Text("위 여백")
                        Slider(value: $settings.portraitTopFrac, in: 0...0.35)
                        Text("\(Int(settings.portraitTopFrac * 100))%")
                            .monospacedDigit()
                            .frame(width: 48, alignment: .trailing)
                    }
                    HStack {
                        Text("아래 여백")
                        Slider(value: $settings.portraitBottomFrac, in: 0...0.35)
                        Text("\(Int(settings.portraitBottomFrac * 100))%")
                            .monospacedDigit()
                            .frame(width: 48, alignment: .trailing)
                    }
                } header: {
                    Text("글상자 (세로 모드)")
                } footer: {
                    Text("가로 모드에서는 카메라 쪽에 폭 55%로 자동 배치돼요.")
                }
                Section {
                    Picker("앱 언어", selection: $settings.appLanguage) {
                        Text("시스템 설정").tag("system")
                        Text(verbatim: "한국어").tag("ko")
                        Text(verbatim: "English").tag("en")
                    }
                } header: {
                    Text("언어")
                } footer: {
                    Text("인식 언어는 대본에서 자동으로 감지되고, 대본 속 다른 언어 문장도 자동으로 전환돼요.")
                }
                Section("자동 스크롤") {
                    HStack {
                        Text("속도")
                        Slider(value: $settings.autoSpeed, in: 10...150)
                        Text("\(Int(settings.autoSpeed))")
                            .monospacedDigit()
                            .frame(width: 36, alignment: .trailing)
                    }
                }
            }
            .navigationTitle(AppSettings.tr("설정"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .presentationDetents(compact ? [.medium] : [.medium, .large])
        .presentationBackgroundInteraction(compact ? .enabled(upThrough: .medium) : .automatic)
    }
}

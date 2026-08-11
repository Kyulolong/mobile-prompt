import Foundation
import Combine

/// User-tunable prompter settings, persisted in UserDefaults.
/// (@Published + didSet instead of @AppStorage so changes publish from an
/// ObservableObject shared across screens.)
final class AppSettings: ObservableObject {
    @Published var scriptText: String { didSet { save(scriptText, "scriptText") } }
    @Published var fontSize: Double { didSet { save(fontSize, "fontSize") } }
    @Published var readingLineFrac: Double { didSet { save(readingLineFrac, "readingLineFrac") } }
    @Published var scrimOpacity: Double { didSet { save(scrimOpacity, "scrimOpacity") } }
    @Published var mirrored: Bool { didSet { save(mirrored, "mirrored") } }
    @Published var autoSpeed: Double { didSet { save(autoSpeed, "autoSpeed") } }
    @Published var voiceMode: Bool { didSet { save(voiceMode, "voiceMode") } }
    /// "yellow" | "green" | "cyan" | "pink" | "none"
    @Published var highlightStyle: String { didSet { save(highlightStyle, "highlightStyle") } }
    /// Locale for formatting; kept in sync with `appLanguage`.
    static var textLocale: Locale = .autoupdatingCurrent

    /// Bundle whose Localizable table matches the chosen app language.
    /// String(localized:locale:) does NOT pick the translation by locale (the
    /// locale only affects formatting), so code-built strings must look up
    /// through the right .lproj bundle to switch language without a relaunch.
    static var textBundle: Bundle = .main

    /// Localized string for `key` in the currently chosen app language.
    static func tr(_ key: String) -> String {
        textBundle.localizedString(forKey: key, value: key, table: nil)
    }

    /// UI language: "system" | "ko" | "en" — applies immediately.
    @Published var appLanguage: String {
        didSet {
            save(appLanguage, "appLanguage")
            applyAppLanguage()
        }
    }

    /// Locale for the SwiftUI environment.
    var uiLocale: Locale {
        appLanguage == "system" ? .autoupdatingCurrent : Locale(identifier: appLanguage)
    }

    private func applyAppLanguage() {
        if appLanguage == "system" {
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
            Self.textLocale = .autoupdatingCurrent
            Self.textBundle = .main
        } else {
            // Persist for the next cold launch too (Info.plist strings etc.).
            UserDefaults.standard.set([appLanguage], forKey: "AppleLanguages")
            Self.textLocale = Locale(identifier: appLanguage)
            if let path = Bundle.main.path(forResource: appLanguage, ofType: "lproj"),
               let bundle = Bundle(path: path) {
                Self.textBundle = bundle
            } else {
                Self.textBundle = .main
            }
        }
    }
    /// Recognition/alignment language: "auto" (detect from script) | "ko" | "en".
    @Published var recogLanguage: String { didSet { save(recogLanguage, "recogLanguage") } }
    // Portrait-mode script box: width fraction and top/bottom margins.
    @Published var portraitWidthFrac: Double { didSet { save(portraitWidthFrac, "portraitWidthFrac") } }
    @Published var portraitTopFrac: Double { didSet { save(portraitTopFrac, "portraitTopFrac") } }
    @Published var portraitBottomFrac: Double { didSet { save(portraitBottomFrac, "portraitBottomFrac") } }

    init() {
        let d = UserDefaults.standard
        scriptText = d.string(forKey: "scriptText") ?? ""
        fontSize = d.object(forKey: "fontSize") as? Double ?? 30
        readingLineFrac = d.object(forKey: "readingLineFrac") as? Double ?? 0.38
        scrimOpacity = d.object(forKey: "scrimOpacity") as? Double ?? 0.35
        mirrored = d.bool(forKey: "mirrored")
        autoSpeed = d.object(forKey: "autoSpeed") as? Double ?? 45
        voiceMode = d.object(forKey: "voiceMode") as? Bool ?? true
        highlightStyle = d.string(forKey: "highlightStyle") ?? "green"
        appLanguage = d.string(forKey: "appLanguage") ?? "system"
        recogLanguage = d.string(forKey: "recogLanguage") ?? "auto"
        portraitWidthFrac = d.object(forKey: "portraitWidthFrac") as? Double ?? 1.0
        portraitTopFrac = d.object(forKey: "portraitTopFrac") as? Double ?? 0
        portraitBottomFrac = d.object(forKey: "portraitBottomFrac") as? Double ?? 0
        applyAppLanguage()
    }

    private func save(_ value: Any, _ key: String) {
        UserDefaults.standard.set(value, forKey: key)
    }

    static var sampleScript: String {
        Locale.current.language.languageCode?.identifier == "ko" ? sampleScriptKo : sampleScriptEn
    }

    static let sampleScriptEn = """
    Hello everyone, today I want to show you a teleprompter I built myself.

    Most prompter apps out there scroll the text at a fixed speed. But the moment you speak a little faster or slower, the screen drifts away from you.

    So I changed the approach. Instead of pushing the text at a constant pace, this app listens to my voice and follows exactly where I am reading, in real time.

    If I pause for a moment, the screen simply waits. If I ad-lib a little, it doesn't lose my place. When I come back to the script, it picks up right where I am.

    Numbers are no problem either. Things like 2025 or 30% are matched just fine, however you say them.

    Best of all, it's completely free. It runs on your phone without internet, no payments, no API keys.

    If the screen has been scrolling smoothly at your pace while you read this — it works. That's it for today. Don't forget to like and subscribe Kyulolong!
    """

    static let sampleScriptKo = """
    안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.

    시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요. 특히 한국어는 인식이 잘 안 되는 경우가 많았고요.

    그래서 생각을 바꿨습니다. 글자를 일정한 속도로 미는 게 아니라, 제 목소리를 듣고 지금 어디를 읽고 있는지 실시간으로 따라오게 만든 거예요.

    잠깐 멈춰도 화면은 그대로 기다려 주고요. 중간에 애드립을 살짝 넣어도 위치를 잃지 않아요. 다시 대본으로 돌아오면 자연스럽게 이어서 따라옵니다.

    숫자도 문제없어요. 예를 들어 2025년, 30퍼센트, 이런 표현도 그대로 맞춰서 넘어갑니다.

    무엇보다 전부 무료예요. 인터넷 없이 폰에서도 돌아가고, 별도의 결제나 API 키도 필요 없습니다.

    여기까지 읽는 동안 화면이 제 속도에 맞춰 부드럽게 내려왔다면, 성공입니다. 오늘 영상은 여기까지고요, 다음에 더 유용한 걸로 찾아올게요. 규로롱 구독과 좋아요 잊지 마세요!
    """
}

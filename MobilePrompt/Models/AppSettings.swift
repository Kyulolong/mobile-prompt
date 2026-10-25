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
    Hi, I'm Hana Kim, and I was the front-end developer on Team MealMate in Perplz cohort 3. Here is the project we built in 8 weeks, in about one minute.

    The problem was simple. Students who don't want to eat alone have no easy way to find someone heading to the same cafeteria at the same time. In our survey, 78 percent of 212 students said they eat alone three or more times a week.

    So we built an app that matches meal buddies by timetable and location. I built the matching screen and chat in React Native, and worked in weekly sprints with two designers and one back-end engineer.

    The results beat our expectations. 540 people signed up during a 3-week beta, and 61 percent of requests ended in a match. The biggest lesson: a single sentence on the first screen doubled our conversion rate, more than any feature did.

    In my next project, I want to bring that habit of deciding with data. Thanks for watching.
    """

    static let sampleScriptKo = """
    안녕하세요, 저는 퍼플즈 3기 '밥친구' 프로젝트에서 프론트엔드 개발을 맡았던 김하늘입니다. 오늘은 저희 팀이 8주 동안 만든 서비스를 1분 안에 소개해 드릴게요.

    저희가 풀고 싶었던 문제는 간단했어요. 혼자 밥 먹기 싫은 대학생이 같은 시간에 같은 식당에 갈 사람을 찾기가 너무 어렵다는 거였죠. 설문에 답한 212명 중 78퍼센트가 일주일에 세 번 이상 혼자 먹는다고 했습니다.

    그래서 저희는 시간표와 위치를 기준으로 식사 메이트를 매칭하는 앱을 만들었어요. 저는 React Native로 매칭 화면과 채팅을 구현했고, 디자이너 두 분, 백엔드 한 분과 함께 매주 스프린트를 돌렸습니다.

    결과는 기대 이상이었어요. 베타 3주 동안 540명이 가입했고, 매칭 성공률은 61퍼센트였습니다. 가장 큰 배움은 기능보다 첫 화면의 문장 하나가 전환율을 두 배로 바꿨다는 사실이었어요.

    다음 프로젝트에서는 이 경험을 살려 데이터로 결정하는 팀을 만들고 싶습니다. 들어 주셔서 감사합니다.
    """
}

import Foundation
import JavaScriptCore

struct DisplayToken: Decodable, Equatable {
    let raw: String
    let breakBefore: Bool
}

struct AlignUpdate: Decodable {
    let token: Int
    let moved: Bool
    let lost: Bool
    let confidence: Double
    let tokensPerSec: Double
}

/// Runs the web app's alignment engine (jamo/script/align.ts, bundled verbatim
/// into engine.js) inside JavaScriptCore. All heavy structures stay JS-side;
/// Swift exchanges small JSON payloads. Main-thread only.
final class AlignEngine {
    private let context: JSContext
    private let api: JSValue

    init?() {
        guard let url = Bundle.main.url(forResource: "engine", withExtension: "js"),
              let source = try? String(contentsOf: url, encoding: .utf8),
              let ctx = JSContext() else { return nil }
        ctx.exceptionHandler = { _, exception in
            print("[AlignEngine] JS exception: \(exception?.toString() ?? "?")")
        }
        ctx.evaluateScript(source)
        guard let api = ctx.objectForKeyedSubscript("PromptEngine"), !api.isUndefined else { return nil }
        self.context = ctx
        self.api = api
    }

    /// Detects the matching language from the script text.
    static func language(for script: String) -> String {
        var hangul = 0, letters = 0
        for scalar in script.unicodeScalars {
            if (0xAC00...0xD7A3).contains(scalar.value) || (0x1100...0x11FF).contains(scalar.value) {
                hangul += 1; letters += 1
            } else if CharacterSet.letters.contains(scalar) {
                letters += 1
            }
        }
        guard letters > 0 else { return "ko" }
        return Double(hangul) / Double(letters) > 0.15 ? "ko" : "en"
    }

    /// `config`: Partial AlignConfig overrides merged over the engine defaults.
    func load(script: String, config: [String: Double] = [:], lang: String = "ko") -> [DisplayToken] {
        let cfgJson = (try? JSONSerialization.data(withJSONObject: config))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        guard let json = api.invokeMethod("load", withArguments: [script, cfgJson, lang])?.toString(),
              let data = json.data(using: .utf8),
              let tokens = try? JSONDecoder().decode([DisplayToken].self, from: data) else { return [] }
        return tokens
    }

    /// `nowMs` matches the web engine's performance.now() milliseconds.
    func push(words: [String], nowMs: Double) -> AlignUpdate? {
        guard let wordsData = try? JSONEncoder().encode(words),
              let wordsJson = String(data: wordsData, encoding: .utf8),
              let json = api.invokeMethod("push", withArguments: [wordsJson, nowMs])?.toString(),
              json != "null",
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(AlignUpdate.self, from: data)
    }

    func seek(token: Int) {
        api.invokeMethod("seek", withArguments: [token])
    }

    func reset(startToken: Int = 0) {
        api.invokeMethod("reset", withArguments: [startToken])
    }
}

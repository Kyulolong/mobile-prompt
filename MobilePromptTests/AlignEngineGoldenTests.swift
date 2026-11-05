import XCTest
@testable import MobilePrompt

/// Replays outputs recorded from the TypeScript engine against the
/// JavaScriptCore bridge. The Android port replays the same file
/// (android/app/src/test/.../EngineGoldenTest.kt), so the two apps are pinned
/// to one behaviour. What this covers on iOS is the bridge itself: JSON
/// marshaling, config merging, language mapping, seek/reset wiring.
final class AlignEngineGoldenTests: XCTestCase {
    struct Golden: Decodable {
        struct Token: Decodable { let raw: String; let breakBefore: Bool }
        struct Script: Decodable { let lang: String; let text: String; let tokens: [Token] }
        struct Result: Decodable {
            let token: Int; let moved: Bool; let lost: Bool
            let confidence: Double; let tokensPerSec: Double
        }
        struct Step: Decodable {
            let words: [String]; let now: Double
            let seek: Int?; let reset: Int?
            let result: Result
        }
        struct Scenario: Decodable { let lang: String; let cfg: [String: Double]; let text: String; let steps: [Step] }
        let scripts: [Script]
        let align: [Scenario]
    }

    /// The fixture lives with the Android tests; read it straight from the
    /// checkout (simulator and CI). On a device there is no checkout — skip.
    private static let goldenURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("android/app/src/test/resources/engine-golden.json")

    private func loadGolden() throws -> Golden {
        try XCTSkipUnless(FileManager.default.fileExists(atPath: Self.goldenURL.path),
                          "engine-golden.json is only reachable from a source checkout")
        return try JSONDecoder().decode(Golden.self, from: Data(contentsOf: Self.goldenURL))
    }

    func testEngineLoads() throws {
        XCTAssertNotNil(AlignEngine(), "engine.js must be bundled with the app")
    }

    func testScriptPreparationMatchesWebEngine() throws {
        let golden = try loadGolden()
        XCTAssertEqual(golden.scripts.count, 6)
        for (i, script) in golden.scripts.enumerated() {
            let engine = try XCTUnwrap(AlignEngine())
            let tokens = engine.load(script: script.text, lang: script.lang)
            XCTAssertEqual(tokens.count, script.tokens.count, "token count (script \(i))")
            for (k, want) in script.tokens.enumerated() where k < tokens.count {
                XCTAssertEqual(tokens[k].raw, want.raw, "raw (script \(i) token \(k))")
                XCTAssertEqual(tokens[k].breakBefore, want.breakBefore, "breakBefore of '\(want.raw)'")
            }
        }
    }

    func testAlignmentMatchesWebEngineStepForStep() throws {
        let golden = try loadGolden()
        XCTAssertEqual(golden.align.count, 4)
        for (i, scenario) in golden.align.enumerated() {
            let engine = try XCTUnwrap(AlignEngine())
            let tokens = engine.load(script: scenario.text, config: scenario.cfg, lang: scenario.lang)
            XCTAssertFalse(tokens.isEmpty)
            engine.reset(startToken: 0)
            var moves = 0
            for (k, step) in scenario.steps.enumerated() {
                if let seek = step.seek { engine.seek(token: seek) }
                if let reset = step.reset { engine.reset(startToken: reset) }
                let r = try XCTUnwrap(engine.push(words: step.words, nowMs: step.now),
                                      "scenario \(i) step \(k) returned nothing")
                let at = "scenario \(i) step \(k) words=\(step.words)"
                XCTAssertEqual(r.token, step.result.token, "token @ \(at)")
                XCTAssertEqual(r.moved, step.result.moved, "moved @ \(at)")
                XCTAssertEqual(r.lost, step.result.lost, "lost @ \(at)")
                XCTAssertEqual(r.confidence, step.result.confidence, accuracy: 1e-9, "confidence @ \(at)")
                XCTAssertEqual(r.tokensPerSec, step.result.tokensPerSec, accuracy: 1e-9, "tokensPerSec @ \(at)")
                if r.moved { moves += 1 }
            }
            XCTAssertGreaterThan(moves, 10, "scenario \(i) should actually move the cursor")
        }
    }

    func testLanguageDetection() {
        XCTAssertEqual(AlignEngine.language(for: "안녕하세요 여러분"), "ko")
        XCTAssertEqual(AlignEngine.language(for: "Hello everyone, welcome back"), "en")
        XCTAssertEqual(AlignEngine.language(for: "2025 30% !!!"), "ko")
        // Mostly Latin letters → English, even with a Korean word in it (ratio < 15%).
        XCTAssertEqual(AlignEngine.language(for: "한국어 script with some English words in it"), "en")
        // Korean script with a few English brand names stays Korean.
        XCTAssertEqual(AlignEngine.language(for: "오늘은 AI 프롬프터 앱을 소개합니다. API 키도 필요 없어요."), "ko")
    }
}

import XCTest
@testable import MobilePrompt

/// Same vectors as android/app/src/test/.../ScriptMarkupTest.kt.
final class ScriptMarkupTests: XCTestCase {
    private func tokens(_ script: String) throws -> [DisplayToken] {
        let engine = try XCTUnwrap(AlignEngine())
        return engine.load(script: ScriptMarkup.engineText(script), lang: AlignEngine.language(for: script))
    }

    private func applied(_ script: String) throws -> [DisplayToken] {
        try XCTUnwrap(ScriptMarkup.apply(try tokens(script), original: script))
    }

    func testEngineTextBlanksNotesAndKeepsEverythingElse() {
        XCTAssertEqual(ScriptMarkup.engineText("안녕 [쉬고] 여러분"), "안녕 [__] 여러분")
        XCTAssertEqual(ScriptMarkup.engineText("[아 잠깐 쉬고] 시작"), "[_ __ __] 시작")
        XCTAssertEqual(ScriptMarkup.engineText("문장[1] 그대로"), "문장[1] 그대로")
        XCTAssertEqual(ScriptMarkup.engineText("[메모\n안녕"), "[__\n안녕")
        XCTAssertEqual(ScriptMarkup.engineText("[쉬고]. 다음"), "[__]_ 다음")
        XCTAssertEqual(ScriptMarkup.engineText("**강조** 유지"), "**강조** 유지")
        XCTAssertEqual(ScriptMarkup.engineText("[pause]\u{00A0}go\r\n[x]"), "[_____]\u{00A0}go\r\n[_]")
    }

    func testTokenCountNeverChanges() throws {
        let engine = try XCTUnwrap(AlignEngine())
        for script in ["안녕 [쉬고] 여러분", "[아 잠깐 쉬고] 시작", "문장[1] 그대로", "[메모\n안녕", "[쉬고]. 다음",
                       "**강조 문구** 끝", "[pause]\u{00A0}go\r\n[x]", "a\u{3000}[b]\u{2003}c", "**", "****"] {
            let plain = engine.load(script: script).count
            let marked = engine.load(script: ScriptMarkup.engineText(script)).count
            XCTAssertEqual(plain, marked, script)
            XCTAssertEqual(ScriptMarkup.tokenize(script).count, plain, script)
        }
    }

    func testNotesAreDisplayedVerbatimButNeverMatched() throws {
        let t = try applied("안녕 [쉬고] 여러분")
        XCTAssertEqual(t.map(\.display), ["안녕", "[쉬고]", "여러분"])
        XCTAssertEqual(t.map(\.kind), [.plain, .note, .plain])
        XCTAssertEqual(t[1].raw, "[쉬고]")

        let multi = try applied("[아 잠깐 쉬고] 시작")
        XCTAssertEqual(multi.map(\.kind), [.note, .note, .note, .plain])

        let line = try applied("[메모 **굵게** 끝\n다음")
        XCTAssertEqual(line.map(\.kind), [.note, .note, .note, .plain])
        XCTAssertEqual(line[1].display, "**굵게**") // inside a note nothing is interpreted

        XCTAssertEqual(try applied("문장[1] 그대로").map(\.kind), [.plain, .plain])
        XCTAssertEqual(try applied("[pause] go").map(\.kind), [.note, .plain])
    }

    func testEmphasis() throws {
        let t = try applied("**강조 문구** 끝")
        XCTAssertEqual(t.map(\.display), ["강조", "문구", "끝"])
        XCTAssertEqual(t.map(\.kind), [.emphasis, .emphasis, .plain])

        XCTAssertEqual(try applied("** 혼자").map(\.display), ["**", "혼자"])
        XCTAssertEqual(try applied("** 혼자").map(\.kind), [.plain, .plain])

        let punct = try applied("**강조**, 다음")
        XCTAssertEqual(punct.map(\.display), ["강조,", "다음"])
        XCTAssertEqual(punct.map(\.kind), [.emphasis, .plain])

        let lines = try applied("**a** b\n**c d")
        XCTAssertEqual(lines.map(\.kind), [.emphasis, .plain, .emphasis, .emphasis])

        XCTAssertEqual(try applied("****").map(\.display), ["****"])
    }

    func testStripMarkers() {
        XCTAssertEqual(ScriptMarkup.stripMarkers("**저희 팀**은"), "저희 팀은")
    }
}

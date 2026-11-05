import XCTest
@testable import MobilePrompt

/// The handoff contract with Perplz (docs/perplz-integration.md), as the app
/// enforces it. Mirrors android/app/src/test/.../HandoffTest.kt.
final class HandoffTests: XCTestCase {
    /// encodeURIComponent-style: everything but unreserved ASCII is escaped.
    private func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
    }

    private func parse(_ s: String) -> HandoffRequest? {
        URL(string: s).flatMap(HandoffRequest.parse)
    }

    func testParsesScriptAutostartAndReturn() throws {
        let script = "안녕하세요, 저는 퍼플즈 3기 프로젝트를 소개합니다.\n\n둘째 줄 + 더하기 기호."
        let url = "voiceprompter://script?s=\(enc(script))&autostart=1&return=\(enc("perplz://project/123/video"))"
        let r = try XCTUnwrap(parse(url))
        XCTAssertEqual(r.text, script)
        XCTAssertTrue(r.autostart)
        XCTAssertEqual(r.returnURL?.absoluteString, "perplz://project/123/video")
    }

    func testDefaultsWithoutOptionalParams() throws {
        let r = try XCTUnwrap(parse("voiceprompter://script?s=hello%20world"))
        XCTAssertEqual(r.text, "hello world")
        XCTAssertFalse(r.autostart)
        XCTAssertNil(r.returnURL)
    }

    func testPlusIsLiteral() throws {
        // encodeURIComponent never produces '+', so a literal '+' must survive.
        XCTAssertEqual(try XCTUnwrap(parse("voiceprompter://script?s=a+b")).text, "a+b")
    }

    func testRejectsMissingOrEmptyScript() {
        XCTAssertNil(parse("voiceprompter://script"))
        XCTAssertNil(parse("voiceprompter://script?autostart=1"))
        XCTAssertNil(parse("voiceprompter://script?s=%20%20"))
    }

    func testRejectsOversizedScript() throws {
        let big = String(repeating: "가", count: 6000) // 18 KB in UTF-8
        XCTAssertNil(parse("voiceprompter://script?s=\(enc(big))"))
        let ok = String(repeating: "가", count: 5000) // 15 KB
        XCTAssertEqual(try XCTUnwrap(parse("voiceprompter://script?s=\(enc(ok))")).text.count, 5000)
    }

    func testRejectsOtherSchemesAndHosts() {
        XCTAssertNil(parse("https://perplz.com/prompter?s=hi"))
        XCTAssertNil(parse("voiceprompter://other?s=hi"))
        XCTAssertNil(parse("voiceprompter://scripts?s=hi"))
    }

    func testDropsDisallowedReturnURLButKeepsScript() throws {
        let r = try XCTUnwrap(parse("voiceprompter://script?s=hi&return=\(enc("https://evil.example/x"))"))
        XCTAssertEqual(r.text, "hi")
        XCTAssertNil(r.returnURL)
        let ok = try XCTUnwrap(parse("voiceprompter://script?s=hi&return=\(enc("https://perplz.com/p/1"))"))
        XCTAssertEqual(ok.returnURL?.absoluteString, "https://perplz.com/p/1")
    }
}

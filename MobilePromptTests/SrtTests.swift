import XCTest
@testable import MobilePrompt

/// Pins the subtitle cue algorithm. The Android port runs the identical
/// fixture (android/app/src/test/.../SrtTest.kt) against the same expected
/// text, so an SRT recorded on either phone comes out the same.
final class SrtTests: XCTestCase {
    static let script = "안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.|시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요."

    static var tokens: [DisplayToken] {
        script.split(separator: "|").enumerated().flatMap { (pi, para) in
            para.split(separator: " ").enumerated().map { (i, w) in
                DisplayToken(raw: String(w), breakBefore: pi > 0 && i == 0)
            }
        }
    }

    /// Recording starts at 100.0 s; the cursor advances one token every 0.4 s
    /// from 100.8 s and the take ends at token 20 (mid-sentence).
    static var trail: [WordTime] { (0...20).map { WordTime(token: $0, t: 100.8 + Double($0) * 0.4) } }

    static let expected = """
    1
    00:00:00,550 --> 00:00:04,000
    안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를

    2
    00:00:04,010 --> 00:00:04,800
    소개해 드릴게요.

    3
    00:00:04,810 --> 00:00:07,600
    시중에 나온 프롬프터 앱들은 대부분 정해진 속도로

    4
    00:00:07,610 --> 00:00:08,400
    글자가 흘러가죠.

    5
    00:00:08,410 --> 00:00:09,600
    그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나


    """

    func testCuesMatchFixture() throws {
        let srt = try XCTUnwrap(Srt.build(tokens: Self.tokens, trail: Self.trail, recStart: 100.0))
        XCTAssertEqual(srt, Self.expected)
    }

    func testNothingUsableYieldsNil() {
        XCTAssertNil(Srt.build(tokens: Self.tokens, trail: [], recStart: 100.0))
        XCTAssertNil(Srt.build(tokens: [], trail: Self.trail, recStart: 100.0))
    }

    func testCuesAreMonotonicAndNonOverlapping() throws {
        let srt = try XCTUnwrap(Srt.build(tokens: Self.tokens, trail: Self.trail, recStart: 100.0))
        let times = srt.split(separator: "\n").filter { $0.contains("-->") }.map { line -> (Double, Double) in
            let parts = line.components(separatedBy: " --> ").map(Self.seconds)
            return (parts[0], parts[1])
        }
        XCTAssertEqual(times.count, 5)
        var lastEnd = -1.0
        for (start, end) in times {
            XCTAssertGreaterThan(start, lastEnd)
            XCTAssertGreaterThan(end, start)
            lastEnd = end
        }
    }

    private static func seconds(_ stamp: String) -> Double {
        let p = stamp.replacingOccurrences(of: ",", with: ":").split(separator: ":").map { Double($0)! }
        return p[0] * 3600 + p[1] * 60 + p[2] + p[3] / 1000
    }
}

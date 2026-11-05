import XCTest
@testable import MobilePrompt

/// Same fixture as android/app/src/test/.../RehearsalStatsTest.kt.
final class RehearsalStatsTests: XCTestCase {
    /// 40 tokens, four sentences of ten ("말1 … 말10." …).
    static let tokens: [DisplayToken] = (1...40).map { i in
        DisplayToken(raw: "말\(i)" + (i % 10 == 0 ? "." : ""), breakBefore: false)
    }

    /// One move every 0.4 s from t=100, with a 3 s gap after token 15 and a
    /// re-read of tokens 22–24 after reaching 25.
    static var trail: [WordTime] {
        var out: [WordTime] = []
        var t = 100.0
        for i in 0..<40 {
            if i == 16 { t += 3.0 }
            out.append(WordTime(token: i, t: t))
            if i == 25 {
                for back in 22...24 { t += 0.4; out.append(WordTime(token: back, t: t)) }
            }
            t += 0.4
        }
        return out
    }

    static let events: [RehearsalEvent] = [.reread(from: 25, to: 22, t: 100 + 26 * 0.4 + 3.0)]

    func testReportNumbers() throws {
        let r = try XCTUnwrap(RehearsalStats.compute(tokens: Self.tokens, trail: Self.trail, events: Self.events,
                                                     lang: "ko", kind: .rehearsal))
        XCTAssertEqual(r.kind, .rehearsal)
        XCTAssertEqual(r.paceUnit, .syllablesPerMinute)
        XCTAssertEqual(r.pauseCount, 1)
        XCTAssertEqual(r.pauseSeconds, 3.4, accuracy: 1e-9)
        // 39 steps of 0.4 s + 3 s gap + the 3 re-read steps.
        XCTAssertEqual(r.seconds, 39 * 0.4 + 3.0 + 3 * 0.4, accuracy: 1e-9)
        // 말N: one syllable plus one per digit → 40 + 9 + 31*2 = 111 units.
        XCTAssertEqual(r.pace, 111 / r.seconds * 60, accuracy: 1e-9)
        XCTAssertEqual(r.stuck.map(\.id), [1, 2])
        guard case .pause(let paused) = try XCTUnwrap(r.stuck[0].reasons.first) else { return XCTFail("expected a pause") }
        XCTAssertEqual(paused, 3.4, accuracy: 1e-9)
        XCTAssertEqual(r.stuck[0].reasons.count, 1)
        XCTAssertEqual(r.stuck[1].reasons, [.reread])
        XCTAssertEqual(r.stuck[0].text, (11...20).map { "말\($0)" + ($0 == 20 ? "." : "") }.joined(separator: " "))
    }

    func testTakeUsesRecordingLengthAndWordsForEnglish() throws {
        let r = try XCTUnwrap(RehearsalStats.compute(tokens: Self.tokens, trail: Self.trail, events: [],
                                                     lang: "en", kind: .take, duration: 42))
        XCTAssertEqual(r.kind, .take)
        XCTAssertEqual(r.seconds, 42)
        XCTAssertEqual(r.paceUnit, .wordsPerMinute)
        XCTAssertEqual(r.pace, 40 / (39 * 0.4 + 3.0 + 3 * 0.4) * 60, accuracy: 1e-9)
    }

    func testNothingUsableYieldsNil() {
        XCTAssertNil(RehearsalStats.compute(tokens: Self.tokens, trail: [WordTime(token: 0, t: 1)], events: [], lang: "ko", kind: .rehearsal))
        XCTAssertNil(RehearsalStats.compute(tokens: [], trail: Self.trail, events: [], lang: "ko", kind: .rehearsal))
    }

    func testSentencesAndUnits() {
        XCTAssertEqual(RehearsalStats.sentenceRanges(Self.tokens).count, 4)
        let mixed = [DisplayToken(raw: "안녕하세요", breakBefore: false), DisplayToken(raw: "[쉬고]", breakBefore: false, kind: .note),
                     DisplayToken(raw: "API", breakBefore: false), DisplayToken(raw: "2025년", breakBefore: false)]
        XCTAssertEqual(mixed.map { RehearsalStats.unitCount($0, unit: .syllablesPerMinute) }, [5, 0, 2, 5])
        XCTAssertEqual(mixed.map { RehearsalStats.unitCount($0, unit: .wordsPerMinute) }, [1, 0, 1, 1])
    }
}

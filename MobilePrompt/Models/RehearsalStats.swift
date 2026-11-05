import Foundation

/// Something that went wrong while reading, as the aligner saw it.
enum RehearsalEvent: Equatable {
    /// The aligner lost the reader near `token`.
    case lost(token: Int, t: Double)
    /// The reader went back from `from` to `to` (re-read or a backward tap).
    case reread(from: Int, to: Int, t: Double)
}

/// Why a sentence shows up in the "stuck spots" list.
enum StuckReason: Equatable {
    case pause(seconds: Double)
    case reread
    case lost
    case slow
}

struct RehearsalReport: Identifiable, Equatable {
    enum Kind: Equatable { case rehearsal, take }
    enum PaceUnit: Equatable { case syllablesPerMinute, wordsPerMinute }
    struct Stuck: Identifiable, Equatable {
        let id: Int // sentence index
        let text: String
        let reasons: [StuckReason]
    }

    let id = UUID()
    let kind: Kind
    /// Reading time (rehearsal) or recording length (take), seconds.
    let seconds: Double
    let pace: Double
    let paceUnit: PaceUnit
    let pauseCount: Int
    let pauseSeconds: Double
    let stuck: [Stuck]
}

/// Pure feedback over an alignment trail; android/.../engine/RehearsalStats.kt
/// is the mirror and both test suites run the same fixture.
enum RehearsalStats {
    /// A gap between two accepted cursor moves longer than this is a pause.
    /// (SFSpeech finalisation and sherpa's endpoint rule both sit under it.)
    static let pauseThreshold = 1.5
    /// A sentence read at under this fraction of the overall pace is "slow".
    static let slowFraction = 0.7
    static let maxStuck = 6

    static func compute(tokens: [DisplayToken], trail: [WordTime], events: [RehearsalEvent],
                        lang: String, kind: RehearsalReport.Kind, duration: Double? = nil) -> RehearsalReport? {
        guard trail.count >= 2, !tokens.isEmpty else { return nil }
        let readingSeconds = trail.last!.t - trail.first!.t
        guard readingSeconds > 0 else { return nil }
        let lo = trail.map(\.token).min()!
        let hi = min(trail.map(\.token).max()!, tokens.count - 1)
        let unit: RehearsalReport.PaceUnit = lang == "en" ? .wordsPerMinute : .syllablesPerMinute

        let units = (lo...hi).reduce(0.0) { $0 + unitCount(tokens[$1], unit: unit) }
        let pace = units / readingSeconds * 60

        let sentences = sentenceRanges(tokens)
        func sentence(of token: Int) -> Int? { sentences.firstIndex { $0.contains(token) } }
        let times = Srt.earliestTimes(tokens: tokens, trail: trail)

        var pauseCount = 0
        var pauseSeconds = 0.0
        var pauseBySentence: [Int: Double] = [:]
        for i in 1..<trail.count {
            let gap = trail[i].t - trail[i - 1].t
            guard gap > pauseThreshold else { continue }
            pauseCount += 1
            pauseSeconds += gap
            if let s = sentence(of: trail[i - 1].token) { pauseBySentence[s, default: 0] += gap }
        }
        var lostIn = Set<Int>()
        var rereadIn = Set<Int>()
        for e in events {
            switch e {
            case .lost(let token, _): if let s = sentence(of: token) { lostIn.insert(s) }
            case .reread(_, let to, _): if let s = sentence(of: to) { rereadIn.insert(s) }
            }
        }

        var stuck: [RehearsalReport.Stuck] = []
        for (si, range) in sentences.enumerated() {
            var reasons: [StuckReason] = []
            if let p = pauseBySentence[si] { reasons.append(.pause(seconds: p)) }
            if rereadIn.contains(si) { reasons.append(.reread) }
            if lostIn.contains(si) { reasons.append(.lost) }
            let spoken = range.filter { tokens[$0].kind != .note }
            if spoken.count >= 4, let first = spoken.first, let last = spoken.last,
               let t0 = times[first], let t1 = times[last], t1 > t0, pace > 0 {
                let u = spoken.reduce(0.0) { $0 + unitCount(tokens[$1], unit: unit) }
                if u / (t1 - t0) * 60 < slowFraction * pace { reasons.append(.slow) }
            }
            guard !reasons.isEmpty else { continue }
            let text = spoken.map { tokens[$0].display }.joined(separator: " ")
            guard !text.isEmpty else { continue }
            stuck.append(.init(id: si, text: text, reasons: reasons))
            if stuck.count == maxStuck { break }
        }

        return RehearsalReport(kind: kind, seconds: duration ?? readingSeconds, pace: pace, paceUnit: unit,
                               pauseCount: pauseCount, pauseSeconds: pauseSeconds, stuck: stuck)
    }

    /// Sentence boundaries: end punctuation or a line break (the SRT rule
    /// without its line budget). Ranges cover every token, notes included.
    static func sentenceRanges(_ tokens: [DisplayToken]) -> [ClosedRange<Int>] {
        var out: [ClosedRange<Int>] = []
        var start = 0
        for i in tokens.indices {
            let d = tokens[i].display
            let ends = d.hasSuffix(".") || d.hasSuffix("!") || d.hasSuffix("?") || d.hasSuffix("…")
            let nextBreaks = i + 1 < tokens.count ? tokens[i + 1].breakBefore : true
            if ends || nextBreaks {
                out.append(start...i)
                start = i + 1
            }
        }
        return out
    }

    /// Korean pace is counted in syllables (digits count one each, a Latin
    /// word about two); English pace in words. Notes count nothing.
    static func unitCount(_ token: DisplayToken, unit: RehearsalReport.PaceUnit) -> Double {
        guard token.kind != .note else { return 0 }
        switch unit {
        case .wordsPerMinute:
            return token.display.contains(where: { $0.isLetter || $0.isNumber }) ? 1 : 0
        case .syllablesPerMinute:
            var n = 0.0
            var inLatin = false
            for s in token.display.unicodeScalars {
                if (0xAC00...0xD7A3).contains(s.value) { n += 1; inLatin = false }
                else if (48...57).contains(s.value) { n += 1; inLatin = false }
                else if (65...90).contains(s.value) || (97...122).contains(s.value) {
                    if !inLatin { n += 2; inLatin = true }
                } else { inLatin = false }
            }
            return n
        }
    }

    /// Default pace used for the editor's time estimate until the reader has
    /// rehearsed once: broadcast Korean runs 330–360 syllables a minute and a
    /// presentation a little slower; English about 150 words a minute.
    static func defaultPace(unit: RehearsalReport.PaceUnit) -> Double {
        unit == .wordsPerMinute ? 150 : 300
    }
}

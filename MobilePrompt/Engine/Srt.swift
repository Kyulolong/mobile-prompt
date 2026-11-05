import Foundation

/// (token, host-clock seconds) for every accepted cursor move.
struct WordTime: Equatable {
    let token: Int
    let t: Double
}

/// Subtitle generation from the alignment trail. Pure — the Android port
/// (engine/Srt.kt, `buildSrt`) is a line-for-line mirror, and both test suites
/// pin the same fixture so the two apps keep producing identical SRT files.
enum Srt {
    /// `trail` is the slice of cursor moves made while this recording ran;
    /// `recStart` is the recording's first-frame time on the same clock.
    /// Returns nil when nothing usable was captured.
    static func build(tokens: [DisplayToken], trail: [WordTime], recStart: Double) -> String? {
        guard !tokens.isEmpty, !trail.isEmpty else { return nil }
        // Earliest time the cursor reached (at least) each token.
        var tokenTime = [Double?](repeating: nil, count: tokens.count)
        var filled = -1
        for entry in trail where entry.token > filled {
            for j in (filled + 1)...min(entry.token, tokens.count - 1) where tokenTime[j] == nil {
                tokenTime[j] = entry.t
            }
            filled = max(filled, entry.token)
        }

        var cues: [(start: Double, end: Double, text: String)] = []
        var rangeStart = 0
        var charCount = 0
        for i in 0..<tokens.count {
            let raw = tokens[i].raw
            charCount += raw.count + 1
            let endsSentence = raw.hasSuffix(".") || raw.hasSuffix("!") || raw.hasSuffix("?") || raw.hasSuffix("…")
            let nextBreaks = i + 1 < tokens.count ? tokens[i + 1].breakBefore : true
            // Keep cues to 1–2 subtitle lines: hard budget ~26 chars, and take
            // a comma as a break point once past ~14.
            let overBudget = charCount >= 26
            let commaBreak = raw.hasSuffix(",") && charCount >= 14
            guard endsSentence || nextBreaks || overBudget || commaBreak || i == tokens.count - 1 else { continue }
            defer { rangeStart = i + 1; charCount = 0 }
            let range = rangeStart...i
            let times = range.compactMap { tokenTime[$0] }
            guard let first = times.first, let last = times.last else { continue }
            let start = max(0, first - recStart - 0.25)
            let end = max(last - recStart + 0.8, start + 0.8)
            let text = range.map { tokens[$0].raw }.joined(separator: " ")
            cues.append((start, end, text))
        }
        guard !cues.isEmpty else { return nil }

        // Monotonic, non-overlapping.
        for i in 1..<cues.count {
            if cues[i].start < cues[i - 1].end {
                cues[i].start = cues[i - 1].end + 0.01
                cues[i].end = max(cues[i].end, cues[i].start + 0.5)
            }
        }

        func stamp(_ s: Double) -> String {
            let ms = Int((s * 1000).rounded())
            return String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        var srt = ""
        for (i, c) in cues.enumerated() {
            srt += "\(i + 1)\n\(stamp(c.start)) --> \(stamp(c.end))\n\(c.text)\n\n"
        }
        return srt
    }
}

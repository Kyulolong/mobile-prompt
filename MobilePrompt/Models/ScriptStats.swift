import Foundation

/// Counts and a reading-time estimate for the editor. Pure; mirrored by
/// android/.../engine/ScriptStats.kt.
struct ScriptStats: Equatable {
    /// Characters excluding whitespace.
    let characters: Int
    /// Spoken tokens (notes excluded).
    let words: Int
    /// Estimated reading time at `pace`.
    let seconds: Double
    let unit: RehearsalReport.PaceUnit
    /// The pace came from the reader's own rehearsal, not the default.
    let measured: Bool

    static func compute(script: String, paceKo: Double, paceEn: Double) -> ScriptStats {
        let unit: RehearsalReport.PaceUnit = AlignEngine.language(for: script) == "en" ? .wordsPerMinute : .syllablesPerMinute
        let plain = ScriptMarkup.tokenizeWithBreaks(script)
        let tokens = ScriptMarkup.apply(plain, original: script) ?? plain
        let characters = script.filter { !$0.isWhitespace }.count
        let words = tokens.filter { $0.kind != .note }.count
        let units = tokens.reduce(0.0) { $0 + RehearsalStats.unitCount($1, unit: unit) }
        let measuredPace = unit == .wordsPerMinute ? paceEn : paceKo
        let pace = measuredPace > 0 ? measuredPace : RehearsalStats.defaultPace(unit: unit)
        return ScriptStats(characters: characters, words: words, seconds: units / pace * 60,
                           unit: unit, measured: measuredPace > 0)
    }

    /// "1,234자 · 210어절 · 약 1분 20초 (보통 속도)"
    var line: String {
        let nf = NumberFormatter()
        nf.numberStyle = .decimal
        nf.locale = AppSettings.textLocale
        let chars = nf.string(from: characters as NSNumber) ?? String(characters)
        let words = nf.string(from: self.words as NSNumber) ?? String(self.words)
        let total = Int(seconds.rounded())
        let duration = total >= 60
            ? String(format: AppSettings.tr("%@분 %@초"), String(total / 60), String(total % 60))
            : String(format: AppSettings.tr("%@초"), String(total))
        let paceNote = AppSettings.tr(measured ? "(내 속도)" : "(보통 속도)")
        return String(format: AppSettings.tr("%@자 · %@어절 · 약 %@"), chars, words, duration) + " " + paceNote
    }
}

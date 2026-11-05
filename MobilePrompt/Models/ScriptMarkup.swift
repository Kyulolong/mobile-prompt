import Foundation

/// How a token is drawn in the prompter.
enum TokenKind: Equatable {
    case plain
    /// `**like this**` — bold, read aloud like any other word.
    case emphasis
    /// `[like this]` — dimmed and never spoken: the engine is handed blanks
    /// for it, so the cursor can't land there.
    case note
}

/// Lightweight markup inside scripts. Pure; android/.../engine/ScriptMarkup.kt
/// is the mirror and both test suites run the same vectors.
///
/// The engine tokenizes on whitespace and keeps `raw` verbatim, so the two
/// halves split cleanly: `engineText` blanks note characters WITHOUT touching
/// any whitespace (token count and positions stay identical to the original
/// script), and `apply` walks the engine's tokens in order to decide how each
/// one is displayed. `*`, `[`, `]` and `_` are all dropped by the engine's
/// normalizers, so emphasis markers never affect matching either.
enum ScriptMarkup {
    /// JS `\s` — what the engine's tokenizer splits on — spelled out so the
    /// scan agrees with it exactly.
    private static let whitespaceScalars: Set<Unicode.Scalar> = {
        var s: Set<Unicode.Scalar> = [" ", "\t", "\n", "\u{0B}", "\u{0C}", "\r", "\u{A0}", "\u{1680}",
                                      "\u{2028}", "\u{2029}", "\u{202F}", "\u{205F}", "\u{3000}", "\u{FEFF}"]
        for v in 0x2000...0x200A { s.insert(Unicode.Scalar(v)!) }
        return s
    }()

    private static func isWhitespace(_ c: Character) -> Bool {
        c.unicodeScalars.allSatisfy { whitespaceScalars.contains($0) }
    }

    /// The text to hand the engine: notes blanked to `_`, everything else
    /// (including every whitespace character) exactly as written.
    ///
    /// A note opens at a `[` that starts a token and closes at the first `]`;
    /// the rest of that token is blanked too (`[쉬고].` is all note), and an
    /// unclosed note ends with its line.
    static func engineText(_ script: String) -> String {
        var out = ""
        out.reserveCapacity(script.utf8.count)
        var inNote = false
        var blankRestOfToken = false
        var atTokenStart = true
        for c in script {
            if isWhitespace(c) {
                if c.unicodeScalars.contains("\n") { inNote = false }
                blankRestOfToken = false
                atTokenStart = true
                out.append(c)
                continue
            }
            if atTokenStart, c == "[" { inNote = true }
            atTokenStart = false
            if inNote || blankRestOfToken {
                if inNote, c == "]" {
                    inNote = false
                    blankRestOfToken = true
                }
                out.append(c == "[" || c == "]" ? c : "_")
            } else {
                out.append(c)
            }
        }
        return out
    }

    /// The engine's own split: runs of whitespace separate tokens.
    static func tokenize(_ script: String) -> [String] {
        var parts: [String] = []
        var current = ""
        for c in script {
            if isWhitespace(c) {
                if !current.isEmpty { parts.append(current); current = "" }
            } else {
                current.append(c)
            }
        }
        if !current.isEmpty { parts.append(current) }
        return parts
    }

    /// Decide display text and kind for the engine's tokens, in order. The
    /// engine was fed `engineText`, so its `raw` for a note is the blanked
    /// form; the original script supplies what to actually show. Returns nil
    /// if the two don't line up token for token (then show the script plain).
    static func apply(_ tokens: [DisplayToken], original script: String) -> [DisplayToken]? {
        let originals = tokenize(script)
        guard originals.count == tokens.count else { return nil }
        var out = zip(originals, tokens).map { DisplayToken(raw: $0, breakBefore: $1.breakBefore) }
        var inNote = false
        var inEmph = false
        for i in out.indices {
            if out[i].breakBefore {
                inNote = false
                inEmph = false
            }
            let raw = out[i].raw
            if inNote || raw.hasPrefix("[") {
                out[i].display = raw
                out[i].kind = .note
                inNote = !raw.contains("]")
                continue
            }
            var display = raw
            var emphHere = inEmph
            var emphAfter = inEmph
            if !raw.allSatisfy({ $0 == "*" }) { // a token of nothing but stars stays literal
                if display.hasPrefix("**"), display.count > 2 {
                    display.removeFirst(2)
                    emphHere = true
                    emphAfter = true
                }
                if let marker = trailingMarker(in: display) {
                    display.removeSubrange(marker)
                    emphAfter = false
                }
            }
            if display.isEmpty { // e.g. "****": keep it literal, change nothing
                display = raw
                emphHere = inEmph
                emphAfter = inEmph
            }
            out[i].display = display
            out[i].kind = emphHere ? .emphasis : .plain
            inEmph = emphAfter
        }
        return out
    }

    /// A closing `**` at the end of the token, allowing trailing punctuation
    /// after it (`강조**,` → `강조,`).
    private static func trailingMarker(in s: String) -> Range<String.Index>? {
        var end = s.endIndex
        while end > s.startIndex {
            let prev = s.index(before: end)
            if s[prev].isLetter || s[prev].isNumber { break }
            end = prev
        }
        // `end` is now just past the last letter/digit; look for ** right there.
        guard end > s.startIndex else { return nil }
        let tail = s[end...]
        guard tail.hasPrefix("**") else { return nil }
        return end..<s.index(end, offsetBy: 2)
    }

    /// Titles and counts shouldn't show the markers.
    static func stripMarkers(_ line: String) -> String {
        line.replacingOccurrences(of: "**", with: "")
    }
}

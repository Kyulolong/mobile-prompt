package com.teaminpact.mobileprompt.engine

/** How a token is drawn in the prompter. */
enum class TokenKind {
    PLAIN,
    /** `**like this**` — bold, read aloud like any other word. */
    EMPHASIS,
    /** `[like this]` — dimmed and never spoken: the engine is handed blanks for it. */
    NOTE,
}

/**
 * Lightweight markup inside scripts. Pure; mirror of iOS ScriptMarkup.swift,
 * and both test suites run the same vectors.
 *
 * The engine tokenizes on whitespace and keeps `raw` verbatim, so the two
 * halves split cleanly: [engineText] blanks note characters WITHOUT touching
 * any whitespace (token count and positions stay identical to the original
 * script), and [apply] walks the engine's tokens in order to decide how each
 * one is displayed. `*`, `[`, `]` and `_` are all dropped by the engine's
 * normalizers, so emphasis markers never affect matching either.
 */
object ScriptMarkup {
    /** JS `\s` — what the engine's tokenizer splits on (see Script.kt). */
    private val WHITESPACE: Set<Char> =
        setOf(' ', '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿') +
            (' '..' ').toSet()

    /**
     * The text to hand the engine: notes blanked to `_`, everything else
     * (including every whitespace character) exactly as written.
     *
     * A note opens at a `[` that starts a token and closes at the first `]`;
     * the rest of that token is blanked too (`[쉬고].` is all note), and an
     * unclosed note ends with its line.
     */
    fun engineText(script: String): String {
        val out = StringBuilder(script.length)
        var inNote = false
        var blankRestOfToken = false
        var atTokenStart = true
        for (c in script) {
            if (c in WHITESPACE) {
                if (c == '\n') inNote = false
                blankRestOfToken = false
                atTokenStart = true
                out.append(c)
                continue
            }
            if (atTokenStart && c == '[') inNote = true
            atTokenStart = false
            if (inNote || blankRestOfToken) {
                if (inNote && c == ']') {
                    inNote = false
                    blankRestOfToken = true
                }
                out.append(if (c == '[' || c == ']') c else '_')
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    /** The engine's own split: runs of whitespace separate tokens. */
    fun tokenize(script: String): List<String> = tokenizeWithBreaks(script).map { it.raw }

    /**
     * Same split, with the engine's `breakBefore` (a line break somewhere in
     * the whitespace before the token). Lets stats run without the engine.
     */
    fun tokenizeWithBreaks(script: String): List<DisplayToken> {
        val out = ArrayList<DisplayToken>()
        val current = StringBuilder()
        var currentBreak = false
        var pendingBreak = false
        for (c in script) {
            if (c in WHITESPACE) {
                if (current.isNotEmpty()) {
                    out.add(DisplayToken(current.toString(), currentBreak))
                    current.setLength(0)
                }
                if (c == '\n') pendingBreak = true
            } else {
                if (current.isEmpty()) {
                    currentBreak = pendingBreak
                    pendingBreak = false
                }
                current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(DisplayToken(current.toString(), currentBreak))
        return out
    }

    /**
     * Decide display text and kind for the engine's tokens, in order. The
     * engine was fed [engineText], so its `raw` for a note is the blanked form;
     * the original script supplies what to actually show. Returns null if the
     * two don't line up token for token (then show the script plain).
     */
    fun apply(tokens: List<DisplayToken>, original: String): List<DisplayToken>? {
        val originals = tokenize(original)
        if (originals.size != tokens.size) return null
        val out = ArrayList<DisplayToken>(tokens.size)
        var inNote = false
        var inEmph = false
        for ((i, t) in tokens.withIndex()) {
            if (t.breakBefore) {
                inNote = false
                inEmph = false
            }
            val raw = originals[i]
            if (inNote || raw.startsWith("[")) {
                out.add(DisplayToken(raw, t.breakBefore, raw, TokenKind.NOTE))
                inNote = !raw.contains(']')
                continue
            }
            var display = raw
            var emphHere = inEmph
            var emphAfter = inEmph
            if (!raw.all { it == '*' }) { // a token of nothing but stars stays literal
                if (display.startsWith("**") && display.length > 2) {
                    display = display.substring(2)
                    emphHere = true
                    emphAfter = true
                }
                val marker = trailingMarkerStart(display)
                if (marker >= 0) {
                    display = display.removeRange(marker, marker + 2)
                    emphAfter = false
                }
            }
            if (display.isEmpty()) { // e.g. "****": keep it literal, change nothing
                display = raw
                emphHere = inEmph
                emphAfter = inEmph
            }
            out.add(DisplayToken(raw, t.breakBefore, display, if (emphHere) TokenKind.EMPHASIS else TokenKind.PLAIN))
            inEmph = emphAfter
        }
        return out
    }

    /** Start of a closing `**` at the end of the token, allowing trailing punctuation (`강조**,`). */
    private fun trailingMarkerStart(s: String): Int {
        var end = s.length
        while (end > 0 && !s[end - 1].isLetterOrDigit()) end--
        if (end == 0) return -1
        return if (s.startsWith("**", end)) end else -1
    }

    /** Titles and counts shouldn't show the markers. */
    fun stripMarkers(line: String): String = line.replace("**", "")
}

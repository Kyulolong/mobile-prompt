package com.teaminpact.mobileprompt.engine

/** (token, host-clock seconds) for every accepted cursor move while recording. */
class WordTime(val token: Int, val t: Double)

/**
 * Subtitle generation from the alignment trail (port of PrompterViewModel.finishSRT).
 * Returns null when there is nothing usable.
 */
fun buildSrt(tokens: List<DisplayToken>, recWordTimes: List<WordTime>, recStart: Double): String? {
    if (tokens.isEmpty() || recWordTimes.isEmpty()) return null
    // Earliest time the cursor reached (at least) each token.
    val tokenTime = arrayOfNulls<Double>(tokens.size)
    var filled = -1
    for (entry in recWordTimes) {
        if (entry.token <= filled) continue
        for (j in (filled + 1)..minOf(entry.token, tokens.size - 1)) {
            if (tokenTime[j] == null) tokenTime[j] = entry.t
        }
        filled = maxOf(filled, entry.token)
    }

    class Cue(var start: Double, var end: Double, val text: String)
    val cues = ArrayList<Cue>()
    var rangeStart = 0
    var charCount = 0
    for (i in tokens.indices) {
        val raw = tokens[i].raw
        charCount += raw.length + 1
        val endsSentence = raw.endsWith(".") || raw.endsWith("!") || raw.endsWith("?") || raw.endsWith("…")
        val nextBreaks = if (i + 1 < tokens.size) tokens[i + 1].breakBefore else true
        // Keep cues to 1–2 subtitle lines: hard budget ~26 chars, and take a
        // comma as a break point once past ~14.
        val overBudget = charCount >= 26
        val commaBreak = raw.endsWith(",") && charCount >= 14
        if (!(endsSentence || nextBreaks || overBudget || commaBreak || i == tokens.size - 1)) continue
        val range = rangeStart..i
        rangeStart = i + 1
        charCount = 0
        val times = range.mapNotNull { tokenTime[it] }
        val first = times.firstOrNull() ?: continue
        val last = times.last()
        val start = maxOf(0.0, first - recStart - 0.25)
        val end = maxOf(last - recStart + 0.8, start + 0.8)
        cues.add(Cue(start, end, range.joinToString(" ") { tokens[it].raw }))
    }
    if (cues.isEmpty()) return null

    // Monotonic, non-overlapping.
    for (i in 1 until cues.size) {
        if (cues[i].start < cues[i - 1].end) {
            cues[i].start = cues[i - 1].end + 0.01
            cues[i].end = maxOf(cues[i].end, cues[i].start + 0.5)
        }
    }

    fun stamp(s: Double): String {
        val ms = Math.round(s * 1000)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
    val sb = StringBuilder()
    cues.forEachIndexed { i, c ->
        sb.append(i + 1).append('\n').append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n')
            .append(c.text).append("\n\n")
    }
    return sb.toString()
}

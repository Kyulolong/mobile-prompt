package com.teaminpact.mobileprompt.engine

/** Something that went wrong while reading, as the aligner saw it. */
sealed class RehearsalEvent {
    /** The aligner lost the reader near [token]. */
    data class Lost(val token: Int, val t: Double) : RehearsalEvent()
    /** The reader went back from [from] to [to] (re-read or a backward tap). */
    data class Reread(val from: Int, val to: Int, val t: Double) : RehearsalEvent()
}

/** Why a sentence shows up in the "stuck spots" list. */
sealed class StuckReason {
    data class Pause(val seconds: Double) : StuckReason()
    object Reread : StuckReason()
    object Lost : StuckReason()
    object Slow : StuckReason()
}

class RehearsalReport(
    val kind: Kind,
    /** Reading time (rehearsal) or recording length (take), seconds. */
    val seconds: Double,
    val pace: Double,
    val paceUnit: PaceUnit,
    val pauseCount: Int,
    val pauseSeconds: Double,
    val stuck: List<Stuck>,
) {
    enum class Kind { REHEARSAL, TAKE }
    enum class PaceUnit { SYLLABLES_PER_MINUTE, WORDS_PER_MINUTE }
    class Stuck(val id: Int, val text: String, val reasons: List<StuckReason>)
}

/**
 * Pure feedback over an alignment trail; mirror of iOS RehearsalStats.swift,
 * and both test suites run the same fixture.
 */
object RehearsalStats {
    /** A gap between two accepted cursor moves longer than this is a pause. */
    const val PAUSE_THRESHOLD = 1.5
    /** A sentence read at under this fraction of the overall pace is "slow". */
    const val SLOW_FRACTION = 0.7
    const val MAX_STUCK = 6

    fun compute(
        tokens: List<DisplayToken>,
        trail: List<WordTime>,
        events: List<RehearsalEvent>,
        lang: EngineLang,
        kind: RehearsalReport.Kind,
        duration: Double? = null,
    ): RehearsalReport? {
        if (trail.size < 2 || tokens.isEmpty()) return null
        val readingSeconds = trail.last().t - trail.first().t
        if (readingSeconds <= 0) return null
        val lo = trail.minOf { it.token }
        val hi = minOf(trail.maxOf { it.token }, tokens.size - 1)
        val unit = if (lang == EngineLang.EN) RehearsalReport.PaceUnit.WORDS_PER_MINUTE else RehearsalReport.PaceUnit.SYLLABLES_PER_MINUTE

        val units = (lo..hi).sumOf { unitCount(tokens[it], unit) }
        val pace = units / readingSeconds * 60

        val sentences = sentenceRanges(tokens)
        fun sentenceOf(token: Int): Int? = sentences.indexOfFirst { token in it }.takeIf { it >= 0 }
        val times = earliestTimes(tokens, trail)

        var pauseCount = 0
        var pauseSeconds = 0.0
        val pauseBySentence = HashMap<Int, Double>()
        for (i in 1 until trail.size) {
            val gap = trail[i].t - trail[i - 1].t
            if (gap <= PAUSE_THRESHOLD) continue
            pauseCount++
            pauseSeconds += gap
            sentenceOf(trail[i - 1].token)?.let { pauseBySentence[it] = (pauseBySentence[it] ?: 0.0) + gap }
        }
        val lostIn = HashSet<Int>()
        val rereadIn = HashSet<Int>()
        for (e in events) {
            when (e) {
                is RehearsalEvent.Lost -> sentenceOf(e.token)?.let { lostIn.add(it) }
                is RehearsalEvent.Reread -> sentenceOf(e.to)?.let { rereadIn.add(it) }
            }
        }

        val stuck = ArrayList<RehearsalReport.Stuck>()
        for ((si, range) in sentences.withIndex()) {
            val reasons = ArrayList<StuckReason>()
            pauseBySentence[si]?.let { reasons.add(StuckReason.Pause(it)) }
            if (si in rereadIn) reasons.add(StuckReason.Reread)
            if (si in lostIn) reasons.add(StuckReason.Lost)
            val spoken = range.filter { tokens[it].kind != TokenKind.NOTE }
            if (spoken.size >= 4) {
                val t0 = times[spoken.first()]
                val t1 = times[spoken.last()]
                if (t0 != null && t1 != null && t1 > t0 && pace > 0) {
                    val u = spoken.sumOf { unitCount(tokens[it], unit) }
                    if (u / (t1 - t0) * 60 < SLOW_FRACTION * pace) reasons.add(StuckReason.Slow)
                }
            }
            if (reasons.isEmpty()) continue
            val text = spoken.joinToString(" ") { tokens[it].display }
            if (text.isEmpty()) continue
            stuck.add(RehearsalReport.Stuck(si, text, reasons))
            if (stuck.size == MAX_STUCK) break
        }

        return RehearsalReport(kind, duration ?: readingSeconds, pace, unit, pauseCount, pauseSeconds, stuck)
    }

    /**
     * Sentence boundaries: end punctuation or a line break (the SRT rule
     * without its line budget). Ranges cover every token, notes included.
     */
    fun sentenceRanges(tokens: List<DisplayToken>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in tokens.indices) {
            val d = tokens[i].display
            val ends = d.endsWith(".") || d.endsWith("!") || d.endsWith("?") || d.endsWith("…")
            val nextBreaks = if (i + 1 < tokens.size) tokens[i + 1].breakBefore else true
            if (ends || nextBreaks) {
                out.add(start..i)
                start = i + 1
            }
        }
        return out
    }

    /**
     * Korean pace is counted in syllables (digits count one each, a Latin word
     * about two); English pace in words. Notes count nothing.
     */
    fun unitCount(token: DisplayToken, unit: RehearsalReport.PaceUnit): Double {
        if (token.kind == TokenKind.NOTE) return 0.0
        return when (unit) {
            RehearsalReport.PaceUnit.WORDS_PER_MINUTE ->
                if (token.display.any { it.isLetterOrDigit() }) 1.0 else 0.0
            RehearsalReport.PaceUnit.SYLLABLES_PER_MINUTE -> {
                var n = 0.0
                var inLatin = false
                for (c in token.display) {
                    when {
                        c in '가'..'힣' -> { n += 1; inLatin = false }
                        c in '0'..'9' -> { n += 1; inLatin = false }
                        c in 'A'..'Z' || c in 'a'..'z' -> { if (!inLatin) { n += 2; inLatin = true } }
                        else -> inLatin = false
                    }
                }
                n
            }
        }
    }

    /**
     * Default pace used for the editor's time estimate until the reader has
     * rehearsed once: broadcast Korean runs 330–360 syllables a minute and a
     * presentation a little slower; English about 150 words a minute.
     */
    fun defaultPace(unit: RehearsalReport.PaceUnit): Double =
        if (unit == RehearsalReport.PaceUnit.WORDS_PER_MINUTE) 150.0 else 300.0
}

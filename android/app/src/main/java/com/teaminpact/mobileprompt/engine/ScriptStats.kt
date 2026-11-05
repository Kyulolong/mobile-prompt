package com.teaminpact.mobileprompt.engine

/** Counts and a reading-time estimate for the editor. Pure; mirror of iOS ScriptStats.swift. */
class ScriptStats(
    /** Characters excluding whitespace. */
    val characters: Int,
    /** Spoken tokens (notes excluded). */
    val words: Int,
    /** Estimated reading time at the pace used. */
    val seconds: Double,
    val unit: RehearsalReport.PaceUnit,
    /** The pace came from the reader's own rehearsal, not the default. */
    val measured: Boolean,
) {
    companion object {
        fun compute(script: String, paceKo: Double, paceEn: Double): ScriptStats {
            val unit = if (PromptEngine.language(script) == EngineLang.EN) RehearsalReport.PaceUnit.WORDS_PER_MINUTE
                       else RehearsalReport.PaceUnit.SYLLABLES_PER_MINUTE
            val plain = ScriptMarkup.tokenizeWithBreaks(script)
            val tokens = ScriptMarkup.apply(plain, script) ?: plain
            val characters = script.count { !it.isWhitespace() }
            val words = tokens.count { it.kind != TokenKind.NOTE }
            val units = tokens.sumOf { RehearsalStats.unitCount(it, unit) }
            val measuredPace = if (unit == RehearsalReport.PaceUnit.WORDS_PER_MINUTE) paceEn else paceKo
            val pace = if (measuredPace > 0) measuredPace else RehearsalStats.defaultPace(unit)
            return ScriptStats(characters, words, units / pace * 60, unit, measuredPace > 0)
        }
    }
}

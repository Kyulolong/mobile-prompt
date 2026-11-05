package com.teaminpact.mobileprompt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same fixture as MobilePromptTests/RehearsalStatsTests.swift. */
class RehearsalStatsTest {
    /** 40 tokens, four sentences of ten ("말1 … 말10." …). */
    private val tokens = (1..40).map { i -> DisplayToken("말$i" + if (i % 10 == 0) "." else "", breakBefore = false) }

    /** One move every 0.4 s from t=100, a 3 s gap after token 15, a re-read of 22–24 after reaching 25. */
    private val trail: List<WordTime> = buildList {
        var t = 100.0
        for (i in 0 until 40) {
            if (i == 16) t += 3.0
            add(WordTime(i, t))
            if (i == 25) for (back in 22..24) { t += 0.4; add(WordTime(back, t)) }
            t += 0.4
        }
    }

    private val events = listOf(RehearsalEvent.Reread(25, 22, 100 + 26 * 0.4 + 3.0))

    @Test
    fun reportNumbers() {
        val r = RehearsalStats.compute(tokens, trail, events, EngineLang.KO, RehearsalReport.Kind.REHEARSAL)!!
        assertEquals(RehearsalReport.Kind.REHEARSAL, r.kind)
        assertEquals(RehearsalReport.PaceUnit.SYLLABLES_PER_MINUTE, r.paceUnit)
        assertEquals(1, r.pauseCount)
        assertEquals(3.4, r.pauseSeconds, 1e-9)
        // 39 steps of 0.4 s + 3 s gap + the 3 re-read steps.
        assertEquals(39 * 0.4 + 3.0 + 3 * 0.4, r.seconds, 1e-9)
        // 말N: one syllable plus one per digit → 40 + 9 + 31*2 = 111 units.
        assertEquals(111 / r.seconds * 60, r.pace, 1e-9)
        assertEquals(listOf(1, 2), r.stuck.map { it.id })
        assertEquals(1, r.stuck[0].reasons.size)
        assertEquals(3.4, (r.stuck[0].reasons[0] as StuckReason.Pause).seconds, 1e-9)
        assertEquals(listOf<StuckReason>(StuckReason.Reread), r.stuck[1].reasons)
        assertEquals((11..20).joinToString(" ") { "말$it" + if (it == 20) "." else "" }, r.stuck[0].text)
    }

    @Test
    fun takeUsesRecordingLengthAndWordsForEnglish() {
        val r = RehearsalStats.compute(tokens, trail, emptyList(), EngineLang.EN, RehearsalReport.Kind.TAKE, 42.0)!!
        assertEquals(RehearsalReport.Kind.TAKE, r.kind)
        assertEquals(42.0, r.seconds, 0.0)
        assertEquals(RehearsalReport.PaceUnit.WORDS_PER_MINUTE, r.paceUnit)
        assertEquals(40 / (39 * 0.4 + 3.0 + 3 * 0.4) * 60, r.pace, 1e-9)
    }

    @Test
    fun nothingUsableYieldsNull() {
        assertNull(RehearsalStats.compute(tokens, listOf(WordTime(0, 1.0)), emptyList(), EngineLang.KO, RehearsalReport.Kind.REHEARSAL))
        assertNull(RehearsalStats.compute(emptyList(), trail, emptyList(), EngineLang.KO, RehearsalReport.Kind.REHEARSAL))
    }

    @Test
    fun sentencesAndUnits() {
        assertEquals(4, RehearsalStats.sentenceRanges(tokens).size)
        val mixed = listOf(
            DisplayToken("안녕하세요", false), DisplayToken("[쉬고]", false, kind = TokenKind.NOTE),
            DisplayToken("API", false), DisplayToken("2025년", false),
        )
        assertEquals(listOf(5.0, 0.0, 2.0, 5.0), mixed.map { RehearsalStats.unitCount(it, RehearsalReport.PaceUnit.SYLLABLES_PER_MINUTE) })
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0), mixed.map { RehearsalStats.unitCount(it, RehearsalReport.PaceUnit.WORDS_PER_MINUTE) })
    }
}

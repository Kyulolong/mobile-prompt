package com.teaminpact.mobileprompt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the subtitle cue algorithm. iOS runs the identical fixture
 * (MobilePromptTests/SrtTests.swift) against the same expected text, so an SRT
 * recorded on either phone comes out the same.
 */
class SrtTest {
    private val script = "안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.|시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요."

    private val tokens: List<DisplayToken> = script.split("|").flatMapIndexed { pi, para ->
        para.split(" ").mapIndexed { i, w -> DisplayToken(w, breakBefore = pi > 0 && i == 0) }
    }

    /** Recording starts at 100.0 s; one token every 0.4 s from 100.8 s; the take ends at token 20. */
    private val trail = (0..20).map { WordTime(it, 100.8 + it * 0.4) }

    private val expected = """
        |1
        |00:00:00,550 --> 00:00:04,000
        |안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를
        |
        |2
        |00:00:04,010 --> 00:00:04,800
        |소개해 드릴게요.
        |
        |3
        |00:00:04,810 --> 00:00:07,600
        |시중에 나온 프롬프터 앱들은 대부분 정해진 속도로
        |
        |4
        |00:00:07,610 --> 00:00:08,400
        |글자가 흘러가죠.
        |
        |5
        |00:00:08,410 --> 00:00:09,600
        |그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나
        |
        |""".trimMargin()

    @Test
    fun cuesMatchFixture() {
        assertEquals(expected, buildSrt(tokens, trail, 100.0))
    }

    @Test
    fun nothingUsableYieldsNull() {
        assertNull(buildSrt(tokens, emptyList(), 100.0))
        assertNull(buildSrt(emptyList(), trail, 100.0))
    }

    @Test
    fun cuesAreMonotonicAndNonOverlapping() {
        val srt = buildSrt(tokens, trail, 100.0)!!
        val times = srt.lines().filter { "-->" in it }.map { line ->
            val (a, b) = line.split(" --> ").map(::seconds)
            a to b
        }
        assertEquals(5, times.size)
        var lastEnd = -1.0
        for ((start, end) in times) {
            assertTrue("start $start after previous end $lastEnd", start > lastEnd)
            assertTrue("end $end after start $start", end > start)
            lastEnd = end
        }
    }

    private fun seconds(stamp: String): Double {
        val p = stamp.replace(',', ':').split(":").map { it.toDouble() }
        return p[0] * 3600 + p[1] * 60 + p[2] + p[3] / 1000
    }
}

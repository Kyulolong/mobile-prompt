package com.teaminpact.mobileprompt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same vectors as MobilePromptTests/ScriptMarkupTests.swift. */
class ScriptMarkupTest {
    private val norm = TokenNormalizer(EngineLang.KO)

    private fun engineTokens(script: String): List<DisplayToken> =
        prepareScript(ScriptMarkup.engineText(script), norm).tokens.map { DisplayToken(it.raw, it.breakBefore) }

    private fun applied(script: String): List<DisplayToken> = ScriptMarkup.apply(engineTokens(script), script)!!

    @Test
    fun engineTextBlanksNotesAndKeepsEverythingElse() {
        assertEquals("안녕 [__] 여러분", ScriptMarkup.engineText("안녕 [쉬고] 여러분"))
        assertEquals("[_ __ __] 시작", ScriptMarkup.engineText("[아 잠깐 쉬고] 시작"))
        assertEquals("문장[1] 그대로", ScriptMarkup.engineText("문장[1] 그대로"))
        assertEquals("[__\n안녕", ScriptMarkup.engineText("[메모\n안녕"))
        assertEquals("[__]_ 다음", ScriptMarkup.engineText("[쉬고]. 다음"))
        assertEquals("**강조** 유지", ScriptMarkup.engineText("**강조** 유지"))
        assertEquals("[_____] go\r\n[_]", ScriptMarkup.engineText("[pause] go\r\n[x]"))
    }

    @Test
    fun tokenCountNeverChangesAndNotesHaveNoJamo() {
        val scripts = listOf(
            "안녕 [쉬고] 여러분", "[아 잠깐 쉬고] 시작", "문장[1] 그대로", "[메모\n안녕", "[쉬고]. 다음",
            "**강조 문구** 끝", "[pause] go\r\n[x]", "a　[b] c", "**", "****",
        )
        for (script in scripts) {
            val plain = prepareScript(script, norm).tokens
            val marked = prepareScript(ScriptMarkup.engineText(script), norm).tokens
            assertEquals(script, plain.size, marked.size)
            assertEquals(script, plain.size, ScriptMarkup.tokenize(script).size)
            val kinds = ScriptMarkup.apply(marked.map { DisplayToken(it.raw, it.breakBefore) }, script)!!.map { it.kind }
            for ((i, t) in marked.withIndex()) {
                if (kinds[i] == TokenKind.NOTE) assertTrue("note '${plain[i].raw}' must have no jamo", t.jamo.isEmpty())
                else assertEquals("non-note '${plain[i].raw}' keeps its jamo", plain[i].jamo, t.jamo)
            }
        }
        // A multi-word note: every piece is unspeakable.
        assertTrue(prepareScript(ScriptMarkup.engineText("[아 잠깐 쉬고] 시작"), norm).tokens.take(3).all { it.jamo.isEmpty() })
    }

    @Test
    fun notesAreDisplayedVerbatimButNeverMatched() {
        val t = applied("안녕 [쉬고] 여러분")
        assertEquals(listOf("안녕", "[쉬고]", "여러분"), t.map { it.display })
        assertEquals(listOf(TokenKind.PLAIN, TokenKind.NOTE, TokenKind.PLAIN), t.map { it.kind })
        assertEquals("[쉬고]", t[1].raw)

        assertEquals(listOf(TokenKind.NOTE, TokenKind.NOTE, TokenKind.NOTE, TokenKind.PLAIN), applied("[아 잠깐 쉬고] 시작").map { it.kind })

        val line = applied("[메모 **굵게** 끝\n다음")
        assertEquals(listOf(TokenKind.NOTE, TokenKind.NOTE, TokenKind.NOTE, TokenKind.PLAIN), line.map { it.kind })
        assertEquals("**굵게**", line[1].display) // inside a note nothing is interpreted

        assertEquals(listOf(TokenKind.PLAIN, TokenKind.PLAIN), applied("문장[1] 그대로").map { it.kind })
        assertEquals(listOf(TokenKind.NOTE, TokenKind.PLAIN), applied("[pause] go").map { it.kind })
    }

    @Test
    fun emphasis() {
        val t = applied("**강조 문구** 끝")
        assertEquals(listOf("강조", "문구", "끝"), t.map { it.display })
        assertEquals(listOf(TokenKind.EMPHASIS, TokenKind.EMPHASIS, TokenKind.PLAIN), t.map { it.kind })

        assertEquals(listOf("**", "혼자"), applied("** 혼자").map { it.display })
        assertEquals(listOf(TokenKind.PLAIN, TokenKind.PLAIN), applied("** 혼자").map { it.kind })

        val punct = applied("**강조**, 다음")
        assertEquals(listOf("강조,", "다음"), punct.map { it.display })
        assertEquals(listOf(TokenKind.EMPHASIS, TokenKind.PLAIN), punct.map { it.kind })

        assertEquals(
            listOf(TokenKind.EMPHASIS, TokenKind.PLAIN, TokenKind.EMPHASIS, TokenKind.EMPHASIS),
            applied("**a** b\n**c d").map { it.kind },
        )

        assertEquals(listOf("****"), applied("****").map { it.display })
    }

    @Test
    fun srtUsesDisplayTextAndSkipsNotes() {
        val tokens = applied("**안녕하세요** 여러분. [쉬고] 다음 문장입니다.")
        val trail = tokens.indices.map { WordTime(it, 100.5 + it * 0.4) }
        val srt = buildSrt(tokens, trail, 100.0)!!
        assertTrue(srt, "안녕하세요 여러분." in srt)
        assertTrue(srt, "**" !in srt && "[" !in srt)
    }

    @Test
    fun stripMarkers() {
        assertEquals("저희 팀은", ScriptMarkup.stripMarkers("**저희 팀**은"))
    }
}

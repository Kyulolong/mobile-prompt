package com.teaminpact.mobileprompt.engine

import com.teaminpact.mobileprompt.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Korean sherpa-onnx model emits text without word spacing, so the web
 * engine's exact-token re-seek can never fire. The Android extension recovers
 * through jamo n-gram voting. Inputs below are real recognizer output captured
 * from the emulator self test (misheard syllables included).
 */
class NgramReseekTest {
    private val cfg = AlignConfig(
        minQueryJamo = 6, accept = 0.45, forwardBase = 48, forwardPenalty = 0.15,
        backwardPenalty = 0.6, reseekAccept = 0.34, lostThreshold = 5, ngramReseek = true,
    )
    private val script = prepareScript(AppSettings.SAMPLE_SCRIPT_KO, TokenNormalizer(EngineLang.KO))

    private fun tokenIndexOf(raw: String) = script.tokens.indexOfFirst { it.raw == raw }.also { assertTrue("token '$raw' exists", it >= 0) }

    /**
     * Push the same unspaced chunk until the engine declares itself lost and
     * re-seeks; returns the first push that moved (re-pushing the already
     * consumed words afterwards is a miss by design, as on iOS).
     */
    private fun pushUntilJump(a: Aligner, chunk: String): AlignResult {
        var r: AlignResult? = null
        for (k in 0 until cfg.lostThreshold + 1) {
            r = a.push(listOf(chunk), 1000.0 + k * 400)
            if (r.moved) return r
        }
        return r!!
    }

    @Test
    fun recoversFromUnspacedRecognizerOutputAfterSkip() {
        val a = Aligner(script, cfg).also { it.reset(0) }
        // Reader skipped to the "numbers" paragraph; recognizer heard this (with errors).
        val r = pushUntilJump(a, "도그대로맞추고서넘어갑니다. 무악과전국무료에요")
        assertTrue("should have moved by re-seek", r.moved)
        val target = tokenIndexOf("무료예요.")
        assertTrue("landed at ${r.token}, expected near $target", r.token in (target - 2)..(target + 1))
    }

    @Test
    fun recoversFromExactUnspacedChunk() {
        val a = Aligner(script, cfg).also { it.reset(0) }
        val r = pushUntilJump(a, "예를들어2025년30퍼센트이런표현도그대로맞춰서넘어갑니다")
        assertTrue(r.moved)
        assertEquals(tokenIndexOf("넘어갑니다."), r.token)
    }

    @Test
    fun adLibNeverJumps() {
        val a = Aligner(script, cfg).also { it.reset(0) }
        val r = pushUntilJump(a, "그러니까말하자면아무튼뭐랄까그냥네그렇죠")
        assertFalse(r.moved)
        assertEquals(0, r.token)
        assertTrue(r.lost)
    }

    @Test
    fun offByDefaultKeepsWebBehavior() {
        val a = Aligner(script, cfg.copy(ngramReseek = false)).also { it.reset(0) }
        val r = pushUntilJump(a, "예를들어2025년30퍼센트이런표현도그대로맞춰서넘어갑니다")
        assertFalse("web engine has no n-gram recovery", r.moved)
        assertTrue(r.lost)
    }
}

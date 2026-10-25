package com.teaminpact.mobileprompt.engine

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
    private val script = prepareScript(SCRIPT, TokenNormalizer(EngineLang.KO))

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

/** The sample script the recognizer output above was captured against. */
private const val SCRIPT = """안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.

시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요. 특히 한국어는 인식이 잘 안 되는 경우가 많았고요.

그래서 생각을 바꿨습니다. 글자를 일정한 속도로 미는 게 아니라, 제 목소리를 듣고 지금 어디를 읽고 있는지 실시간으로 따라오게 만든 거예요.

잠깐 멈춰도 화면은 그대로 기다려 주고요. 중간에 애드립을 살짝 넣어도 위치를 잃지 않아요. 다시 대본으로 돌아오면 자연스럽게 이어서 따라옵니다.

숫자도 문제없어요. 예를 들어 2025년, 30퍼센트, 이런 표현도 그대로 맞춰서 넘어갑니다.

무엇보다 전부 무료예요. 인터넷 없이 폰에서도 돌아가고, 별도의 결제나 API 키도 필요 없습니다.

여기까지 읽는 동안 화면이 제 속도에 맞춰 부드럽게 내려왔다면, 성공입니다. 오늘 영상은 여기까지고요, 다음에 더 유용한 걸로 찾아올게요. 규로롱 구독과 좋아요 잊지 마세요!"""

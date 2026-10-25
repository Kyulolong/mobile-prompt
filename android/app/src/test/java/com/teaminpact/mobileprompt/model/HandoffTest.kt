package com.teaminpact.mobileprompt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** The handoff contract with Perplz (docs/perplz-integration.md), as the app enforces it. */
class HandoffTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun parsesScriptAutostartAndReturn() {
        val script = "안녕하세요, 저는 퍼플즈 3기 프로젝트를 소개합니다.\n\n둘째 줄 + 더하기 기호."
        val url = "voiceprompter://script?s=${enc(script)}&autostart=1&return=${enc("perplz://project/123/video")}"
        val r = HandoffRequest.parse(url)!!
        assertEquals(script, r.text)
        assertTrue(r.autostart)
        assertEquals("perplz://project/123/video", r.returnUrl)
    }

    @Test
    fun defaultsWithoutOptionalParams() {
        val r = HandoffRequest.parse("voiceprompter://script?s=hello%20world")!!
        assertEquals("hello world", r.text)
        assertFalse(r.autostart)
        assertNull(r.returnUrl)
    }

    @Test
    fun plusIsLiteral() {
        // encodeURIComponent never produces '+', so a literal '+' must survive.
        assertEquals("a+b", HandoffRequest.parse("voiceprompter://script?s=a+b")!!.text)
    }

    @Test
    fun rejectsMissingOrEmptyScript() {
        assertNull(HandoffRequest.parse("voiceprompter://script"))
        assertNull(HandoffRequest.parse("voiceprompter://script?autostart=1"))
        assertNull(HandoffRequest.parse("voiceprompter://script?s=%20%20"))
    }

    @Test
    fun rejectsOversizedScript() {
        val big = "가".repeat(6000) // 18 KB in UTF-8
        assertNull(HandoffRequest.parse("voiceprompter://script?s=${enc(big)}"))
        val ok = "가".repeat(5000) // 15 KB
        assertEquals(5000, HandoffRequest.parse("voiceprompter://script?s=${enc(ok)}")!!.text.length)
    }

    @Test
    fun rejectsOtherSchemesAndHosts() {
        assertNull(HandoffRequest.parse("https://perplz.com/prompter?s=hi"))
        assertNull(HandoffRequest.parse("voiceprompter://other?s=hi"))
        assertNull(HandoffRequest.parse("voiceprompter://scripts?s=hi"))
        assertNull(HandoffRequest.parse(null))
    }

    @Test
    fun dropsDisallowedReturnUrlButKeepsScript() {
        val r = HandoffRequest.parse("voiceprompter://script?s=hi&return=${enc("https://evil.example/x")}")!!
        assertEquals("hi", r.text)
        assertNull(r.returnUrl)
        val ok = HandoffRequest.parse("voiceprompter://script?s=hi&return=${enc("https://perplz.com/p/1")}")!!
        assertEquals("https://perplz.com/p/1", ok.returnUrl)
    }
}

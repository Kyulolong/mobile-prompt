package com.teaminpact.mobileprompt.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays outputs recorded from the TypeScript engine (the web app's, used
 * verbatim by iOS) against the Kotlin port. Any divergence here means Android
 * would track a script differently from iPhone. Regenerate the JSON with the
 * generator in the repo's engine-src tooling when the TS engine changes.
 */
class EngineGoldenTest {
    private val golden: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResource("engine-golden.json")!!.readText()
        JSONObject(text)
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private fun lang(code: String) = EngineLang.from(code)

    @Test
    fun normalizationMatchesWebEngine() {
        val arr = golden.getJSONArray("norm")
        assertTrue(arr.length() > 20)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val raw = o.getString("raw")
            assertEquals("ko norm of '$raw'", o.getString("ko"), Jamo.tokenJamo(raw))
            assertEquals("en norm of '$raw'", o.getString("en"), TokenNormalizer.tokenLatin(raw))
        }
    }

    @Test
    fun scriptPreparationMatchesWebEngine() {
        val arr = golden.getJSONArray("scripts")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = prepareScript(o.getString("text"), TokenNormalizer(lang(o.getString("lang"))))
            val toks = o.getJSONArray("tokens")
            assertEquals("token count (script $i)", toks.length(), p.tokens.size)
            assertEquals("jamo length (script $i)", o.getInt("jamoLength"), p.jamo.length)
            assertEquals("index size (script $i)", o.getInt("indexSize"), p.index.size)
            for (k in 0 until toks.length()) {
                val t = toks.getJSONObject(k)
                val pt = p.tokens[k]
                assertEquals(t.getString("raw"), pt.raw)
                assertEquals("jamo of '${pt.raw}'", t.getString("jamo"), pt.jamo)
                assertEquals("breakBefore of '${pt.raw}'", t.getBoolean("breakBefore"), pt.breakBefore)
                assertEquals("jamoStart of '${pt.raw}'", t.getInt("jamoStart"), pt.jamoStart)
            }
        }
    }

    @Test
    fun alignmentMatchesWebEngineStepForStep() {
        val arr = golden.getJSONArray("align")
        for (i in 0 until arr.length()) {
            val scenario = arr.getJSONObject(i)
            val cfg = configFrom(scenario.getJSONObject("cfg"))
            val p = prepareScript(scenario.getString("text"), TokenNormalizer(lang(scenario.getString("lang"))))
            val a = Aligner(p, cfg)
            a.reset(0)
            val steps = scenario.getJSONArray("steps")
            var moves = 0
            for (k in 0 until steps.length()) {
                val s = steps.getJSONObject(k)
                if (s.has("seek")) a.seekToken(s.getInt("seek"))
                if (s.has("reset")) a.reset(s.getInt("reset"))
                val r = a.push(s.getJSONArray("words").strings(), s.getDouble("now"))
                val want = s.getJSONObject("result")
                val where = "scenario $i step $k words=${s.getJSONArray("words")}"
                assertEquals("token @ $where", want.getInt("token"), r.token)
                assertEquals("moved @ $where", want.getBoolean("moved"), r.moved)
                assertEquals("lost @ $where", want.getBoolean("lost"), r.lost)
                assertEquals("confidence @ $where", want.getDouble("confidence"), r.confidence, 1e-9)
                assertEquals("tokensPerSec @ $where", want.getDouble("tokensPerSec"), a.tokensPerSec, 1e-9)
                assertEquals("cursorJamo @ $where", want.getInt("cursorJamo"), a.cursorJamo)
                assertEquals("confirmedToken @ $where", want.getInt("confirmedToken"), a.confirmedToken)
                if (r.moved) moves++
            }
            assertTrue("scenario $i should actually move the cursor", moves > 10)
        }
    }

    @Test
    fun languageDetection() {
        assertEquals(EngineLang.KO, PromptEngine.language("안녕하세요 여러분"))
        assertEquals(EngineLang.EN, PromptEngine.language("Hello everyone, welcome back"))
        assertEquals(EngineLang.KO, PromptEngine.language("2025 30% !!!"))
        // Mostly Latin letters → English, even with a Korean word in it (ratio < 15%).
        assertEquals(EngineLang.EN, PromptEngine.language("한국어 script with some English words in it"))
        // Korean script with a few English brand names stays Korean.
        assertEquals(EngineLang.KO, PromptEngine.language("오늘은 AI 프롬프터 앱을 소개합니다. API 키도 필요 없어요."))
    }

    private fun configFrom(o: JSONObject): AlignConfig {
        val d = AlignConfig()
        return AlignConfig(
            back = o.optInt("back", d.back),
            forwardBase = o.optInt("forwardBase", d.forwardBase),
            accept = o.optDouble("accept", d.accept),
            reseekAccept = o.optDouble("reseekAccept", d.reseekAccept),
            lostThreshold = o.optInt("lostThreshold", d.lostThreshold),
            maxQueryJamo = o.optInt("maxQueryJamo", d.maxQueryJamo),
            minQueryJamo = o.optInt("minQueryJamo", d.minQueryJamo),
            maxReseekCandidates = o.optInt("maxReseekCandidates", d.maxReseekCandidates),
            backwardPenalty = o.optDouble("backwardPenalty", d.backwardPenalty),
            forwardPenalty = o.optDouble("forwardPenalty", d.forwardPenalty),
        )
    }
}

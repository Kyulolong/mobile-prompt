package com.teaminpact.mobileprompt.engine

/** What the prompter renders: one entry per script token. */
data class DisplayToken(val raw: String, val breakBefore: Boolean)

/** One alignment step's outcome, as the prompter consumes it. */
class AlignUpdate(
    val token: Int,
    val moved: Boolean,
    val lost: Boolean,
    val confidence: Double,
    val tokensPerSec: Double,
)

/**
 * Facade over the alignment engine with the same shape as the iOS app's
 * AlignEngine (which drives the web engine through JavaScriptCore). Here the
 * engine is native Kotlin, so no bridge is needed — but keeping the surface
 * identical keeps the prompter view model a 1:1 port. Main-thread only.
 */
class PromptEngine {
    private var prepared: PreparedScript? = null
    private var aligner: Aligner? = null

    /** Load a script; returns its display tokens. */
    fun load(script: String, config: AlignConfig = AlignConfig(), lang: EngineLang = EngineLang.KO): List<DisplayToken> {
        val p = prepareScript(script, TokenNormalizer(lang))
        prepared = p
        aligner = Aligner(p, config).also { it.reset(0) }
        return p.tokens.map { DisplayToken(it.raw, it.breakBefore) }
    }

    /** `nowMs` matches the web engine's performance.now() milliseconds. */
    fun push(words: List<String>, nowMs: Double): AlignUpdate? {
        val a = aligner ?: return null
        val r = a.push(words, nowMs)
        return AlignUpdate(r.token, r.moved, r.lost, r.confidence, a.tokensPerSec)
    }

    fun seek(token: Int) {
        aligner?.seekToken(token)
    }

    fun reset(startToken: Int = 0) {
        aligner?.reset(startToken)
    }

    companion object {
        /** Detects the matching language from the script text (port of AlignEngine.language). */
        fun language(for_: String): EngineLang {
            var hangul = 0
            var letters = 0
            var i = 0
            while (i < for_.length) {
                val cp = for_.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF) {
                    hangul++; letters++
                } else if (Character.isLetter(cp)) {
                    letters++
                }
            }
            if (letters == 0) return EngineLang.KO
            return if (hangul.toDouble() / letters > 0.15) EngineLang.KO else EngineLang.EN
        }
    }
}

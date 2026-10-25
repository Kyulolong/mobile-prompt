package com.teaminpact.mobileprompt.engine

import java.text.Normalizer

/**
 * Korean text normalization for forced alignment.
 *
 * Kotlin port of engine-src/jamo.ts (the web app's engine). Behavior is kept
 * identical so the Android aligner tracks exactly like the iOS/web ones:
 * compare at the JAMO level so a misheard 받침 costs one edit, not a syllable.
 */
object Jamo {
    private val CHO = arrayOf(
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )
    private val JUNG = arrayOf(
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ",
    )
    // Final consonants; compound ones (ㄳ, ㄵ…) are split into two so a partial
    // mishearing costs a single edit rather than a whole cluster.
    private val JONG = arrayOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3

    /** Decompose one code point into its jamo (non-Hangul passes through). */
    private fun appendJamoOfCodePoint(code: Int, out: StringBuilder) {
        if (code < HANGUL_BASE || code > HANGUL_LAST) {
            out.appendCodePoint(code)
            return
        }
        val s = code - HANGUL_BASE
        val jong = s % 28
        val jung = ((s - jong) / 28) % 21
        val cho = (s - jong) / 28 / 21
        out.append(CHO[cho]).append(JUNG[jung]).append(JONG[jong])
    }

    // --- Sino-Korean number reading (2025 -> 이천이십오) -----------------------
    private val DIGIT = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val SMALL_UNIT = arrayOf("", "십", "백", "천")
    private val BIG_UNIT = arrayOf("", "만", "억", "조", "경")

    private val LEADING_ZEROS = Regex("^0+(?=[0-9])")
    private val DIGIT_RUN = Regex("[0-9]+")
    private val NOT_KEPT = Regex("[^가-힣a-z0-9]")

    /** Read an integer string as Sino-Korean. Best-effort; used only for matching. */
    fun sinoKorean(numStr: String): String {
        var digits = numStr.filter { it in '0'..'9' }
        if (digits.isEmpty()) return ""
        digits = LEADING_ZEROS.replace(digits, "")
        if (digits == "0") return "영"
        // split into 4-digit groups from the right
        val groups = ArrayList<String>()
        var i = digits.length
        while (i > 0) {
            groups.add(0, digits.substring(maxOf(0, i - 4), i))
            i -= 4
        }
        val out = StringBuilder()
        val g = groups.size
        for (gi in 0 until g) {
            val group = groups[gi]
            val chunk = StringBuilder()
            val len = group.length
            for (k in 0 until len) {
                val d = group[k] - '0'
                if (d == 0) continue
                val unit = SMALL_UNIT[len - 1 - k]
                // 1 is implicit before 십/백/천 (십, not 일십)
                chunk.append(if (d == 1 && unit != "") "" else DIGIT[d]).append(unit)
            }
            if (chunk.isNotEmpty()) out.append(chunk).append(BIG_UNIT.getOrElse(g - 1 - gi) { "" })
        }
        return out.toString()
    }

    /**
     * Normalize a raw token (eojeol) into a canonical string:
     * - lowercases Latin so "AI"/"ai" match
     * - rewrites digit runs to their Sino-Korean reading
     * - drops emoji / punctuation that neither side reliably produces
     */
    fun canonical(token: String): String {
        var t = Normalizer.normalize(token.lowercase(), Normalizer.Form.NFC)
        t = DIGIT_RUN.replace(t) { sinoKorean(it.value) }
        t = NOT_KEPT.replace(t, "")
        return t
    }

    /** Convert an arbitrary string to its jamo stream. */
    fun toJamo(text: String): String {
        val sb = StringBuilder(text.length * 3)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            appendJamoOfCodePoint(cp, sb)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** canonical() then toJamo() — the form the aligner actually compares. */
    fun tokenJamo(token: String): String = toJamo(canonical(token))
}

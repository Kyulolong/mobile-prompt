package com.teaminpact.mobileprompt.engine

import java.text.Normalizer

enum class EngineLang(val code: String) {
    KO("ko"), EN("en");

    companion object {
        fun from(code: String?): EngineLang = if (code == "en") EN else KO
    }
}

/**
 * Language-switchable token normalizer (port of engine-src/norm.ts).
 *
 * Korean uses jamo decomposition; Latin-script languages use lowercase letters
 * with punctuation stripped and digits expanded to spoken words, so "2025"
 * matches whether STT emits "2025" or "twenty twenty five".
 *
 * The web engine keeps the language in module state; here it is an instance
 * so a script and its aligner always agree on the normalization they used.
 */
class TokenNormalizer(val lang: EngineLang) {
    fun norm(raw: String): String = if (lang == EngineLang.KO) Jamo.tokenJamo(raw) else tokenLatin(raw)

    companion object {
        private val ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen",
        )
        private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        private fun belowHundred(n: Int): String {
            if (n < 20) return ONES[n]
            val t = TENS[n / 10]
            val r = n % 10
            return if (r != 0) t + ONES[r] else t
        }

        private fun belowThousand(n: Int): String {
            val h = n / 100
            val r = n % 100
            var s = if (h != 0) ONES[h] + "hundred" else ""
            if (r != 0) s += belowHundred(r)
            return s.ifEmpty { "zero" }
        }

        /** Deterministic spoken form for a digit string; both sides get the same one. */
        fun numberWords(digits: String): String {
            val n = digits.toLongOrNull() ?: return digits
            // Years read pairwise: 2025 -> "twenty twentyfive", 1999 -> "nineteen ninetynine"
            if (digits.length == 4 && n >= 1100 && n <= 2999 && n % 100 != 0L) {
                return belowHundred((n / 100).toInt()) + belowHundred((n % 100).toInt())
            }
            if (n < 100) return belowHundred(n.toInt())
            if (n < 1000) return belowThousand(n.toInt())
            if (n < 1_000_000) {
                val th = belowThousand((n / 1000).toInt()) + "thousand"
                val r = (n % 1000).toInt()
                return if (r != 0) th + belowThousand(r) else th
            }
            if (n < 1_000_000_000) {
                val m = belowThousand((n / 1_000_000).toInt()) + "million"
                val r = n % 1_000_000
                return if (r != 0L) m + numberWords(r.toString()) else m
            }
            return digits
        }

        // Insertion order matters: the first suffix that matches wins.
        private val ORDINAL_MAP = listOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
            "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        )

        private val COMBINING = Regex("[̀-ͯ]")
        // JS `\b` after the suffix: next char is not an ASCII word char.
        private val ORDINAL = Regex("([0-9]+)(st|nd|rd|th)(?![A-Za-z0-9_])")
        private val DIGIT_RUN = Regex("[0-9]+")
        private val NOT_KEPT = Regex("[^a-z가-힣]")

        fun tokenLatin(raw: String): String {
            var s = raw.lowercase()
            // strip diacritics (café -> cafe); recompose so Hangul syllables — which
            // NFD also decomposes — come back intact for the filter below
            s = Normalizer.normalize(s, Normalizer.Form.NFD)
            s = COMBINING.replace(s, "")
            s = Normalizer.normalize(s, Normalizer.Form.NFC)
            s = s.replace("%", " percent ").replace("&", " and ").replace("+", " plus ")
            // ordinals: 21st -> twentyfirst (approximate: cardinal + suffix fix)
            s = ORDINAL.replace(s) { m ->
                val w = numberWords(m.groupValues[1])
                var out: String? = null
                for ((card, ord) in ORDINAL_MAP) {
                    if (w.endsWith(card)) {
                        out = w.substring(0, w.length - card.length) + ord
                        break
                    }
                }
                out ?: if (w.endsWith("y")) w.substring(0, w.length - 1) + "ieth" else w + "th"
            }
            s = DIGIT_RUN.replace(s) { numberWords(it.value) }
            // Keep Latin letters AND Hangul (drops punctuation: don't -> dont).
            s = NOT_KEPT.replace(s, "")
            // Decompose any Hangul to jamo; Latin passes through unchanged.
            return Jamo.toJamo(s)
        }
    }
}

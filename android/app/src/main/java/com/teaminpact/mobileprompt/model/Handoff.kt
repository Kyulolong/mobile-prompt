package com.teaminpact.mobileprompt.model

import java.io.ByteArrayOutputStream

/**
 * A script handed to the prompter by another app (Perplz) through the
 * `voiceprompter://script?...` URL scheme. See docs/perplz-integration.md.
 *
 *   s          percent-encoded script text (required, ≤ 16 KB UTF-8)
 *   autostart  "1" → open the prompter immediately instead of the editor
 *   return     URL to offer after a recording is saved; only perplz:// and
 *              https://perplz.com/ are honored, anything else is dropped
 *
 * Parsing works on the raw string (no android.net.Uri) so it is unit-testable
 * and so '+' stays '+', matching encodeURIComponent on the sending side.
 */
data class HandoffRequest(val text: String, val autostart: Boolean, val returnUrl: String?) {
    companion object {
        const val SCHEME = "voiceprompter"
        const val HOST = "script"
        const val MAX_BYTES = 16 * 1024
        val ALLOWED_RETURN_PREFIXES = listOf("perplz://", "https://perplz.com/")

        fun parse(url: String?): HandoffRequest? {
            if (url == null) return null
            val prefix = "$SCHEME://$HOST"
            if (!url.lowercase().startsWith(prefix)) return null
            val rest = url.substring(prefix.length)
            if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
            val query = rest.substringAfter('?', "").substringBefore('#')
            var text = ""
            var autostart = false
            var returnUrl: String? = null
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val name = percentDecode(pair.substringBefore('='))
                val value = percentDecode(pair.substringAfter('=', ""))
                when (name) {
                    "s" -> text = value
                    "autostart" -> autostart = value == "1" || value.equals("true", ignoreCase = true)
                    "return" -> if (ALLOWED_RETURN_PREFIXES.any { value.lowercase().startsWith(it) }) returnUrl = value
                }
            }
            text = text.trim()
            if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            return HandoffRequest(text, autostart, returnUrl)
        }

        /** %XX → bytes → UTF-8. Unlike URLDecoder, '+' is left alone. */
        private fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) {
                    val hex = s.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16)
                    if (b != null) {
                        out.write(b)
                        i += 3
                        continue
                    }
                }
                for (byte in c.toString().toByteArray(Charsets.UTF_8)) out.write(byte.toInt())
                i++
            }
            return out.toString("UTF-8")
        }
    }
}

package com.teaminpact.mobileprompt.model

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Turns a shared or picked text file into script text. Scripts are small, so
 * anything past [MAX_BYTES] is refused rather than truncated silently. Old
 * Korean `.txt` files are often EUC-KR, so that is the fallback when the bytes
 * are not valid UTF-8.
 */
object TextDecoding {
    const val MAX_BYTES = 1 shl 20 // 1 MiB

    /** Null when the file can't be opened, is too large, or isn't text we can decode. */
    fun read(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { readCapped(it) }?.let(::decode)
    } catch (_: Exception) {
        null // SecurityException, FileNotFoundException, IOException: nothing to import
    }

    fun decode(bytes: ByteArray): String? {
        val (body, bom) = stripBom(bytes)
        if (bom != null) return String(body, bom)
        return tryDecode(body, Charsets.UTF_8)
            ?: (if (Charset.isSupported("EUC-KR")) tryDecode(body, Charset.forName("EUC-KR")) else null)
            ?: String(body, Charsets.UTF_8) // lenient: replacement chars beat losing the file
    }

    private fun stripBom(b: ByteArray): Pair<ByteArray, Charset?> = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            b.copyOfRange(3, b.size) to Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
            b.copyOfRange(2, b.size) to Charsets.UTF_16BE
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
            b.copyOfRange(2, b.size) to Charsets.UTF_16LE
        else -> b to null
    }

    private fun tryDecode(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }
}

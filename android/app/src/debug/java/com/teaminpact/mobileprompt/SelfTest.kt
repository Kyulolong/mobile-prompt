package com.teaminpact.mobileprompt

import android.content.Context
import android.content.Intent
import android.util.Log
import com.teaminpact.mobileprompt.engine.AlignConfig
import com.teaminpact.mobileprompt.engine.EngineLang
import com.teaminpact.mobileprompt.engine.PromptEngine
import com.teaminpact.mobileprompt.speech.SpeechModels
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only end-to-end check of recognition → alignment without a microphone:
 * decodes a synthesized reading of the sample script (assets/selftest/<lang>.wav)
 * through the real recognizer and aligner, logging progress under tag "SelfTest".
 *
 *   adb shell am start -n com.teaminpact.mobileprompt.debug/com.teaminpact.mobileprompt.MainActivity --es selftest ko
 *   adb logcat -s SelfTest
 */
object SelfTest {
    private const val TAG = "SelfTest"

    fun maybeRun(context: Context, intent: Intent?) {
        val lang = intent?.getStringExtra("selftest") ?: return
        Thread({ run(context.applicationContext, EngineLang.from(lang)) }, "selftest").start()
    }

    private fun run(context: Context, lang: EngineLang) {
        try {
            val t0 = System.nanoTime()
            val recognizer = SpeechModels.get(context, lang)
            Log.i(TAG, "model ${lang.code} loaded in ${"%.2f".format((System.nanoTime() - t0) / 1e9)} s")

            val samples = readWav(context.assets.open("selftest/${lang.code}.wav").readBytes())
            val audioSec = samples.size / 16000.0
            Log.i(TAG, "audio ${"%.1f".format(audioSec)} s")

            // The text the WAV was synthesized from (not the app's current sample).
            val script = context.assets.open("selftest/${lang.code}.txt").bufferedReader().use { it.readText() }.trim()
            val engine = PromptEngine()
            val tokens = engine.load(
                script,
                AlignConfig(minQueryJamo = 6, accept = 0.45, forwardBase = 48, forwardPenalty = 0.15, backwardPenalty = 0.6, reseekAccept = 0.34, lostThreshold = 5, ngramReseek = true),
                lang,
            )
            // Simulate the reader skipping a paragraph: drop 9 s of audio at 40 %.
            val skipAt = (samples.size * 0.4).toInt()
            val skipLen = 9 * 16000
            var skipped = false

            val stream = recognizer.createStream()
            val chunk = 1600 // 100 ms
            var i = 0
            var lastPushed: List<String> = emptyList()
            var lastToken = 0
            var maxToken = 0
            var moves = 0
            var lostCount = 0
            val transcript = StringBuilder()
            val t1 = System.nanoTime()
            while (i < samples.size) {
                if (!skipped && i >= skipAt) {
                    skipped = true
                    i += skipLen
                    recognizer.reset(stream)
                    lastPushed = emptyList()
                    Log.i(TAG, "SKIP: jumped ${skipLen / 16000}s ahead at t=${"%.1f".format(skipAt / 16000.0)} (aligner at tok=$lastToken)")
                    continue
                }
                val n = minOf(chunk, samples.size - i)
                stream.acceptWaveform(samples.copyOfRange(i, i + n), 16000)
                i += n
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                val endpoint = recognizer.isEndpoint(stream)
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(8)
                if (words.isNotEmpty() && words != lastPushed) {
                    lastPushed = words
                    val r = engine.push(words, i / 16.0)
                    if (r != null) {
                        lastToken = r.token
                        if (r.moved) moves++
                        if (r.lost) lostCount++
                        maxToken = maxOf(maxToken, r.token)
                        Log.d(TAG, "t=${"%5.1f".format(i / 16000.0)} tok=${r.token}/${tokens.size} moved=${r.moved} lost=${r.lost} conf=${"%.2f".format(r.confidence)} | ${words.joinToString(" ")}")
                    }
                }
                if (endpoint) {
                    transcript.append(text.trim()).append(" / ")
                    Log.i(TAG, "endpoint @${"%.1f".format(i / 16000.0)}s: $text")
                    recognizer.reset(stream)
                    lastPushed = emptyList()
                }
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            transcript.append(recognizer.getResult(stream).text.trim())
            val procSec = (System.nanoTime() - t1) / 1e9
            stream.release()

            Log.i(TAG, "transcript: $transcript")
            Log.i(TAG, "RESULT lang=${lang.code} tokens=${tokens.size} lastToken=$lastToken maxToken=$maxToken moves=$moves lostPushes=$lostCount rtf=${"%.2f".format(procSec / audioSec)}")
        } catch (e: Throwable) {
            Log.e(TAG, "self test failed", e)
        }
    }

    /** PCM16 mono WAV → float samples in [-1, 1]. Walks RIFF chunks to find "data". */
    private fun readWav(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV" }
        var pos = 12
        var channels = 1
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") channels = bb.getShort(pos + 10).toInt()
            if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(len, bytes.size - dataStart)
                break
            }
            pos += 8 + len + (len and 1)
        }
        require(dataStart >= 0) { "no data chunk" }
        val frames = dataLen / 2 / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) out[f] = bb.getShort(dataStart + f * 2 * channels) / 32768f
        return out
    }
}

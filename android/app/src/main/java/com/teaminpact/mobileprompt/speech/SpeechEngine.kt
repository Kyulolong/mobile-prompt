package com.teaminpact.mobileprompt.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.engine.Clock
import com.teaminpact.mobileprompt.engine.EngineLang

/**
 * Process-wide cache of loaded recognizers. Loading the Korean model reads
 * ~130 MB from the asset pack and takes a few seconds, so each language is
 * loaded once and kept for the life of the process.
 */
object SpeechModels {
    private val cache = HashMap<EngineLang, OnlineRecognizer>()

    @Synchronized
    fun isLoaded(lang: EngineLang): Boolean = cache.containsKey(lang)

    @Synchronized
    fun get(context: Context, lang: EngineLang): OnlineRecognizer {
        cache[lang]?.let { return it }
        val dir = "models/${lang.code}"
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = threads,
                modelType = "zipformer",
            ),
            // Endpoint = "utterance ended": the stream is reset so the text
            // buffer doesn't grow forever. The aligner keeps its cursor across
            // resets, so they are invisible to the reader.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.4f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        val recognizer = OnlineRecognizer(assetManager = context.applicationContext.assets, config = config)
        cache[lang] = recognizer
        return recognizer
    }

    const val SAMPLE_RATE = 16000
}

/**
 * Streaming on-device speech recognition feeding the aligner.
 *
 * Owns its own AudioRecord (16 kHz mono), so recognition never competes with
 * the system recognizer for the microphone and can run while CameraX records
 * video with audio: Android lets one app hold several capture clients.
 * Emits the last few spoken words on the main thread, throttled like the iOS
 * engine so partial churn doesn't yank the aligner around.
 */
class SpeechEngine(private val context: Context) {
    sealed class Status {
        object Idle : Status()
        object Loading : Status()
        object Listening : Status()
        object Denied : Status()
        data class Error(val message: String) : Status()
    }

    /** Last few spoken words (tail), delivered on the main thread. */
    var onWords: ((List<String>) -> Unit)? = null
    var onStatus: ((Status) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var lang = EngineLang.KO
    /** Monotonic session tag; a stale worker's callbacks are ignored. */
    @Volatile private var session = 0
    private val tailWords = 8
    private val partialInterval = 0.2

    /** Switch the recognition language. Takes effect on the next start(). */
    fun setLanguage(l: EngineLang) {
        lang = l
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        stop()
        if (!hasPermission) {
            notify(session, Status.Denied)
            return
        }
        val id = ++session
        val l = lang
        notify(id, if (SpeechModels.isLoaded(l)) Status.Listening else Status.Loading)
        Thread({ run(id, l) }, "speech-engine").start()
    }

    fun stop() {
        session++
        notify(session, Status.Idle)
    }

    private fun run(id: Int, l: EngineLang) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val recognizer = try {
            SpeechModels.get(context, l)
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            notify(id, Status.Error(context.getString(R.string.model_load_failed)))
            return
        }
        if (id != session) return

        val minBuf = AudioRecord.getMinBufferSize(SpeechModels.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SpeechModels.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SpeechModels.SAMPLE_RATE * 2), // ≥ 1 s of PCM16
            )
        } catch (e: SecurityException) {
            notify(id, Status.Denied)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            notify(id, Status.Error(context.getString(R.string.mic_start_failed)))
            return
        }

        val stream = recognizer.createStream()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                notify(id, Status.Error(context.getString(R.string.mic_start_failed)))
                return
            }
            notify(id, Status.Listening)

            val chunk = ShortArray(SpeechModels.SAMPLE_RATE / 10) // 100 ms
            val floats = FloatArray(chunk.size)
            var lastPushed: List<String> = emptyList()
            var lastPartialPush = 0.0
            while (id == session) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) {
                    Log.e(TAG, "AudioRecord.read error $n")
                    notify(id, Status.Error(context.getString(R.string.mic_start_failed)))
                    return
                }
                if (n == 0) continue
                for (i in 0 until n) floats[i] = chunk[i] / 32768f
                stream.acceptWaveform(if (n == chunk.size) floats else floats.copyOf(n), SpeechModels.SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val endpoint = recognizer.isEndpoint(stream)
                val words = recognizer.getResult(stream).text.trim()
                    .split(WS).filter { it.isNotEmpty() }.takeLast(tailWords)
                // Skip repeats: an unchanged tail carries no new information.
                if (words.isNotEmpty() && words != lastPushed) {
                    val now = Clock.now()
                    if (endpoint || now - lastPartialPush >= partialInterval) {
                        lastPartialPush = now
                        lastPushed = words
                        main.post { if (id == session) onWords?.invoke(words) }
                    }
                }
                if (endpoint) {
                    recognizer.reset(stream)
                    lastPushed = emptyList()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recognition loop failed", e)
            notify(id, Status.Error(context.getString(R.string.speech_start_failed)))
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            stream.release()
        }
    }

    private fun notify(id: Int, status: Status) {
        main.post { if (id == session) onStatus?.invoke(status) }
    }

    companion object {
        private const val TAG = "SpeechEngine"
        private val WS = Regex("\\s+")
    }
}

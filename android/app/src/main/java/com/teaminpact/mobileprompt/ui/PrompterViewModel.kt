package com.teaminpact.mobileprompt.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.capture.CaptureController
import com.teaminpact.mobileprompt.engine.AlignConfig
import com.teaminpact.mobileprompt.engine.Clock
import com.teaminpact.mobileprompt.engine.DisplayToken
import com.teaminpact.mobileprompt.engine.EngineLang
import com.teaminpact.mobileprompt.engine.PromptEngine
import com.teaminpact.mobileprompt.engine.RehearsalEvent
import com.teaminpact.mobileprompt.engine.RehearsalReport
import com.teaminpact.mobileprompt.engine.RehearsalStats
import com.teaminpact.mobileprompt.engine.ScriptMarkup
import com.teaminpact.mobileprompt.engine.ScrollModel
import com.teaminpact.mobileprompt.engine.TokenKind
import com.teaminpact.mobileprompt.engine.WordTime
import com.teaminpact.mobileprompt.engine.buildSrt
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.speech.SpeechEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * The hub that wires everything together (port of the iOS PrompterViewModel):
 * speech -> aligner -> scroll + imperative word highlight on the text view.
 *
 * One instance per prompter session: PrompterScreen scopes it to its own
 * composition, so it is cleared (camera released, mic stopped) whenever the
 * prompter leaves the screen, and the next script starts from a clean slate.
 */
class PrompterViewModel(app: Application) : AndroidViewModel(app) {
    enum class PermissionState { UNKNOWN, GRANTED, DENIED }

    var tokens by mutableStateOf<List<DisplayToken>>(emptyList())
        private set
    var lost by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    /** 3-2-1 before recording starts; null when not counting. */
    var countdown by mutableStateOf<Int?>(null)
        private set
    var speechStatus by mutableStateOf<SpeechEngine.Status>(SpeechEngine.Status.Idle)
        private set
    var permissionState by mutableStateOf(PermissionState.UNKNOWN)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    /** Generated after a voice-mode recording ends; drives the share button. */
    var srtFile by mutableStateOf<File?>(null)
        private set

    val capture = CaptureController(app)
    val speech = SpeechEngine(app)
    val scroll = ScrollModel()
    private val engine = PromptEngine()

    /**
     * Aligner tuning carried over from iOS, where recognizer partials churn far
     * more than Web Speech's: ignore very short fragments, demand cleaner
     * matches, keep the window tight, penalize distance hard so the nearer of
     * two identical phrases always wins. Re-tune here for sherpa-onnx if needed.
     */
    private val alignOverrides = AlignConfig(
        minQueryJamo = 6,
        accept = 0.45,
        forwardBase = 48,
        forwardPenalty = 0.15,
        backwardPenalty = 0.6,
        reseekAccept = 0.34,
        lostThreshold = 5,
        // sherpa-onnx's Korean model emits no word spacing, so exact-token
        // re-seek never fires; recover by jamo n-gram voting instead.
        ngramReseek = true,
    )

    private var started = false
    private var voiceMode = true
    /** No camera at all; speech is independent of it on Android anyway. */
    var stageMode = false
        private set
    private var scriptText = ""
    private var textView: PromptTextView? = null
    private var currentHighlight = -1
    private var highlightColor: Int? = null
    private var speakingJob: Job? = null
    private var countdownJob: Job? = null
    private var langWatchdog: Job? = null

    /** Language actually driving recognition/alignment this session. */
    private var activeLang = EngineLang.KO
    /** Script language as detected at start (drives text locale / line breaking). */
    var scriptLang by mutableStateOf(EngineLang.KO)
        private set

    /**
     * (token, host-clock time) for every accepted cursor move in voice mode —
     * the whole session. Recording and rehearsal each use a slice of it.
     */
    private val trail = ArrayList<WordTime>()
    /** Trouble the aligner noticed (lost, re-reads), same clock as [trail]. */
    private val events = ArrayList<RehearsalEvent>()
    private var recordingTrailStart = 0
    private var recordingEventsStart = 0
    private var lastTakeTrail: List<WordTime> = emptyList()
    private var lastTakeEvents: List<RehearsalEvent> = emptyList()
    private var lastTakeSeconds = 0.0
    /** The last take was read in voice mode long enough to have a summary. */
    var takeReportAvailable by mutableStateOf(false)
        private set
    /** Whether the running take started in voice mode: only such a take has a real trail. */
    private var srtEligible = false
    private var settings: AppSettings? = null

    // MARK: rehearsal

    var rehearsalActive by mutableStateOf(false)
        private set
    var rehearsalSeconds by mutableStateOf(0)
        private set
    /** Set when a rehearsal or take summary is ready; drives the result sheet. */
    var report by mutableStateOf<RehearsalReport?>(null)
    /** Short transient message (e.g. "nothing was read yet"). */
    var toast by mutableStateOf<String?>(null)
        private set
    private var rehearsalJob: Job? = null
    private var rehearsalFinishJob: Job? = null
    private var toastJob: Job? = null
    /** Last token the reader can actually say — reaching it (then 1.5 s of silence) ends a rehearsal. */
    private var lastSpokenToken = -1

    init {
        capture.onRecordingStarted = {
            srtFile = null
            takeReportAvailable = false
            recordingTrailStart = trail.size
            recordingEventsStart = events.size
            srtEligible = voiceMode
        }
        capture.onRecordingFinished = {
            val slice = trail.subList(minOf(recordingTrailStart, trail.size), trail.size).toList()
            lastTakeTrail = slice
            lastTakeEvents = events.subList(minOf(recordingEventsStart, events.size), events.size).toList()
            lastTakeSeconds = capture.recordingSeconds.toDouble()
            takeReportAvailable = srtEligible && slice.size >= 2
            if (srtEligible && slice.isNotEmpty()) finishSRT(slice)
        }
    }

    /**
     * Read the script in voice mode without the camera rolling, then get a
     * summary: time, pace, pauses, and the sentences that caused trouble.
     */
    fun startRehearsal() {
        if (!voiceMode || capture.isRecording || countdown != null || tokens.isEmpty()) return
        trail.clear()
        events.clear()
        engine.reset(0)
        pendingJumpTarget = null
        lost = false
        scroll.jump(0.0)
        highlight(0)
        rehearsalActive = true
        rehearsalSeconds = 0
        rehearsalJob?.cancel()
        rehearsalJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                rehearsalSeconds++
            }
        }
    }

    fun finishRehearsal() {
        if (!rehearsalActive) return
        rehearsalActive = false
        rehearsalJob?.cancel()
        rehearsalJob = null
        rehearsalFinishJob?.cancel()
        rehearsalFinishJob = null
        val r = RehearsalStats.compute(tokens, trail.toList(), events.toList(), activeLang, RehearsalReport.Kind.REHEARSAL)
        if (r == null) {
            showToast(getApplication<Application>().getString(R.string.nothing_read))
            return
        }
        // A real read-through teaches the editor the reader's own pace.
        if (trail.size >= 20) {
            settings?.let { if (r.paceUnit == RehearsalReport.PaceUnit.WORDS_PER_MINUTE) it.paceEn = r.pace else it.paceKo = r.pace }
        }
        report = r
    }

    /** Summary of the take that just finished (voice mode only). */
    fun showTakeReport() {
        if (!takeReportAvailable) return
        report = RehearsalStats.compute(tokens, lastTakeTrail, lastTakeEvents, activeLang, RehearsalReport.Kind.TAKE, lastTakeSeconds) ?: return
    }

    private fun armRehearsalFinish(token: Int) {
        if (!rehearsalActive) return
        rehearsalFinishJob?.cancel()
        rehearsalFinishJob = null
        if (token < lastSpokenToken) return
        rehearsalFinishJob = viewModelScope.launch {
            delay(1500)
            finishRehearsal()
        }
    }

    private fun showToast(message: String) {
        toast = message
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2200)
            toast = null
        }
    }

    /** Called once per session by the screen after permissions were requested. */
    fun start(script: String, settings: AppSettings, micGranted: Boolean) {
        if (started) return
        started = true
        this.settings = settings
        voiceMode = settings.voiceMode
        stageMode = settings.stageMode
        scriptText = script
        highlightColor = highlightColor(settings.highlightStyle)
        scroll.readingLineFrac = settings.readingLineFrac.toFloat()
        scroll.autoDpPerSec = settings.autoSpeed.toFloat()
        scroll.mode = if (settings.voiceMode) ScrollModel.Mode.VOICE else ScrollModel.Mode.AUTO

        val chosen = PromptEngine.language(script)
        activeLang = chosen
        scriptLang = chosen
        currentSTTLang = chosen
        speech.setLanguage(chosen)
        // Markup: the engine sees notes as blanks (same whitespace, same token
        // count); the display gets bold/dim per token. If the two ever fail to
        // line up, the script is shown plain rather than wrong.
        val engineTokens = engine.load(ScriptMarkup.engineText(script), alignOverrides, chosen)
        tokens = ScriptMarkup.apply(engineTokens, script) ?: engine.load(script, alignOverrides, chosen)
        computeLangZones(chosen)
        lastSpokenToken = tokens.indexOfLast { t -> t.kind != TokenKind.NOTE && t.display.any { it.isLetterOrDigit() } }
        if (tokens.isEmpty()) {
            errorMessage = getApplication<Application>().getString(R.string.script_empty)
            return
        }

        if (voiceMode && !micGranted) {
            permissionState = PermissionState.DENIED
        } else {
            permissionState = PermissionState.GRANTED
        }

        scroll.start()
        lastProgressAt = Clock.now()
        langWatchdog?.cancel()
        langWatchdog = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (!voiceMode || currentSTTLang == activeLang) continue
                val now = Clock.now()
                if (now - lastProgressAt > 4.0 && now - lastLangSwitch > 2.0) switchSTT(activeLang, now)
            }
        }
        if (voiceMode && micGranted) startVoicePipeline()
    }

    private fun startVoicePipeline() {
        speech.onWords = { handleWords(it) }
        speech.onStatus = { st ->
            speechStatus = st
            // Another app took the mic: keep the script moving on its own
            // until it comes back.
            if (voiceMode) {
                if (st == SpeechEngine.Status.Silenced) scroll.mode = ScrollModel.Mode.AUTO
                else if (st == SpeechEngine.Status.Listening) scroll.mode = ScrollModel.Mode.VOICE
            }
        }
        speech.start()
    }

    /**
     * Toggle the camera off (stage) or on, mid-session. Turning it on is the
     * screen's job (it rebinds the preview); the aligner, scroll position and
     * highlight stay where they are either way.
     */
    fun setStageMode(on: Boolean) {
        if (on == stageMode) return
        stageMode = on
        if (on) {
            cancelCountdown()
            capture.unbind()
        }
    }

    fun stopAll() {
        cancelCountdown()
        rehearsalActive = false
        rehearsalJob?.cancel()
        rehearsalFinishJob?.cancel()
        langWatchdog?.cancel()
        langWatchdog = null
        speech.stop()
        scroll.stop()
        if (capture.isRecording) capture.stopRecording()
        speakingJob?.cancel()
    }

    /** The microphone can't be used from the background: pause recognition, resume on return. */
    fun onBackground() {
        if (voiceMode) speech.stop()
    }

    fun onForeground() {
        if (started && voiceMode && permissionState == PermissionState.GRANTED) startVoicePipeline()
    }

    /** Permission granted later (Settings round-trip): retry speech. */
    fun micPermissionGranted() {
        if (permissionState == PermissionState.DENIED) {
            permissionState = PermissionState.GRANTED
            if (voiceMode) startVoicePipeline()
        }
    }

    fun setMode(voice: Boolean, settings: AppSettings) {
        voiceMode = voice
        scroll.mode = if (voice) ScrollModel.Mode.VOICE else ScrollModel.Mode.AUTO
        if (voice) {
            if (speech.hasPermission) {
                permissionState = PermissionState.GRANTED
                startVoicePipeline()
            } else {
                permissionState = PermissionState.DENIED
            }
        } else {
            speech.stop()
            // A take that switches to auto-scroll mid-way loses its trail.
            if (capture.isRecording) srtEligible = false
        }
    }

    override fun onCleared() {
        stopAll()
        capture.unbind()
    }

    // MARK: text view wiring (called by the screen)

    fun attach(textView: PromptTextView, offsets: FloatArray, density: Float) {
        this.textView = textView
        scroll.surface = textView
        scroll.density = density
        val firstLayout = scroll.offsets.isEmpty()
        scroll.offsets = offsets
        if (firstLayout && offsets.isNotEmpty()) {
            scroll.jump(0.0)
            if (voiceMode) highlight(0)
        } else if (offsets.isNotEmpty()) {
            // Relayout (rotation, PiP in/out, font change): back on the reading
            // line at once, and the highlight span survives setContent.
            scroll.resnap()
            textView.setHighlight(currentHighlight, highlightColor)
        }
    }

    /**
     * PiP's play/pause button pauses the prompter — in voice mode that also
     * stops listening, so a paused floating window never holds the mic.
     */
    fun setPausedFromPip(paused: Boolean) {
        if (paused == isPaused) return
        isPaused = paused
        scroll.paused = paused
        if (!voiceMode || permissionState != PermissionState.GRANTED) return
        if (paused) speech.stop() else startVoicePipeline()
    }

    /** PiP's skip buttons: a few words back or forward. */
    fun jumpTokens(delta: Int) {
        if (tokens.isEmpty()) return
        seekTo((currentHighlight + delta).coerceIn(0, tokens.lastIndex))
    }

    fun togglePause() {
        isPaused = !isPaused
        scroll.paused = isPaused
    }

    /** Volume button / selfie remote: the same thing the big button does, or "finish" while rehearsing. */
    fun hardwareShutterPressed() {
        if (rehearsalActive) finishRehearsal() else recordTapped()
    }

    /** Record button: idle → countdown → recording. Tapping mid-countdown cancels. */
    fun recordTapped() {
        if (rehearsalActive || stageMode) return
        if (capture.isRecording) {
            capture.stopRecording()
        } else if (countdown != null) {
            cancelCountdown()
        } else {
            countdown = 3
            countdownJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    val c = countdown ?: return@launch
                    if (c > 1) {
                        countdown = c - 1
                    } else {
                        countdown = null
                        capture.startRecording()
                        return@launch
                    }
                }
            }
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown = null
    }

    fun tapped(characterIndex: Int) {
        val ranges = textView?.ranges ?: return
        val idx = ranges.indexOfLast { it.first <= characterIndex }
        if (idx < 0) return
        seekTo(idx)
    }

    /** Manual jump (word tap, PiP skip): aligner, scroll and highlight move together. */
    private fun seekTo(idx: Int) {
        val now = Clock.now()
        if (voiceMode) {
            if (idx < currentHighlight - 1) events.add(RehearsalEvent.Reread(currentHighlight, idx, now))
            trail.add(WordTime(idx, now))
        }
        engine.seek(idx)
        scroll.update(confirmedToken = idx)
        scroll.jump(idx.toDouble())
        highlight(idx)
        lost = false
        armRehearsalFinish(idx)
        lastLangSwitch = 0.0 // manual jump may cross a language zone — allow an immediate switch
        maybeSwitchSTT(idx)
    }

    // MARK: mixed-language scripts

    /**
     * Regions of the script written in the OTHER language (full sentences of
     * ≥5 tokens). When the reading cursor approaches one, only the speech
     * recognizer switches language — the aligner stays in the base language,
     * whose normalizer already handles Latin tokens, so matching just works.
     */
    private var langZones: List<Pair<IntRange, EngineLang>> = emptyList()
    private var currentSTTLang = EngineLang.KO
    private var lastLangSwitch = 0.0
    private var lastProgressAt = 0.0
    private var noMatchStreak = 0

    private fun tokenLang(raw: String): EngineLang? {
        var hasHangul = false
        var hasLatin = false
        for (c in raw) {
            if (c in '가'..'힣') hasHangul = true
            else if (c in 'A'..'Z' || c in 'a'..'z') hasLatin = true
        }
        if (hasHangul) return EngineLang.KO
        if (hasLatin) return EngineLang.EN
        return null // digits/punctuation: neutral, doesn't break a run
    }

    private fun computeLangZones(base: EngineLang) {
        val zones = ArrayList<Pair<IntRange, EngineLang>>()
        val opposite = if (base == EngineLang.KO) EngineLang.EN else EngineLang.KO
        var runStart: Int? = null
        var lastOppositeIdx = -1
        fun closeRun() {
            val s = runStart
            if (s != null && lastOppositeIdx - s + 1 >= 5) zones.add((s..lastOppositeIdx) to opposite)
            runStart = null
        }
        for ((i, t) in tokens.withIndex()) {
            val lang = if (t.kind == TokenKind.NOTE) null else tokenLang(t.display) // notes are unspoken: neutral
            if (lang == opposite) {
                if (runStart == null) runStart = i
                lastOppositeIdx = i
            } else if (lang != null) {
                closeRun()
            }
        }
        closeRun()
        langZones = zones
    }

    private fun maybeSwitchSTT(token: Int) {
        if (!voiceMode || langZones.isEmpty()) return
        val now = Clock.now()
        if (now - lastLangSwitch <= 2.0) return
        val next = minOf(token + 1, maxOf(tokens.size - 1, 0))
        var want = activeLang
        for ((range, lang) in langZones) if (token in range || next in range) want = lang
        val stalled = now - lastProgressAt
        if (currentSTTLang != activeLang && stalled > 2.5 && noMatchStreak >= 3) {
            want = activeLang // zone-exit recovery
        } else if (currentSTTLang == activeLang && want == activeLang && stalled > 5.0 && noMatchStreak >= 6) {
            want = if (activeLang == EngineLang.KO) EngineLang.EN else EngineLang.KO // reverse probe
        }
        switchSTT(want, now)
    }

    private fun switchSTT(lang: EngineLang, now: Double) {
        if (lang == currentSTTLang) return
        lastLangSwitch = now
        lastProgressAt = now
        noMatchStreak = 0
        currentSTTLang = lang
        speech.stop()
        speech.setLanguage(lang)
        startVoicePipeline()
    }

    // MARK: voice pipeline

    /** A displayed move bigger than this needs two consecutive pushes agreeing. */
    private val jumpThreshold = 3
    private var pendingJumpTarget: Int? = null

    private fun handleWords(words: List<String>) {
        val r = engine.push(words, Clock.now() * 1000) ?: return
        val now = Clock.now()

        val current = maxOf(0, currentHighlight)
        var apply = true
        if (abs(r.token - current) > jumpThreshold) {
            val pending = pendingJumpTarget
            if (pending != null && abs(pending - r.token) <= 2) {
                pendingJumpTarget = null // confirmed by two pushes in a row
            } else {
                pendingJumpTarget = r.token
                apply = false
            }
        } else {
            pendingJumpTarget = null
        }

        if (r.moved) noMatchStreak = 0 else noMatchStreak++
        if (apply) {
            if (r.moved) {
                lastProgressAt = now
                trail.add(WordTime(r.token, now))
                if (r.token <= current - 2) events.add(RehearsalEvent.Reread(current, r.token, now))
                armRehearsalFinish(r.token)
            }
            highlight(r.token)
            scroll.update(confirmedToken = r.token, tokensPerSec = r.tokensPerSec, speaking = true, lost = r.lost)
        } else {
            scroll.update(speaking = true, lost = r.lost)
        }
        if (lost != r.lost) {
            lost = r.lost
            if (r.lost) events.add(RehearsalEvent.Lost(r.token, now))
        }
        maybeSwitchSTT(r.token)

        // Speech-activity gate: silence for a beat freezes the creep.
        speakingJob?.cancel()
        speakingJob = viewModelScope.launch {
            delay(450)
            scroll.update(speaking = false)
        }
    }

    // MARK: highlight

    fun setHighlightStyle(style: String) {
        highlightColor = highlightColor(style)
        textView?.setHighlight(currentHighlight, highlightColor)
    }

    private fun highlight(idx: Int) {
        if (idx == currentHighlight) return
        currentHighlight = idx
        textView?.setHighlight(idx, highlightColor)
    }

    // MARK: subtitles

    private fun finishSRT(trail: List<WordTime>) {
        val recStart = capture.lastRecordingStart ?: return
        val name = capture.lastRecordingName ?: return
        val srt = buildSrt(tokens, trail, recStart) ?: return
        val dir = File(getApplication<Application>().cacheDir, "srt").apply { mkdirs() }
        val f = File(dir, "$name.srt")
        f.writeText(srt)
        srtFile = f
    }

    /**
     * The recorded movie plus its subtitles (when voice-follow produced one),
     * for the share sheet — Perplz picks both up as a share target.
     */
    fun recordingShareIntent(): Intent? {
        val app = getApplication<Application>()
        val uris = ArrayList<Uri>()
        capture.lastRecordingUri?.let { uris.add(it) }
        srtFile?.let { uris.add(FileProvider.getUriForFile(app, app.packageName + ".files", it)) }
        if (uris.isEmpty()) return null
        val mime = when {
            uris.size == 2 -> "*/*"
            capture.lastRecordingUri != null -> "video/mp4"
            else -> "application/x-subrip"
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        fun highlightColor(style: String): Int? = when (style) {
            "green" -> 0xFF8FFF00.toInt() // Kyulolong #8FFF00
            "cyan" -> 0xFF63D1FF.toInt()
            "pink" -> 0xFFFF6382.toInt()
            "none" -> null
            else -> 0xFFFFD60A.toInt() // yellow
        }
    }
}

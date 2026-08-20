import Foundation
import UIKit
import Combine

/// The hub that wires everything together, mirroring the web app's
/// useController: speech -> aligner -> scroll + imperative word highlight
/// (attribute edits on the text view, never SwiftUI re-renders).
@MainActor
final class PrompterViewModel: ObservableObject {
    enum PermissionState { case unknown, granted, denied }

    @Published var tokens: [DisplayToken] = []
    @Published var lost = false
    @Published var isPaused = false
    /// 3-2-1 before recording starts; nil when not counting.
    @Published var countdown: Int?
    private var countdownTimer: Timer?
    @Published var speechStatus: SpeechEngine.Status = .idle
    @Published var permissionState: PermissionState = .unknown
    @Published var errorMessage: String?

    let capture = CaptureController()
    let speech = SpeechEngine()
    let scroll = ScrollModel()
    private var engine: AlignEngine?
    private var cancellables = Set<AnyCancellable>()

    /// iOS-specific aligner tuning (merged over the web engine's defaults).
    /// SFSpeechRecognizer partials churn much more than Web Speech, so: ignore
    /// very short fragments (lone fillers like "음/어"), demand cleaner matches,
    /// keep the search window tight, and penalize distance hard so that when
    /// the same phrase appears twice, the nearer occurrence always wins.
    /// See AlignConfig in engine-src/align.ts.
    private let alignOverrides: [String: Double] = [
        "minQueryJamo": 6,
        "accept": 0.45,
        "forwardBase": 48,      // lookahead window: 96 → 48 jamo, fewer in-window duplicates
        "forwardPenalty": 0.15, // distance tiebreaker between duplicate matches
        "backwardPenalty": 0.6,
        "reseekAccept": 0.34,   // global re-seek must be near-exact
        "lostThreshold": 5,     // pushes are throttled, so "lost" needs more misses
    ]

    init() {
        // @Published changes inside nested ObservableObjects don't bubble up
        // through @StateObject on their own — forward them so SwiftUI actually
        // re-renders on camera readiness / recording state / timer ticks.
        capture.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)

        // Subtitle capture rides on the recording lifecycle.
        capture.$isRecording
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recording in
                guard let self else { return }
                if recording {
                    self.recWordTimes = []
                    self.srtFileURL = nil
                } else if !self.recWordTimes.isEmpty {
                    self.finishSRT()
                }
            }
            .store(in: &cancellables)
    }

    // MARK: subtitles (SRT)

    /// (token, host-clock time) for every accepted cursor move while recording.
    private var recWordTimes: [(token: Int, t: Double)] = []
    /// Generated after a voice-mode recording ends; drives the share button.
    @Published var srtFileURL: URL?

    private func finishSRT() {
        guard let recStart = capture.lastRecordingStart, tokens.count > 0 else { return }
        // Earliest time the cursor reached (at least) each token.
        var tokenTime = [Double?](repeating: nil, count: tokens.count)
        var filled = -1
        for entry in recWordTimes where entry.token > filled {
            for j in (filled + 1)...min(entry.token, tokens.count - 1) where tokenTime[j] == nil {
                tokenTime[j] = entry.t
            }
            filled = max(filled, entry.token)
        }

        var cues: [(start: Double, end: Double, text: String)] = []
        var rangeStart = 0
        var charCount = 0
        for i in 0..<tokens.count {
            let raw = tokens[i].raw
            charCount += raw.count + 1
            let endsSentence = raw.hasSuffix(".") || raw.hasSuffix("!") || raw.hasSuffix("?") || raw.hasSuffix("…")
            let nextBreaks = i + 1 < tokens.count ? tokens[i + 1].breakBefore : true
            // Keep cues to 1–2 subtitle lines: hard budget ~26 chars, and take
            // a comma as a break point once past ~14.
            let overBudget = charCount >= 26
            let commaBreak = raw.hasSuffix(",") && charCount >= 14
            guard endsSentence || nextBreaks || overBudget || commaBreak || i == tokens.count - 1 else { continue }
            defer { rangeStart = i + 1; charCount = 0 }
            let range = rangeStart...i
            let times = range.compactMap { tokenTime[$0] }
            guard let first = times.first, let last = times.last else { continue }
            let start = max(0, first - recStart - 0.25)
            let end = max(last - recStart + 0.8, start + 0.8)
            let text = range.map { tokens[$0].raw }.joined(separator: " ")
            cues.append((start, end, text))
        }
        guard !cues.isEmpty else { return }

        // Monotonic, non-overlapping.
        for i in 1..<cues.count {
            if cues[i].start < cues[i - 1].end {
                cues[i].start = cues[i - 1].end + 0.01
                cues[i].end = max(cues[i].end, cues[i].start + 0.5)
            }
        }

        func stamp(_ s: Double) -> String {
            let ms = Int((s * 1000).rounded())
            return String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        var srt = ""
        for (i, c) in cues.enumerated() {
            srt += "\(i + 1)\n\(stamp(c.start)) --> \(stamp(c.end))\n\(c.text)\n\n"
        }

        let df = DateFormatter()
        df.dateFormat = "yyyyMMdd-HHmmss"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("VoicePrompter-\(df.string(from: Date())).srt")
        try? srt.write(to: url, atomically: true, encoding: .utf8)
        srtFileURL = url
    }

    // Set by ScriptTextView when the text lays out.
    private weak var textView: PromptTextView?
    private var tokenRanges: [NSRange] = []
    private var currentHighlight = -1
    private var speakingTimer: Timer?
    private var voiceMode = true

    /// Language actually driving recognition/alignment this session.
    private(set) var activeLang = "ko"
    /// Set when the chosen language differs from what the script looks like —
    /// drives the gentle "switch language?" capsule in the prompter.
    @Published var suggestedLang: String?
    private var scriptText = ""

    func start(script: String, settings: AppSettings) async {
        voiceMode = settings.voiceMode
        scriptText = script
        highlightUIColor = Self.highlightColor(for: settings.highlightStyle)
        scroll.readingLineFrac = settings.readingLineFrac
        scroll.autoPxPerSec = settings.autoSpeed
        scroll.mode = settings.voiceMode ? .voice : .auto

        guard let engine = AlignEngine() else {
            errorMessage = AppSettings.tr("정렬 엔진을 불러오지 못했어요 (engine.js)")
            return
        }
        self.engine = engine
        // Recognition language is always auto-detected from the script; full
        // sentences in the other language switch the recognizer on the fly
        // (langZones), so there is no manual override to get stuck on.
        let chosen = AlignEngine.language(for: script)
        activeLang = chosen
        currentSTTLang = chosen
        speech.setLocale(chosen == "en" ? "en-US" : "ko-KR")
        tokens = engine.load(script: script, config: alignOverrides, lang: chosen)
        computeLangZones(base: chosen)
        if tokens.isEmpty {
            errorMessage = AppSettings.tr("대본이 비어 있어요")
            return
        }

        let perms = await CaptureController.requestPermissions()
        var speechOK = true
        if voiceMode {
            speechOK = await SpeechEngine.requestPermission()
        }
        guard perms.mic || !voiceMode, speechOK else {
            permissionState = .denied
            return
        }
        permissionState = .granted

        // No sample buffers arrive while the capture session is interrupted,
        // and the recognition task goes stale — rebuild the pipeline rather
        // than waiting for audio that never resumes. This also means coming
        // back from Settings (a backgrounding interruption) retries speech,
        // which is exactly when the user has just fixed a system-level cause.
        capture.onInterruptionEnded = { [weak self] in
            Task { @MainActor in
                guard let self, self.voiceMode else { return }
                self.speech.stop()
                self.startVoicePipeline()
            }
        }
        capture.configureAndStart()
        scroll.start()
        lastProgressAt = CACurrentMediaTime()

        langWatchdog?.invalidate()
        langWatchdog = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.voiceMode, self.currentSTTLang != self.activeLang else { return }
                let now = CACurrentMediaTime()
                if now - self.lastProgressAt > 4.0, now - self.lastLangSwitch > 2.0 {
                    self.switchSTT(to: self.activeLang, now: now)
                }
            }
        }

        if voiceMode {
            startVoicePipeline()
        }
    }

    private func startVoicePipeline() {
        let external = capture.providesAudio
        if external {
            capture.onAudioBuffer = { [weak self] buffer in
                self?.speech.append(buffer)
            }
        }
        speech.onWords = { [weak self] words in
            self?.handleWords(words)
        }
        speech.onStatus = { [weak self] status in
            self?.speechStatus = status
        }
        speech.start(externalAudio: external)
    }

    /// Accept the language suggestion: reload the aligner and switch the
    /// recognizer locale in place. The scroll position resets to the top of
    /// the (same) script only in aligner state — the screen stays put.
    func applySuggestedLang() {
        guard let lang = suggestedLang, let engine else { return }
        suggestedLang = nil
        activeLang = lang
        currentSTTLang = lang
        if voiceMode { speech.stop() }
        speech.setLocale(lang == "en" ? "en-US" : "ko-KR")
        tokens = engine.load(script: scriptText, config: alignOverrides, lang: lang)
        computeLangZones(base: lang)
        pendingJumpTarget = nil
        if voiceMode { startVoicePipeline() }
    }

    func stopAll() {
        cancelCountdown()
        langWatchdog?.invalidate()
        langWatchdog = nil
        speech.stop()
        scroll.stop()
        if capture.isRecording { capture.stopRecording() }
        capture.stopSession()
        speakingTimer?.invalidate()
    }

    func setMode(voice: Bool) {
        voiceMode = voice
        scroll.mode = voice ? .voice : .auto
        if voice {
            Task {
                guard await SpeechEngine.requestPermission() else {
                    permissionState = .denied
                    return
                }
                startVoicePipeline()
            }
        } else {
            speech.stop()
        }
    }

    // MARK: text view wiring (called by ScriptTextView)

    func attach(textView: PromptTextView, ranges: [NSRange], offsets: [CGFloat]) {
        self.textView = textView
        self.tokenRanges = ranges
        scroll.surface = textView
        let firstLayout = scroll.offsets.isEmpty
        scroll.offsets = offsets
        if firstLayout, !offsets.isEmpty {
            scroll.jump(to: 0)
            if voiceMode { highlight(0) }
        }
    }

    func togglePause() {
        isPaused.toggle()
        scroll.paused = isPaused
    }

    /// Record button: idle → countdown → recording. Tapping mid-countdown cancels.
    func recordTapped() {
        if capture.isRecording {
            capture.stopRecording()
        } else if countdown != nil {
            cancelCountdown()
        } else {
            countdown = 3
            countdownTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let c = self.countdown else { return }
                    if c > 1 {
                        self.countdown = c - 1
                    } else {
                        self.cancelCountdown()
                        self.capture.startRecording()
                    }
                }
            }
        }
    }

    private func cancelCountdown() {
        countdownTimer?.invalidate()
        countdownTimer = nil
        countdown = nil
    }

    func tapped(characterIndex: Int) {
        guard let idx = tokenRanges.lastIndex(where: { $0.location <= characterIndex }) else { return }
        engine?.seek(token: idx)
        scroll.update(confirmedToken: idx, now: CACurrentMediaTime())
        scroll.jump(to: Double(idx))
        highlight(idx)
        lost = false
        if capture.isRecording { recWordTimes.append((idx, CACurrentMediaTime())) }
        lastLangSwitch = 0 // manual jump may cross a language zone — allow an immediate switch
        maybeSwitchSTT(for: idx)
    }

    // MARK: voice pipeline

    // MARK: mixed-language scripts

    /// Regions of the script written in the OTHER language (full sentences of
    /// ≥5 tokens). When the reading cursor approaches one, only the speech
    /// recognizer switches locale — the aligner stays in the base language,
    /// whose normalizer already handles Latin tokens, so matching just works.
    private var langZones: [(range: ClosedRange<Int>, lang: String)] = []
    private var currentSTTLang = "ko"
    private var lastLangSwitch: CFTimeInterval = 0
    /// Time of the last accepted cursor movement — used to detect that the
    /// switched-to zone language has stopped matching (e.g., the recognizer
    /// missed the zone's last word and the reader is already back in the base
    /// language), so we can bail back instead of deadlocking.
    private var lastProgressAt: CFTimeInterval = 0
    /// Consecutive pushes without cursor movement — distinguishes "speaking
    /// but nothing matches" (wrong recognizer) from mere silence.
    private var noMatchStreak = 0
    /// Push-independent escape hatch: a recognizer listening in the wrong
    /// language may emit NOTHING at all (en-US hearing Korean often goes
    /// silent), so push-driven recovery alone can deadlock. This timer forces
    /// a return to the base language after sustained non-progress.
    private var langWatchdog: Timer?

    private static func tokenLang(_ raw: String) -> String? {
        var hasHangul = false, hasLatin = false
        for s in raw.unicodeScalars {
            if (0xAC00...0xD7A3).contains(s.value) { hasHangul = true }
            else if (65...90).contains(s.value) || (97...122).contains(s.value) { hasLatin = true }
        }
        if hasHangul { return "ko" }
        if hasLatin { return "en" }
        return nil // digits/punctuation: neutral, doesn't break a run
    }

    private func computeLangZones(base: String) {
        langZones = []
        let opposite = base == "ko" ? "en" : "ko"
        var runStart: Int?
        var lastOppositeIdx = -1
        func closeRun() {
            if let s = runStart, lastOppositeIdx - s + 1 >= 5 {
                langZones.append((s...lastOppositeIdx, opposite))
            }
            runStart = nil
        }
        for (i, t) in tokens.enumerated() {
            let lang = Self.tokenLang(t.raw)
            if lang == opposite {
                if runStart == nil { runStart = i }
                lastOppositeIdx = i
            } else if lang != nil { // base-language token ends the run
                closeRun()
            } // neutral tokens neither extend nor break
        }
        closeRun()
        #if DEBUG
        for z in langZones { print("LANG|zone \(z.lang) tokens \(z.range)") }
        #endif
    }

    /// Switch the recognizer locale when the cursor is about to enter/leave an
    /// other-language zone. Debounced so it can't flap.
    private func maybeSwitchSTT(for token: Int) {
        guard voiceMode, !langZones.isEmpty else { return }
        let now = CACurrentMediaTime()
        guard now - lastLangSwitch > 2.0 else { return }
        // Switch only when the cursor is IN a zone or the NEXT token starts
        // one — i.e., after the preceding language's words are confirmed.
        // Looking further ahead abandons the words still being read.
        let next = min(token + 1, max(tokens.count - 1, 0))
        var want = activeLang
        for z in langZones where z.range.contains(token) || z.range.contains(next) {
            want = z.lang
        }
        let stalled = now - lastProgressAt
        if currentSTTLang != activeLang, stalled > 2.5, noMatchStreak >= 3 {
            // Zone-exit recovery: switched away from the base language but
            // speech stopped matching — the zone is effectively over, go back.
            want = activeLang
        } else if currentSTTLang == activeLang, want == activeLang,
                  stalled > 5.0, noMatchStreak >= 6 {
            // Reverse probe: sustained speech that matches NOTHING in the base
            // language — the reader may have jumped back to an other-language
            // sentence. Try the zone language; the aligner's global re-seek
            // locks on if so, and the 2.5s recovery bails out if not.
            want = activeLang == "ko" ? "en" : "ko"
        }
        switchSTT(to: want, now: now)
    }

    private func switchSTT(to lang: String, now: CFTimeInterval) {
        guard lang != currentSTTLang else { return }
        lastLangSwitch = now
        lastProgressAt = now // grace period for the new recognizer
        noMatchStreak = 0
        currentSTTLang = lang
        #if DEBUG
        print("LANG|switch STT to \(lang)")
        #endif
        speech.stop()
        speech.setLocale(lang == "en" ? "en-US" : "ko-KR")
        startVoicePipeline()
    }

    /// A displayed move bigger than this needs two consecutive pushes agreeing
    /// on (roughly) the same target. One noisy recognition can never yank the
    /// screen; a real skip confirms itself on the very next push (~0.5s).
    private let jumpThreshold = 3
    private var pendingJumpTarget: Int?

    private func handleWords(_ words: [String]) {
        guard let engine else { return }
        // The web engine expects performance.now()-style milliseconds.
        guard let r = engine.push(words: words, nowMs: CACurrentMediaTime() * 1000) else { return }
        let now = CACurrentMediaTime()

        let current = max(0, currentHighlight)
        var apply = true
        if abs(r.token - current) > jumpThreshold {
            if let pending = pendingJumpTarget, abs(pending - r.token) <= 2 {
                pendingJumpTarget = nil // confirmed by two pushes in a row
            } else {
                pendingJumpTarget = r.token
                apply = false
            }
        } else {
            pendingJumpTarget = nil
        }

        #if DEBUG
        print(String(format: "ALN|%.2f|tok=%d|cur=%d|conf=%.2f|moved=%d|lost=%d|apply=%d",
                     now, r.token, current, r.confidence, r.moved ? 1 : 0, r.lost ? 1 : 0, apply ? 1 : 0))
        #endif
        if r.moved {
            noMatchStreak = 0
        } else {
            noMatchStreak += 1
        }
        if apply {
            if r.moved {
                lastProgressAt = now
                if capture.isRecording { recWordTimes.append((r.token, now)) }
            }
            highlight(r.token)
            scroll.update(confirmedToken: r.token, tokensPerSec: r.tokensPerSec,
                          speaking: true, lost: r.lost, now: now)
        } else {
            // Hold position (gentle creep only) until the jump is confirmed.
            scroll.update(speaking: true, lost: r.lost, now: now)
        }
        if lost != r.lost { lost = r.lost }
        maybeSwitchSTT(for: r.token)

        // Speech-activity gate: silence for a beat freezes the creep.
        speakingTimer?.invalidate()
        speakingTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.scroll.update(speaking: false, now: CACurrentMediaTime())
            }
        }
    }

    /// Current highlight color; nil = highlighting off. Set from settings.
    private var highlightUIColor: UIColor?

    func setHighlightStyle(_ style: String) {
        highlightUIColor = Self.highlightColor(for: style)
        guard let textView, currentHighlight >= 0, currentHighlight < tokenRanges.count else { return }
        let range = tokenRanges[currentHighlight]
        textView.performWithoutScrolling {
            textView.textStorage.addAttribute(.foregroundColor,
                                              value: highlightUIColor ?? UIColor.white,
                                              range: range)
        }
    }

    static func highlightColor(for style: String) -> UIColor? {
        switch style {
        case "green": return UIColor(red: 0x8F / 255.0, green: 1.0, blue: 0.0, alpha: 1) // Kyulolong #8FFF00
        case "cyan": return UIColor(red: 0.39, green: 0.82, blue: 1.0, alpha: 1)
        case "pink": return UIColor(red: 1.0, green: 0.39, blue: 0.51, alpha: 1)
        case "none": return nil
        default: return UIColor(red: 1.0, green: 0.84, blue: 0.04, alpha: 1) // yellow
        }
    }

    private func highlight(_ idx: Int) {
        guard idx != currentHighlight, let textView, idx < tokenRanges.count else { return }
        let previous = currentHighlight
        currentHighlight = idx
        guard let color = highlightUIColor else { return }
        // Storage edits make UITextView auto-scroll the edited word into view;
        // performWithoutScrolling swallows that so only ScrollModel scrolls.
        textView.performWithoutScrolling {
            let storage = textView.textStorage
            if previous >= 0, previous < tokenRanges.count {
                storage.addAttribute(.foregroundColor, value: UIColor.white, range: tokenRanges[previous])
            }
            storage.addAttribute(.foregroundColor, value: color, range: tokenRanges[idx])
        }
    }
}

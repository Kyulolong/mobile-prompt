import Foundation
import UIKit
import Combine
import AVFoundation
import Speech

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
                    if let old = self.srtFileURL { try? FileManager.default.removeItem(at: old) }
                    self.srtFileURL = nil
                    self.takeReportAvailable = false
                    self.recordingTrailStart = self.trail.count
                    self.recordingEventsStart = self.events.count
                    // Only a take read in voice mode has a real alignment trail;
                    // word taps during an auto-scroll take must not yield a
                    // partial SRT.
                    self.srtEligible = self.voiceMode
                } else {
                    let slice = Array(self.trail[min(self.recordingTrailStart, self.trail.count)...])
                    self.lastTakeTrail = slice
                    self.lastTakeEvents = Array(self.events[min(self.recordingEventsStart, self.events.count)...])
                    self.lastTakeSeconds = Double(self.capture.recordingSeconds)
                    self.takeReportAvailable = self.srtEligible && slice.count >= 2
                    if self.srtEligible, !slice.isEmpty { self.finishSRT(trail: slice) }
                }
            }
            .store(in: &cancellables)
    }

    // MARK: subtitles (SRT)

    /// (token, host-clock time) for every accepted cursor move in voice mode —
    /// the whole session. Recording and rehearsal each use a slice of it.
    private var trail: [WordTime] = []
    /// Trouble the aligner noticed (lost, re-reads), same clock as `trail`.
    private var events: [RehearsalEvent] = []
    private var recordingTrailStart = 0
    private var recordingEventsStart = 0
    private var lastTakeTrail: [WordTime] = []
    private var lastTakeEvents: [RehearsalEvent] = []
    private var lastTakeSeconds = 0.0
    /// The last take was read in voice mode long enough to have a summary.
    @Published private(set) var takeReportAvailable = false
    /// Whether the running take started in voice mode (see the recording sink).
    private var srtEligible = false
    /// Generated after a voice-mode recording ends; drives the share button.
    @Published var srtFileURL: URL?

    private func finishSRT(trail: [WordTime]) {
        guard let recStart = capture.lastRecordingStart,
              let name = capture.lastRecordingName,
              let srt = Srt.build(tokens: tokens, trail: trail, recStart: recStart) else { return }
        // Same stem as the movie (VoicePrompter-yyyyMMdd-HHmmss) so the pair
        // stays together in the share sheet and on the receiving side.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).srt")
        try? srt.write(to: url, atomically: true, encoding: .utf8)
        srtFileURL = url
    }

    // Set by ScriptTextView when the text lays out.
    private weak var textView: PromptTextView?
    private var tokenRanges: [NSRange] = []
    private var currentHighlight = -1
    private var speakingTimer: Timer?
    private var voiceMode = true
    private var settings: AppSettings?
    /// No camera at all: the mic runs through SpeechEngine's own AVAudioEngine.
    private(set) var stageMode = false
    /// Speech was stopped because the app went to the background with nothing
    /// on screen; resumed when it comes back.
    private var pausedInBackground = false

    // MARK: rehearsal

    @Published private(set) var rehearsalActive = false
    @Published private(set) var rehearsalSeconds = 0
    /// Set when a rehearsal or take summary is ready; drives the result sheet.
    @Published var report: RehearsalReport?
    /// Short transient message (e.g. "nothing was read yet").
    @Published var toast: String?
    private var rehearsalTimer: Timer?
    private var rehearsalFinishTimer: Timer?
    private var toastTimer: Timer?
    /// Last token the reader can actually say — reaching it (then 1.5 s of
    /// silence) ends a rehearsal by itself.
    private var lastSpokenToken = -1

    /// Read the script in voice mode without the camera rolling, then get a
    /// summary: time, pace, pauses, and the sentences that caused trouble.
    func startRehearsal() {
        guard voiceMode, !capture.isRecording, countdown == nil, !tokens.isEmpty else { return }
        trail = []
        events = []
        engine?.reset(startToken: 0)
        pendingJumpTarget = nil
        lost = false
        scroll.jump(to: 0)
        highlight(0)
        rehearsalActive = true
        rehearsalSeconds = 0
        rehearsalTimer?.invalidate()
        rehearsalTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.rehearsalSeconds += 1 }
        }
    }

    func finishRehearsal() {
        guard rehearsalActive else { return }
        rehearsalActive = false
        rehearsalTimer?.invalidate()
        rehearsalTimer = nil
        rehearsalFinishTimer?.invalidate()
        rehearsalFinishTimer = nil
        guard let r = RehearsalStats.compute(tokens: tokens, trail: trail, events: events,
                                             lang: activeLang, kind: .rehearsal) else {
            showToast(AppSettings.tr("읽은 내용이 없어요"))
            return
        }
        // A real read-through teaches the editor the reader's own pace.
        if trail.count >= 20, let settings {
            if r.paceUnit == .wordsPerMinute { settings.paceEn = r.pace } else { settings.paceKo = r.pace }
        }
        report = r
    }

    /// Summary of the take that just finished (voice mode only).
    func showTakeReport() {
        guard takeReportAvailable,
              let r = RehearsalStats.compute(tokens: tokens, trail: lastTakeTrail, events: lastTakeEvents,
                                             lang: activeLang, kind: .take, duration: lastTakeSeconds) else { return }
        report = r
    }

    private func armRehearsalFinish(token: Int) {
        guard rehearsalActive else { return }
        rehearsalFinishTimer?.invalidate()
        rehearsalFinishTimer = nil
        guard token >= lastSpokenToken else { return }
        rehearsalFinishTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.finishRehearsal() }
        }
    }

    private func showToast(_ message: String) {
        toast = message
        toastTimer?.invalidate()
        toastTimer = Timer.scheduledTimer(withTimeInterval: 2.2, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.toast = nil }
        }
    }

    /// Language actually driving recognition/alignment this session.
    private(set) var activeLang = "ko"
    private var scriptText = ""

    func start(script: String, settings: AppSettings) async {
        self.settings = settings
        voiceMode = settings.voiceMode
        stageMode = settings.stageMode
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
        // Markup: the engine sees notes as blanks (same whitespace, same token
        // count); the display gets bold/dim per token. If the two ever fail to
        // line up, the script is shown plain rather than wrong.
        let engineTokens = engine.load(script: ScriptMarkup.engineText(script), config: alignOverrides, lang: chosen)
        tokens = ScriptMarkup.apply(engineTokens, original: script)
            ?? engine.load(script: script, config: alignOverrides, lang: chosen)
        computeLangZones(base: chosen)
        lastSpokenToken = tokens.lastIndex { t in
            t.kind != .note && t.display.unicodeScalars.contains { s in
                (0xAC00...0xD7A3).contains(s.value) || CharacterSet.alphanumerics.contains(s)
            }
        } ?? -1
        if tokens.isEmpty {
            errorMessage = AppSettings.tr("대본이 비어 있어요")
            return
        }

        // A denied mic/speech permission only blocks voice-follow: the camera,
        // auto-scroll and recording still run, and the overlay offers the
        // switch (mirrors the Android screen).
        // Stage mode never asks for the camera.
        var micOK = stageMode ? await AVCaptureDevice.requestAccess(for: .audio)
                              : (await CaptureController.requestPermissions()).mic
        if voiceMode, micOK {
            micOK = await SpeechEngine.requestPermission()
        }
        permissionState = micOK ? .granted : .denied

        // No sample buffers arrive while the capture session is interrupted,
        // and the recognition task goes stale — rebuild the pipeline rather
        // than waiting for audio that never resumes. This also means coming
        // back from Settings (a backgrounding interruption) retries speech,
        // which is exactly when the user has just fixed a system-level cause.
        capture.onInterruptionEnded = { [weak self] in
            Task { @MainActor in
                guard let self, self.voiceMode, !self.stageMode else { return }
                self.speech.stop()
                self.startVoicePipeline()
            }
        }
        capture.preferredPreset = Self.preset(for: settings.videoQuality)
        capture.preferHEVC = settings.hevc
        // Reading a script is a long, touch-free activity: never auto-lock.
        UIApplication.shared.isIdleTimerDisabled = true
        if !stageMode { capture.configureAndStart() }
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

        if voiceMode, micOK {
            startVoicePipeline()
        }
    }

    /// Coming back from Settings: if the user just granted what was missing,
    /// start listening without making them reopen the prompter.
    func recheckPermissions() {
        guard voiceMode, permissionState == .denied,
              AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
              SFSpeechRecognizer.authorizationStatus() == .authorized else { return }
        permissionState = .granted
        startVoicePipeline()
    }

    private func startVoicePipeline() {
        let external = !stageMode && capture.providesAudio
        if external {
            capture.onAudioBuffer = { [weak self] buffer in
                self?.speech.append(buffer)
            }
        } else {
            capture.onAudioBuffer = nil
        }
        speech.onWords = { [weak self] words in
            self?.handleWords(words)
        }
        speech.onStatus = { [weak self] status in
            guard let self else { return }
            self.speechStatus = status
            // Another app took the mic (call, Siri, a live-stream app): keep
            // the script moving on its own until it comes back.
            guard self.voiceMode else { return }
            if status == .interrupted { self.scroll.mode = .auto }
            else if status == .listening { self.scroll.mode = .voice }
        }
        speech.start(externalAudio: external)
    }

    /// Toggle the camera off (stage) or on, mid-session. The aligner, the
    /// scroll position and the highlight all stay where they are.
    func setStageMode(_ on: Bool) {
        guard on != stageMode else { return }
        speech.stop()
        if on {
            if capture.isRecording { capture.stopRecording() }
            cancelCountdown()
            capture.stopSession()
            stageMode = true
            if voiceMode, permissionState == .granted { startVoicePipeline() }
        } else {
            Task { @MainActor in
                _ = await AVCaptureDevice.requestAccess(for: .video)
                stageMode = false
                capture.configureAndStart()
                if voiceMode, permissionState == .granted { startVoicePipeline() }
            }
        }
    }

    /// Background with nothing on screen: the mic must not stay hot.
    func sceneDidEnterBackground() {
        guard stageMode, voiceMode, !pausedInBackground else { return }
        pausedInBackground = true
        speech.stop()
        scroll.paused = true
    }

    func sceneDidBecomeActive() {
        guard pausedInBackground else { return }
        pausedInBackground = false
        scroll.paused = isPaused
        if voiceMode, permissionState == .granted { startVoicePipeline() }
    }

    func stopAll() {
        cancelCountdown()
        rehearsalActive = false
        rehearsalTimer?.invalidate()
        rehearsalFinishTimer?.invalidate()
        langWatchdog?.invalidate()
        langWatchdog = nil
        speech.stop()
        speech.releaseAudioSession()
        scroll.stop()
        if capture.isRecording { capture.stopRecording() }
        capture.stopSession()
        speakingTimer?.invalidate()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func setMode(voice: Bool) {
        voiceMode = voice
        scroll.mode = voice ? .voice : .auto
        if voice {
            Task {
                let micOK = await AVCaptureDevice.requestAccess(for: .audio)
                var speechOK = false
                if micOK { speechOK = await SpeechEngine.requestPermission() }
                permissionState = speechOK ? .granted : .denied
                if speechOK { startVoicePipeline() }
            }
        } else {
            speech.stop()
            // A take that switches to auto-scroll mid-way loses its trail.
            if capture.isRecording { srtEligible = false }
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

    static func preset(for quality: String) -> AVCaptureSession.Preset {
        quality == "4k" ? .hd4K3840x2160 : .hd1920x1080
    }

    func setVideoQuality(_ quality: String) {
        capture.setPreset(Self.preset(for: quality))
    }

    func setHEVC(_ on: Bool) {
        capture.preferHEVC = on
    }

    /// Volume button / Camera Control: the same thing the big button does,
    /// or "finish" while rehearsing.
    func hardwareShutterPressed() {
        if rehearsalActive { finishRehearsal() } else { recordTapped() }
    }

    /// Record button: idle → countdown → recording. Tapping mid-countdown cancels.
    func recordTapped() {
        guard !rehearsalActive, !stageMode else { return }
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
        let now = CACurrentMediaTime()
        if voiceMode {
            if idx < currentHighlight - 1 { events.append(.reread(from: currentHighlight, to: idx, t: now)) }
            trail.append(WordTime(token: idx, t: now))
        }
        engine?.seek(token: idx)
        scroll.update(confirmedToken: idx, now: now)
        scroll.jump(to: Double(idx))
        highlight(idx)
        lost = false
        armRehearsalFinish(token: idx)
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
            let lang = t.kind == .note ? nil : Self.tokenLang(t.display) // notes are unspoken: neutral
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
                trail.append(WordTime(token: r.token, t: now))
                if r.token <= current - 2 { events.append(.reread(from: current, to: r.token, t: now)) }
                armRehearsalFinish(token: r.token)
            }
            highlight(r.token)
            scroll.update(confirmedToken: r.token, tokensPerSec: r.tokensPerSec,
                          speaking: true, lost: r.lost, now: now)
        } else {
            // Hold position (gentle creep only) until the jump is confirmed.
            scroll.update(speaking: true, lost: r.lost, now: now)
        }
        if lost != r.lost {
            lost = r.lost
            if r.lost { events.append(.lost(token: r.token, t: now)) }
        }
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
    /// Notes (`[like this]`) are drawn dimmed.
    static let noteColor = UIColor.white.withAlphaComponent(0.45)

    /// A token's colour when it is not the highlighted one.
    private func baseColor(_ idx: Int) -> UIColor {
        idx < tokens.count && tokens[idx].kind == .note ? Self.noteColor : .white
    }

    func setHighlightStyle(_ style: String) {
        highlightUIColor = Self.highlightColor(for: style)
        guard let textView, currentHighlight >= 0, currentHighlight < tokenRanges.count else { return }
        let range = tokenRanges[currentHighlight]
        textView.performWithoutScrolling {
            textView.textStorage.addAttribute(.foregroundColor,
                                              value: highlightUIColor ?? baseColor(currentHighlight),
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
                storage.addAttribute(.foregroundColor, value: baseColor(previous), range: tokenRanges[previous])
            }
            storage.addAttribute(.foregroundColor, value: color, range: tokenRanges[idx])
        }
    }
}

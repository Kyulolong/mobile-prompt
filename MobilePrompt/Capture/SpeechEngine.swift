import Foundation
import AVFoundation
import QuartzCore
import Speech

/// Korean speech recognition feeding the aligner.
///
/// Audio can come from two places:
///  - the camera capture session's audio buffers (normal path — lets recording
///    and recognition share one microphone), via `append(_:)`
///  - its own AVAudioEngine tap (fallback when no capture session runs,
///    e.g. the simulator)
///
/// Like the web app's Web Speech adapter, sessions are restarted transparently
/// (on final results, errors, and a periodic timer for server-based
/// recognition). The aligner keeps its cursor across restarts, so they're
/// invisible to the user.
final class SpeechEngine: NSObject {
    enum Status: Equatable { case idle, listening, denied, unavailable, dictationDisabled, error(String) }

    /// Last few spoken words (tail), delivered on the main thread.
    var onWords: (([String]) -> Void)?
    var onStatus: ((Status) -> Void)?

    private var recognizer = SFSpeechRecognizer(locale: Locale(identifier: "ko-KR"))

    /// Switch the recognition locale (e.g. "ko-KR", "en-US"). Call before start.
    func setLocale(_ identifier: String) {
        recognizer = SFSpeechRecognizer(locale: Locale(identifier: identifier))
    }
    private let queue = DispatchQueue(label: "speech.engine")
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var audioEngine: AVAudioEngine?
    private var running = false
    private var restartTimer: Timer?
    private let tailWords = 8
    // SFSpeechRecognizer rewrites its partial transcription very aggressively
    // (far noisier than Web Speech interims). Throttling partials keeps that
    // churn from yanking the aligner around; finals always go through.
    private var lastPartialPush: CFTimeInterval = 0
    private let partialInterval: CFTimeInterval = 0.2
    private var lastPushedWords: [String] = []
    /// Consecutive session failures with nothing recognized in between.
    /// `SFSpeechRecognitionTask` can fail instantly and forever (dictation
    /// switched off in Settings is the common one); restarting on every error
    /// spins a ~1000-restarts-per-second loop that burns battery and never
    /// recognizes a word, while the UI still reads "listening".
    private var failureStreak = 0
    private let maxFailureStreak = 6
    /// On-device recognition can be refused (kLSRErrorDomain 201) even when
    /// `supportsOnDeviceRecognition` says yes — the assets are present but the
    /// system won't hand them out. Server recognition often still works, so
    /// fall back once before declaring dictation dead.
    private var forceServerRecognition = false

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
    }

    var isOnDevice: Bool { recognizer?.supportsOnDeviceRecognition ?? false }

    /// externalAudio: true when the capture session feeds buffers via append(_:).
    func start(externalAudio: Bool) {
        #if DEBUG
        print("SPX|start|external=\(externalAudio)|recognizer=\(recognizer != nil)|available=\(recognizer?.isAvailable ?? false)|onDevice=\(recognizer?.supportsOnDeviceRecognition ?? false)|locale=\(recognizer?.locale.identifier ?? "-")")
        #endif
        guard let recognizer, recognizer.isAvailable else {
            notify(.unavailable)
            return
        }
        queue.sync {
            running = true
            failureStreak = 0
            forceServerRecognition = false
            beginSessionLocked()
        }
        if !externalAudio {
            startAudioEngine()
        }
        // Server-based recognition dies around the 1-minute mark; rotate the
        // request before that. On-device has no cap, so no timer needed.
        if !isOnDevice {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.running else { return }
                self.restartTimer = Timer.scheduledTimer(withTimeInterval: 50, repeats: true) { [weak self] _ in
                    self?.restartSession()
                }
            }
        }
        notify(.listening)
    }

    func stop() {
        queue.sync {
            running = false
            endSessionLocked()
        }
        DispatchQueue.main.async { [weak self] in
            self?.restartTimer?.invalidate()
            self?.restartTimer = nil
        }
        stopAudioEngine()
        notify(.idle)
    }

    /// Called from the capture session's data queue.
    func append(_ sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self else { return }
            #if DEBUG
            self.bufferCount += 1
            let now = CACurrentMediaTime()
            if now - self.lastBufferLog >= 1.0 {
                self.lastBufferLog = now
                print(String(format: "AUD|%.2f|buffers=%d|hasRequest=%d", now, self.bufferCount, self.request != nil ? 1 : 0))
                self.bufferCount = 0
            }
            #endif
            self.request?.appendAudioSampleBuffer(sampleBuffer)
        }
    }

    #if DEBUG
    private var bufferCount = 0
    private var lastBufferLog: CFTimeInterval = 0
    #endif

    // MARK: session lifecycle (on `queue`)

    /// Monotonic session tag. Cancelling a task fires its callback with an
    /// error; without this guard that stale callback would restart (and kill)
    /// the session that REPLACED it — an infinite restart loop with no
    /// recognition output. Callbacks from any session other than the current
    /// one are ignored.
    private var sessionID = 0

    private func beginSessionLocked() {
        sessionID += 1
        let id = sessionID
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.taskHint = .dictation
        let onDevice = recognizer?.supportsOnDeviceRecognition == true && !forceServerRecognition
        req.requiresOnDeviceRecognition = onDevice
        #if DEBUG
        print("SPX|session|id=\(id)|onDevice=\(onDevice)|auth=\(SFSpeechRecognizer.authorizationStatus().rawValue)|available=\(recognizer?.isAvailable ?? false)")
        #endif
        request = req
        task = recognizer?.recognitionTask(with: req) { [weak self] result, error in
            guard let self else { return }
            if let result {
                // SFSpeech keeps REWRITING its most recent word(s) as more audio
                // arrives — the dominant source of alignment jitter. Drop the
                // newest segment from partials (finals are stable): the prompt
                // trails by ~one word, which the scroll's creep covers anyway.
                let segments = result.bestTranscription.segments
                let stable = result.isFinal ? segments[...] : segments.dropLast()
                let words = stable.suffix(self.tailWords).map(\.substring)
                let isFinal = result.isFinal
                self.queue.async {
                    guard id == self.sessionID else { return } // stale session
                    self.failureStreak = 0 // recognition is alive
                    // Skip repeats: an unchanged tail carries no new information.
                    if !words.isEmpty, words != self.lastPushedWords {
                        let now = CACurrentMediaTime()
                        if isFinal || now - self.lastPartialPush >= self.partialInterval {
                            self.lastPartialPush = now
                            self.lastPushedWords = words
                            #if DEBUG
                            print(String(format: "STT|%.2f|final=%d|%@", now, isFinal ? 1 : 0, words.joined(separator: " ")))
                            #endif
                            DispatchQueue.main.async { self.onWords?(words) }
                        }
                    }
                    if isFinal { self.restartLocked(ifStill: id) }
                }
            } else if let error {
                let ns = error as NSError
                #if DEBUG
                print("SPX|error|domain=\(ns.domain)|code=\(ns.code)|\(ns.localizedDescription)")
                #endif
                // "Siri and Dictation are disabled": every future session will
                // fail identically, and `recognizer.isAvailable` does NOT
                // report it — so detect it here and tell the user.
                if ns.domain == "kLSRErrorDomain", ns.code == 201 {
                    self.queue.async {
                        guard id == self.sessionID, self.running else { return }
                        // Retry once on the server recognizer before concluding
                        // that speech is off system-wide.
                        if !self.forceServerRecognition, self.recognizer?.supportsOnDeviceRecognition == true {
                            #if DEBUG
                            print("SPX|fallback|on-device refused, retrying server-based")
                            #endif
                            self.forceServerRecognition = true
                            self.endSessionLocked()
                            self.beginSessionLocked()
                            return
                        }
                        self.running = false
                        self.endSessionLocked()
                        DispatchQueue.main.async {
                            self.restartTimer?.invalidate()
                            self.restartTimer = nil
                        }
                        self.notify(.dictationDisabled)
                    }
                    return
                }
                self.queue.async {
                    guard id == self.sessionID, self.running else { return } // cancelled/stale
                    self.failureStreak += 1
                    guard self.failureStreak <= self.maxFailureStreak else {
                        self.running = false
                        self.endSessionLocked()
                        self.notify(.error(AppSettings.tr("음성인식을 시작하지 못했어요")))
                        return
                    }
                    // Exponential backoff so a persistent failure costs a few
                    // retries, not a spin loop.
                    let delay = min(0.25 * pow(2, Double(self.failureStreak - 1)), 4.0)
                    self.endSessionLocked()
                    self.queue.asyncAfter(deadline: .now() + delay) {
                        guard self.running, id == self.sessionID else { return }
                        self.beginSessionLocked()
                    }
                }
            }
        }
    }

    private func endSessionLocked() {
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
    }

    /// On `queue`. Restart only if `id` is still the live session.
    private func restartLocked(ifStill id: Int) {
        guard running, id == sessionID else { return }
        endSessionLocked()
        beginSessionLocked()
    }

    private func restartSession() {
        queue.async { [weak self] in
            guard let self, self.running else { return }
            self.endSessionLocked()
            self.beginSessionLocked()
        }
    }

    // MARK: AVAudioEngine fallback (no capture session)

    private func startAudioEngine() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let engine = AVAudioEngine()
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                guard let self else { return }
                self.queue.async { self.request?.append(buffer) }
            }
            engine.prepare()
            try engine.start()
            audioEngine = engine
        } catch {
            notify(.error(AppSettings.tr("마이크를 시작하지 못했어요") + ": \(error.localizedDescription)"))
        }
    }

    private func stopAudioEngine() {
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
    }

    private func notify(_ status: Status) {
        DispatchQueue.main.async { self.onStatus?(status) }
    }
}

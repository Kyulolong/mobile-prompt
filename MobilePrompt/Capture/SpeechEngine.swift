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
    enum Status: Equatable { case idle, listening, denied, unavailable, error(String) }

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
        guard let recognizer, recognizer.isAvailable else {
            notify(.unavailable)
            return
        }
        queue.sync {
            running = true
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
            self?.request?.appendAudioSampleBuffer(sampleBuffer)
        }
    }

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
        if recognizer?.supportsOnDeviceRecognition == true {
            req.requiresOnDeviceRecognition = true
        }
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
            } else if error != nil {
                self.queue.async {
                    guard id == self.sessionID else { return } // cancelled/stale
                    self.restartLocked(ifStill: id)
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

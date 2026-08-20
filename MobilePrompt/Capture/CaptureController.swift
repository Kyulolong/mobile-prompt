import Foundation
import AVFoundation
import Photos
import UIKit

/// Owns the single AVCaptureSession behind everything camera-related:
///  - front-camera preview (via AVCaptureVideoPreviewLayer in CameraPreview)
///  - movie recording through AVAssetWriter (video + mic audio)
///  - a live audio-buffer feed for speech recognition (`onAudioBuffer`)
///
/// One session, one microphone: the audio data output fans each buffer out to
/// both the asset writer and the speech engine, which is what makes
/// "voice-follow while recording" possible at all on iOS.
final class CaptureController: NSObject, ObservableObject {
    enum CameraState { case starting, ready, unavailable }

    @Published var isRecording = false
    @Published var recordingSeconds = 0
    @Published var cameraState: CameraState = .starting
    /// True while the session is interrupted (backgrounded, Split View /
    /// Stage Manager, another app holding the camera). No sample buffers are
    /// delivered in this state — including the mic buffers the speech engine
    /// lives on — so it has to be visible rather than silently swallowed.
    @Published var isInterrupted = false
    /// Called on the main thread once the session is running again. The
    /// recognition session went deaf while interrupted, so the owner has to
    /// rebuild it instead of waiting for buffers that never resume.
    var onInterruptionEnded: (() -> Void)?
    @Published var saveMessage: String?
    /// Host-clock seconds of the recording's first video frame — the zero
    /// point of the movie's timeline. Same clock as CACurrentMediaTime(), so
    /// the aligner's word timestamps map directly onto subtitle times.
    @Published var lastRecordingStart: Double?

    var cameraAvailable: Bool { cameraState == .ready }

    let session = AVCaptureSession()
    /// Called on the capture data queue for every mic buffer.
    var onAudioBuffer: ((CMSampleBuffer) -> Void)?

    private let sessionQueue = DispatchQueue(label: "capture.session")
    private let dataQueue = DispatchQueue(label: "capture.data")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()

    // Orientation. The coordinator tracks the physical device orientation and
    // publishes the rotation angles preview/capture need to stay upright.
    private var cameraDevice: AVCaptureDevice?
    private weak var previewLayer: AVCaptureVideoPreviewLayer?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservers: [NSKeyValueObservation] = []

    // Writer state — touched only on dataQueue.
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var writerSessionStarted = false
    private var recordingURL: URL?

    private var recordingTimer: Timer?
    private var sessionObservers: [NSObjectProtocol] = []

    deinit {
        sessionObservers.forEach(NotificationCenter.default.removeObserver)
    }

    static func requestPermissions() async -> (camera: Bool, mic: Bool) {
        let cam = await AVCaptureDevice.requestAccess(for: .video)
        let mic = await AVCaptureDevice.requestAccess(for: .audio)
        return (cam, mic)
    }

    func configureAndStart() {
        observeSessionEvents()
        sessionQueue.async { [self] in
            session.beginConfiguration()
            session.sessionPreset = .hd1920x1080

            var hasCamera = false
            if let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
               let input = try? AVCaptureDeviceInput(device: cam),
               session.canAddInput(input) {
                session.addInput(input)
                cameraDevice = cam
                hasCamera = true
            }
            var hasMic = false
            if let mic = AVCaptureDevice.default(for: .audio),
               let input = try? AVCaptureDeviceInput(device: mic),
               session.canAddInput(input) {
                session.addInput(input)
                hasMic = true
            }
            #if DEBUG
            print("CAP|inputs|camera=\(hasCamera)|mic=\(hasMic)|micDevice=\(AVCaptureDevice.default(for: .audio)?.localizedName ?? "nil")|micAuth=\(AVCaptureDevice.authorizationStatus(for: .audio).rawValue)")
            #endif

            videoOutput.setSampleBufferDelegate(self, queue: dataQueue)
            if hasCamera, session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
            audioOutput.setSampleBufferDelegate(self, queue: dataQueue)
            if session.canAddOutput(audioOutput) { session.addOutput(audioOutput) }

            if let conn = videoOutput.connection(with: .video), conn.isVideoRotationAngleSupported(90) {
                conn.videoRotationAngle = 90 // portrait default until the coordinator kicks in
            }
            session.commitConfiguration()
            #if DEBUG
            print("CAP|outputs|audioOutputAdded=\(session.outputs.contains(audioOutput))|audioConnection=\(audioOutput.connection(with: .audio) != nil)")
            #endif
            if hasCamera || !session.outputs.isEmpty {
                session.startRunning()
                #if DEBUG
                print("CAP|started|running=\(session.isRunning)")
                #endif
            }
            DispatchQueue.main.async {
                self.cameraState = hasCamera ? .ready : .unavailable
                self.setupRotationCoordinatorIfReady()
            }
        }
    }

    /// Called by CameraPreview once its layer exists.
    func attachPreviewLayer(_ layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        setupRotationCoordinatorIfReady()
    }

    private func setupRotationCoordinatorIfReady() {
        guard rotationCoordinator == nil, let device = cameraDevice, let layer = previewLayer else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer)
        rotationCoordinator = coordinator
        applyPreviewRotation(coordinator.videoRotationAngleForHorizonLevelPreview)
        applyCaptureRotation(coordinator.videoRotationAngleForHorizonLevelCapture)
        rotationObservers = [
            coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak self] c, _ in
                let angle = c.videoRotationAngleForHorizonLevelPreview
                DispatchQueue.main.async { self?.applyPreviewRotation(angle) }
            },
            coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.new]) { [weak self] c, _ in
                let angle = c.videoRotationAngleForHorizonLevelCapture
                DispatchQueue.main.async { self?.applyCaptureRotation(angle) }
            },
        ]
    }

    private func applyPreviewRotation(_ angle: CGFloat) {
        if let conn = previewLayer?.connection, conn.isVideoRotationAngleSupported(angle) {
            conn.videoRotationAngle = angle
        }
    }

    private func applyCaptureRotation(_ angle: CGFloat) {
        // Mid-recording rotation would change buffer dimensions and corrupt the
        // asset writer, so recording keeps the orientation it started with
        // (same as the system camera app).
        guard !isRecording else { return }
        sessionQueue.async { [self] in
            if let conn = videoOutput.connection(with: .video), conn.isVideoRotationAngleSupported(angle) {
                conn.videoRotationAngle = angle
            }
        }
    }

    /// True when the session can feed mic buffers to the speech engine
    /// (device). False on the simulator, where SpeechEngine falls back to its
    /// own AVAudioEngine tap.
    var providesAudio: Bool {
        AVCaptureDevice.default(for: .audio) != nil
    }

    func stopSession() {
        sessionQueue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    // MARK: recording

    func startRecording() {
        guard cameraAvailable else { return } // UI calls this on the main thread
        dataQueue.async { [self] in
            guard writer == nil else { return }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("prompt-\(UUID().uuidString).mov")
            guard let w = try? AVAssetWriter(outputURL: url, fileType: .mov) else { return }

            let vSettings = videoOutput.recommendedVideoSettingsForAssetWriter(writingTo: .mov)
            let v = AVAssetWriterInput(mediaType: .video, outputSettings: vSettings)
            v.expectsMediaDataInRealTime = true
            let aSettings = audioOutput.recommendedAudioSettingsForAssetWriter(writingTo: .mov)
            let a = AVAssetWriterInput(mediaType: .audio, outputSettings: aSettings)
            a.expectsMediaDataInRealTime = true

            if w.canAdd(v) { w.add(v) }
            if w.canAdd(a) { w.add(a) }
            guard w.startWriting() else { return }

            writer = w
            videoInput = v
            audioInput = a
            writerSessionStarted = false
            recordingURL = url

            DispatchQueue.main.async {
                self.isRecording = true
                self.recordingSeconds = 0
                self.saveMessage = nil
                self.recordingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
                    self.recordingSeconds += 1
                }
            }
        }
    }

    func stopRecording() {
        dataQueue.async { [self] in
            guard let w = writer else { return }
            writer = nil
            let url = recordingURL
            DispatchQueue.main.async {
                self.isRecording = false
                self.recordingTimer?.invalidate()
                self.recordingTimer = nil
                // Catch up on any rotation that happened while recording.
                if let c = self.rotationCoordinator {
                    self.applyCaptureRotation(c.videoRotationAngleForHorizonLevelCapture)
                }
            }
            videoInput?.markAsFinished()
            audioInput?.markAsFinished()
            w.finishWriting { [self] in
                if w.status == .completed, let url {
                    saveToPhotos(url)
                } else {
                    DispatchQueue.main.async { self.saveMessage = AppSettings.tr("녹화 저장에 실패했어요") }
                }
            }
        }
    }

    /// iPad multitasking, backgrounding, or another app grabbing the camera
    /// interrupts the session. Audio for speech recognition flows only through
    /// here, so an unhandled interruption reads to the user as "listening"
    /// while nothing is heard at all.
    private func observeSessionEvents() {
        guard sessionObservers.isEmpty else { return }
        let nc = NotificationCenter.default
        sessionObservers = [
            nc.addObserver(forName: .AVCaptureSessionWasInterrupted, object: session, queue: .main) { [weak self] note in
                #if DEBUG
                let reason = (note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int) ?? -1
                print("CAP|interrupted|reason=\(reason)")
                #endif
                self?.isInterrupted = true
            },
            nc.addObserver(forName: .AVCaptureSessionInterruptionEnded, object: session, queue: .main) { [weak self] _ in
                #if DEBUG
                print("CAP|interruptionEnded")
                #endif
                self?.resumeSession()
            },
            nc.addObserver(forName: .AVCaptureSessionRuntimeError, object: session, queue: .main) { [weak self] note in
                #if DEBUG
                let err = note.userInfo?[AVCaptureSessionErrorKey] as? NSError
                print("CAP|runtimeError|\(err?.code ?? -1)|\(err?.localizedDescription ?? "?")")
                #endif
                // A runtime error stops the session for good; it never comes
                // back on its own.
                self?.isInterrupted = true
                self?.resumeSession()
            },
        ]
    }

    /// Get the session running again and tell the owner, so the speech
    /// pipeline can be rebuilt. Safe to call when it is already running.
    private func resumeSession() {
        sessionQueue.async { [self] in
            if !session.isRunning { session.startRunning() }
            let running = session.isRunning
            #if DEBUG
            print("CAP|resume|running=\(running)")
            #endif
            DispatchQueue.main.async {
                self.isInterrupted = !running
                if running { self.onInterruptionEnded?() }
            }
        }
    }

    private func saveToPhotos(_ url: URL) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { self.saveMessage = AppSettings.tr("사진 접근 권한이 없어 임시 폴더에만 저장됐어요") }
                return
            }
            PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            } completionHandler: { success, _ in
                if success { try? FileManager.default.removeItem(at: url) }
                DispatchQueue.main.async {
                    self.saveMessage = success ? AppSettings.tr("갤러리에 저장됐어요 ✓")
                                               : AppSettings.tr("갤러리 저장에 실패했어요")
                }
            }
        }
    }
}

extension CaptureController: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output == audioOutput {
            onAudioBuffer?(sampleBuffer)
        }
        guard let writer, writer.status == .writing else { return }
        let ts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if output == videoOutput {
            // Anchor the timeline on the first VIDEO frame so the clip doesn't
            // open with audio-only black.
            if !writerSessionStarted {
                writer.startSession(atSourceTime: ts)
                writerSessionStarted = true
                let startSeconds = CMTimeGetSeconds(ts)
                DispatchQueue.main.async { self.lastRecordingStart = startSeconds }
            }
            if let v = videoInput, v.isReadyForMoreMediaData { v.append(sampleBuffer) }
        } else if writerSessionStarted {
            if let a = audioInput, a.isReadyForMoreMediaData { a.append(sampleBuffer) }
        }
    }
}

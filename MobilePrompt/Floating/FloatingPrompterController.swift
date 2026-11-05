import AVKit
import AVFoundation
import Combine
import UIKit

/// The floating prompter: a Picture-in-Picture window fed with frames drawn by
/// PipFrameRenderer, so the script keeps scrolling over other apps (and the
/// mic keeps listening in stage mode). Apple's custom-player PiP route: an
/// AVSampleBufferDisplayLayer as the content source, playback controls mapped
/// onto the prompter (play/pause ↔ pause, skip ↔ a few words back/forward).
///
/// PiP only ever starts from the user's button (or automatically when the app
/// is sent to the background while the prompter is up) — never on its own.
final class FloatingPrompterController: NSObject, ObservableObject {
    @Published private(set) var isPossible = false
    @Published private(set) var isActive = false

    // Hooks the view model installs. All called on the main thread.
    var frameSource: (() -> PipFrameRenderer.Frame)?
    var onPlayPause: ((Bool) -> Void)?
    var onSkip: ((Int) -> Void)?
    var onStoppedInBackground: (() -> Void)?
    var onFailure: ((String) -> Void)?
    /// Mirrored from the view model for the synchronous delegate getter.
    var isPausedForPlayback = false

    private var controller: AVPictureInPictureController?
    private weak var layer: AVSampleBufferDisplayLayer?
    private let renderer = PipFrameRenderer()
    private var displayLink: CADisplayLink?
    private var possibleObservation: NSKeyValueObservation?
    private var lastKey = ""
    private var lastEnqueue: CFTimeInterval = 0

    var isSupported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    /// Called by PipSourceView once its layer is in the window.
    func attach(layer: AVSampleBufferDisplayLayer) {
        guard isSupported else { return }
        detach()
        self.layer = layer
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: layer, playbackDelegate: self)
        let c = AVPictureInPictureController(contentSource: source)
        c.delegate = self
        c.canStartPictureInPictureAutomaticallyFromInline = true
        controller = c
        possibleObservation = c.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            let possible = c.isPictureInPicturePossible
            DispatchQueue.main.async { self?.isPossible = possible }
        }
        startRenderLoop()
        #if DEBUG
        print("PIP|attach|supported=\(isSupported)")
        #endif
    }

    func detach() {
        stopRenderLoop()
        possibleObservation = nil
        if let controller, controller.isPictureInPictureActive { controller.stopPictureInPicture() }
        controller?.contentSource = nil
        controller = nil
        layer = nil
        isPossible = false
        isActive = false
    }

    func configure(tokens: [DisplayToken], fontSize: CGFloat) {
        renderer.configure(tokens: tokens, fontSize: fontSize)
        lastKey = ""
    }

    func start() {
        guard let controller, controller.isPictureInPicturePossible else { return }
        renderNow(force: true) // a current frame for the fly-out animation
        controller.startPictureInPicture()
    }

    func stop() {
        controller?.stopPictureInPicture()
    }

    // MARK: render loop

    private func startRenderLoop() {
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 10, maximum: 30, preferred: 30)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopRenderLoop() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func tick() {
        // Idle in the corner of the main screen: a frame every half second is
        // plenty to have something for PiP to start from.
        renderNow(force: false, minInterval: isActive ? 0 : 0.5)
    }

    private func renderNow(force: Bool, minInterval: CFTimeInterval = 0) {
        guard let layer, let frame = frameSource?() else { return }
        let now = CACurrentMediaTime()
        let key = frame.key
        let changed = key != lastKey
        // Unchanged pictures are re-sent once a second as a heartbeat.
        guard force || (changed && now - lastEnqueue >= minInterval) || now - lastEnqueue >= 1.0 else { return }
        if layer.status == .failed { layer.flush() }
        guard let sample = renderer.render(frame) else { return }
        layer.enqueue(sample)
        lastKey = key
        lastEnqueue = now
    }
}

extension FloatingPrompterController: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        DispatchQueue.main.async {
            self.isActive = true
            #if DEBUG
            print("PIP|started")
            #endif
        }
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        DispatchQueue.main.async {
            self.isActive = false
            #if DEBUG
            print("PIP|stopped|appState=\(UIApplication.shared.applicationState.rawValue)")
            #endif
            if UIApplication.shared.applicationState != .active { self.onStoppedInBackground?() }
        }
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        DispatchQueue.main.async {
            #if DEBUG
            print("PIP|failed|\(error.localizedDescription)")
            #endif
            self.onFailure?(error.localizedDescription)
        }
    }

    func pictureInPictureController(_ c: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        // The prompter cover is still presented underneath; nothing to rebuild.
        completionHandler(true)
    }
}

extension FloatingPrompterController: AVPictureInPictureSampleBufferPlaybackDelegate {
    func pictureInPictureController(_ c: AVPictureInPictureController, setPlaying playing: Bool) {
        DispatchQueue.main.async {
            self.isPausedForPlayback = !playing
            self.onPlayPause?(playing)
            self.renderNow(force: true)
        }
    }

    func pictureInPictureControllerTimeRangeForPlayback(_ c: AVPictureInPictureController) -> CMTimeRange {
        // Live content: no scrubber, no duration.
        CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(_ c: AVPictureInPictureController) -> Bool {
        isPausedForPlayback
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {
        DispatchQueue.main.async {
            self.renderer.setRenderSize(CGSize(width: CGFloat(newRenderSize.width), height: CGFloat(newRenderSize.height)))
            self.renderNow(force: true)
        }
    }

    func pictureInPictureController(_ c: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.onSkip?(skipInterval.seconds > 0 ? 5 : -5)
            self.renderNow(force: true)
            completionHandler()
        }
    }

    func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ c: AVPictureInPictureController) -> Bool {
        false
    }
}

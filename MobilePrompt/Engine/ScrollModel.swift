import UIKit

/// Where scrolling is applied. PromptTextView (a UITextView) conforms.
@MainActor
protocol ScrollSurface: AnyObject {
    var promptScrollTop: CGFloat { get set }
    var promptViewportHeight: CGFloat { get }
    var promptContentHeight: CGFloat { get }
    var isUserDragging: Bool { get }
}

/// Swift port of the web app's engine/scroll.ts.
///
/// Two layers of smoothing: "word creep" keeps inching forward at the measured
/// reading speed between STT updates, and a per-frame spring eases the scroll
/// position toward its target. Creep freezes on silence or when the aligner is
/// lost, so pauses hold position instead of drifting.
@MainActor
final class ScrollModel {
    // DEFAULT_SCROLL from scroll.ts (spring softened for mobile: SFSpeech
    // partials arrive in bursts, so a gentler ease reads much calmer)
    var readingLineFrac: CGFloat = 0.38
    var spring: CGFloat = 0.085
    /// Hard cap on voice-follow scroll speed. Even if the align target jumps
    /// (duplicate phrases, noisy partials), the screen only ever glides — and
    /// an oscillating target barely moves it at all.
    var maxVoicePxPerSec: CGFloat = 260
    var creepCapTokens: Double = 3
    var deadbandPx: CGFloat = 1.5

    enum Mode { case voice, auto }
    var mode: Mode = .voice { didSet { lastFrame = 0 } }
    var autoPxPerSec: CGFloat = 45
    var paused = false { didSet { lastFrame = 0 } }

    weak var surface: ScrollSurface?
    var offsets: [CGFloat] = []

    private var confirmedToken = 0
    private var tokensPerSec: Double = 2.5
    private var speaking = false
    private var lost = false
    private var displayLink: CADisplayLink?
    private var lastFrame: CFTimeInterval = 0
    /// Last offset THIS controller wrote. Nothing but this controller (or the
    /// user's finger) may move the text view — UITextView occasionally
    /// auto-scrolls on its own (storage edits, OS-version quirks); any such
    /// rogue move is snapped back on the next frame, before it's visible.
    private var lastWrittenTop: CGFloat = -1
    // Creep is INTEGRATED per frame, not derived from elapsed-since-confirm:
    // a pause freezes it in place (no snap back to zero), and resuming grows
    // it again at reading speed (no lurch to the cap).
    private var creepTokens: Double = 0
    // Monotonic guard: while reading forward, the target may never move
    // backward — killing the breath-pause backslide and per-word sawtooth.
    private var prevDesired: Double = 0
    private var prevConfirmed: Int = 0

    func update(confirmedToken: Int? = nil, tokensPerSec: Double? = nil,
                speaking: Bool? = nil, lost: Bool? = nil, now: CFTimeInterval) {
        if let t = confirmedToken, t != self.confirmedToken {
            self.confirmedToken = t
            self.creepTokens = 0
        }
        if let r = tokensPerSec { self.tokensPerSec = r }
        if let s = speaking { self.speaking = s }
        if let l = lost { self.lost = l }
    }

    func start() {
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
    }

    /// Immediately center a token (manual seek / start).
    func jump(to token: Double) {
        guard let s = surface else { return }
        confirmedToken = Int(token)
        prevConfirmed = Int(token)
        prevDesired = token
        creepTokens = 0
        s.promptScrollTop = y(at: token) - s.promptViewportHeight * readingLineFrac
        lastWrittenTop = s.promptScrollTop
    }

    private func y(at index: Double) -> CGFloat {
        guard !offsets.isEmpty else { return 0 }
        let i = max(0, min(offsets.count - 1, Int(index)))
        let f = CGFloat(index - Double(i))
        let a = offsets[i]
        let b = offsets[min(offsets.count - 1, i + 1)]
        return a + (b - a) * max(0, min(1, f))
    }

    @objc private func tick(_ link: CADisplayLink) {
        guard let s = surface, !s.isUserDragging else {
            lastFrame = 0
            lastWrittenTop = -1 // user owns the position; re-arm after the drag
            return
        }
        let now = CACurrentMediaTime()

        // Rogue-scroll guard: snap back anything that moved the view besides us.
        if lastWrittenTop >= 0, abs(s.promptScrollTop - lastWrittenTop) > 30 {
            #if DEBUG
            print(String(format: "SCR-ROGUE|%.2f|found=%.0f|restored=%.0f", now, s.promptScrollTop, lastWrittenTop))
            #endif
            s.promptScrollTop = lastWrittenTop
        }

        if mode == .auto {
            let dt = lastFrame > 0 ? min(now - lastFrame, 0.1) : 0
            lastFrame = now
            if !paused, autoPxPerSec > 0 {
                // Scroll until the LAST token reaches the reading line — the
                // content-size clamp stopped short of the actual script end.
                let endTop: CGFloat
                if offsets.isEmpty {
                    endTop = max(0, s.promptContentHeight - s.promptViewportHeight)
                } else {
                    endTop = max(0, y(at: Double(offsets.count - 1)) - s.promptViewportHeight * readingLineFrac)
                }
                s.promptScrollTop = min(endTop, s.promptScrollTop + autoPxPerSec * dt)
            }
            lastWrittenTop = s.promptScrollTop
            return
        }

        let dt = lastFrame > 0 ? min(now - lastFrame, 0.1) : 1.0 / 60.0
        lastFrame = now
        guard !offsets.isEmpty else { return }

        if speaking, !lost {
            creepTokens = min(creepTokens + tokensPerSec * dt, creepCapTokens)
        }
        var desired = Double(confirmedToken) + creepTokens
        if confirmedToken >= prevConfirmed {
            desired = max(desired, prevDesired) // forward reading: never regress
        }
        prevConfirmed = confirmedToken
        prevDesired = desired

        let targetTop = y(at: desired) - s.promptViewportHeight * readingLineFrac
        let diff = targetTop - s.promptScrollTop
        if abs(diff) > deadbandPx {
            let maxStep = maxVoicePxPerSec * dt
            s.promptScrollTop += max(-maxStep, min(maxStep, diff * spring))
        }
        lastWrittenTop = s.promptScrollTop
        #if DEBUG
        if now - lastDebugLog > 0.5 {
            lastDebugLog = now
            print(String(format: "SCR|%.2f|top=%.0f|target=%.0f|desired=%.2f|tok=%d|creep=%.2f|speak=%d",
                         now, s.promptScrollTop, targetTop, desired, confirmedToken, creepTokens, speaking ? 1 : 0))
        }
        #endif
    }
    private var lastDebugLog: CFTimeInterval = 0
}

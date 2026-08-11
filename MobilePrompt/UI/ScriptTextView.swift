import SwiftUI
import UIKit

/// UITextView subclass that reports layout passes and exposes the scroll
/// surface the ScrollModel drives.
final class PromptTextView: UITextView, ScrollSurface {
    var onLayoutChange: (() -> Void)?
    private var lastLayoutSize: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastLayoutSize {
            lastLayoutSize = bounds.size
            onLayoutChange?()
        }
    }

    /// The offset gate is permanent now, so this is just a plain call — kept
    /// for call-site compatibility.
    func performWithoutScrolling(_ body: () -> Void) {
        body()
    }

    // The prompter owns ALL programmatic scrolling. UITextView auto-scrolls on
    // its own (around storage edits; the exact path varies by iOS version) and
    // a lock window around our edits proved leaky — the scroll landed later
    // and rendered for a frame before being corrected, which reads as jitter.
    // So: DROP every offset write that isn't ours or the user's finger,
    // before it can ever render.
    private var allowOffsetWrite = false

    override var contentOffset: CGPoint {
        get { super.contentOffset }
        set {
            guard allowOffsetWrite || isTracking || isDragging || isDecelerating else { return }
            super.contentOffset = newValue
        }
    }

    override func setContentOffset(_ contentOffset: CGPoint, animated: Bool) {
        guard allowOffsetWrite || isTracking || isDragging || isDecelerating else { return }
        super.setContentOffset(contentOffset, animated: animated)
    }

    override func scrollRectToVisible(_ rect: CGRect, animated: Bool) {}

    override func scrollRangeToVisible(_ range: NSRange) {}

    // ScrollSurface
    var promptScrollTop: CGFloat {
        get { contentOffset.y }
        set {
            allowOffsetWrite = true
            super.contentOffset = CGPoint(x: 0, y: newValue)
            allowOffsetWrite = false
        }
    }
    var promptViewportHeight: CGFloat { bounds.height }
    var promptContentHeight: CGFloat { contentSize.height + contentInset.top + contentInset.bottom }
    var isUserDragging: Bool { isTracking || isDragging || isDecelerating }
}

/// Renders the script with TextKit so we get exact per-token line positions
/// (for voice-follow scroll) and cheap current-word highlighting via
/// attribute edits — no SwiftUI re-render ever touches the script.
struct ScriptTextView: UIViewRepresentable {
    @ObservedObject var viewModel: PrompterViewModel
    let tokens: [DisplayToken]
    let fontSize: CGFloat
    let readingLineFrac: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PromptTextView {
        let tv = PromptTextView()
        tv.backgroundColor = .clear
        tv.isEditable = false
        tv.isSelectable = false
        tv.showsVerticalScrollIndicator = false
        tv.contentInsetAdjustmentBehavior = .never
        tv.textContainer.lineFragmentPadding = 0
        tv.onLayoutChange = { [weak tv, weak coordinator = context.coordinator] in
            guard let tv, let coordinator else { return }
            coordinator.relayout(tv)
        }
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        tv.addGestureRecognizer(tap)
        context.coordinator.rebuildText(tv)
        return tv
    }

    func updateUIView(_ tv: PromptTextView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.builtTokens != tokens || context.coordinator.builtFontSize != fontSize {
            context.coordinator.rebuildText(tv)
        } else if context.coordinator.builtFrac != readingLineFrac {
            context.coordinator.relayout(tv)
        }
    }

    @MainActor
    final class Coordinator: NSObject {
        var parent: ScriptTextView
        var builtTokens: [DisplayToken] = []
        var builtFontSize: CGFloat = 0
        var builtFrac: CGFloat = -1
        private var ranges: [NSRange] = []

        init(_ parent: ScriptTextView) { self.parent = parent }

        func rebuildText(_ tv: PromptTextView) {
            builtTokens = parent.tokens
            builtFontSize = parent.fontSize

            let font = UIFont.systemFont(ofSize: parent.fontSize, weight: .semibold)
            let para = NSMutableParagraphStyle()
            para.lineSpacing = parent.fontSize * 0.4
            para.paragraphSpacing = parent.fontSize * 0.6
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.9)
            shadow.shadowBlurRadius = 4
            shadow.shadowOffset = CGSize(width: 0, height: 1)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white,
                .paragraphStyle: para,
                .shadow: shadow,
            ]

            let text = NSMutableAttributedString()
            ranges = []
            for (i, t) in parent.tokens.enumerated() {
                if i > 0 {
                    text.append(NSAttributedString(string: t.breakBefore ? "\n" : " ", attributes: attrs))
                }
                let start = text.length
                text.append(NSAttributedString(string: t.raw, attributes: attrs))
                ranges.append(NSRange(location: start, length: text.length - start))
            }
            tv.textStorage.setAttributedString(text)
            relayout(tv)
        }

        /// Recompute per-token center-Y offsets and hand everything to the VM.
        func relayout(_ tv: PromptTextView) {
            guard tv.bounds.height > 0, !ranges.isEmpty else { return }
            builtFrac = parent.readingLineFrac

            // Inset so the first/last token can reach the reading line.
            let top = tv.bounds.height * parent.readingLineFrac
            let bottom = tv.bounds.height * (1 - parent.readingLineFrac)
            if tv.textContainerInset.top != top || tv.textContainerInset.bottom != bottom {
                tv.textContainerInset = UIEdgeInsets(top: top, left: 0, bottom: bottom, right: 0)
            }

            let lm = tv.layoutManager
            lm.ensureLayout(for: tv.textContainer)
            let inset = tv.textContainerInset.top
            let offsets: [CGFloat] = ranges.map { range in
                let glyphRange = lm.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
                let rect = lm.boundingRect(forGlyphRange: glyphRange, in: tv.textContainer)
                return rect.midY + inset
            }
            parent.viewModel.attach(textView: tv, ranges: ranges, offsets: offsets)
        }

        @objc func handleTap(_ gr: UITapGestureRecognizer) {
            guard let tv = gr.view as? PromptTextView else { return }
            var point = gr.location(in: tv)
            point.x -= tv.textContainerInset.left
            point.y -= tv.textContainerInset.top
            let idx = tv.layoutManager.characterIndex(for: point, in: tv.textContainer,
                                                      fractionOfDistanceBetweenInsertionPoints: nil)
            parent.viewModel.tapped(characterIndex: idx)
        }
    }
}

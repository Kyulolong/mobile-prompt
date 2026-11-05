import UIKit
import AVFoundation
import CoreMedia

/// Draws the script into video frames for the Picture-in-Picture window: a
/// black card, the lines around the reading position, the current word in
/// the highlight colour, and a status dot. Same text joining rule as
/// ScriptTextView, so what floats is what the prompter shows.
final class PipFrameRenderer {
    enum Status { case listening, lost, auto, interrupted, off }

    struct Frame: Equatable {
        var fractionalToken: Double
        var highlight: Int
        var highlightColor: UIColor?
        var status: Status
        var paused: Bool

        /// Coarse identity: frames that differ by less than this are the same picture.
        var key: String { "\(Int(fractionalToken * 100))|\(highlight)|\(status)|\(paused)" }
    }

    private let storage = NSTextStorage()
    private let layoutManager = NSLayoutManager()
    private let container = NSTextContainer(size: .zero)
    private var tokens: [DisplayToken] = []
    private var ranges: [NSRange] = []
    private var midY: [CGFloat] = []
    private(set) var size = CGSize(width: 640, height: 360)
    private var fontSize: CGFloat = 26
    private var pool: CVPixelBufferPool?
    private let padding: CGFloat = 18
    private var lastHighlight = -1

    init() {
        layoutManager.addTextContainer(container)
        storage.addLayoutManager(layoutManager)
        container.lineFragmentPadding = 0
    }

    func configure(tokens: [DisplayToken], fontSize: CGFloat) {
        self.tokens = tokens
        self.fontSize = fontSize
        rebuildText()
    }

    /// PiP windows come in many sizes; the long edge is capped so a 4K-class
    /// pixel buffer is never needed for a few lines of text.
    func setRenderSize(_ requested: CGSize) {
        guard requested.width > 0, requested.height > 0 else { return }
        let scale = min(1, 1280 / max(requested.width, requested.height))
        let s = CGSize(width: (requested.width * scale).rounded(), height: (requested.height * scale).rounded())
        guard s != size else { return }
        size = s
        pool = nil
        rebuildText()
    }

    private func rebuildText() {
        // Scale the font with the frame so the text stays readable at any window size.
        let scaledFont = fontSize * max(0.6, size.width / 640)
        let font = UIFont.systemFont(ofSize: scaledFont, weight: .semibold)
        let heavy = UIFont.systemFont(ofSize: scaledFont, weight: .heavy)
        let para = NSMutableParagraphStyle()
        para.lineSpacing = scaledFont * 0.3
        para.paragraphSpacing = scaledFont * 0.5
        let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white, .paragraphStyle: para]

        let text = NSMutableAttributedString()
        ranges = []
        for (i, t) in tokens.enumerated() {
            if i > 0 { text.append(NSAttributedString(string: t.breakBefore ? "\n" : " ", attributes: base)) }
            var attrs = base
            switch t.kind {
            case .emphasis: attrs[.font] = heavy
            case .note: attrs[.foregroundColor] = PrompterViewModel.noteColor
            case .plain: break
            }
            let start = text.length
            text.append(NSAttributedString(string: t.display, attributes: attrs))
            ranges.append(NSRange(location: start, length: text.length - start))
        }
        storage.setAttributedString(text)
        container.size = CGSize(width: size.width - 2 * padding, height: .greatestFiniteMagnitude)
        layoutManager.ensureLayout(for: container)
        midY = ranges.map { range in
            let glyphs = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            return layoutManager.boundingRect(forGlyphRange: glyphs, in: container).midY
        }
        lastHighlight = -1
    }

    private func y(at index: Double) -> CGFloat {
        guard !midY.isEmpty else { return 0 }
        let i = max(0, min(midY.count - 1, Int(index)))
        let f = CGFloat(index - Double(i))
        let a = midY[i]
        let b = midY[min(midY.count - 1, i + 1)]
        return a + (b - a) * max(0, min(1, f))
    }

    private func baseColor(_ idx: Int) -> UIColor {
        idx < tokens.count && tokens[idx].kind == .note ? PrompterViewModel.noteColor : .white
    }

    func render(_ frame: Frame) -> CMSampleBuffer? {
        guard let pixelBuffer = makePixelBuffer() else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        guard let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pixelBuffer),
                                  width: Int(size.width), height: Int(size.height),
                                  bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }

        ctx.setFillColor(UIColor.black.cgColor)
        ctx.fill(CGRect(origin: .zero, size: size))

        // UIKit drawing: flip into top-left coordinates.
        ctx.translateBy(x: 0, y: size.height)
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)

        if lastHighlight >= 0, lastHighlight < ranges.count {
            storage.addAttribute(.foregroundColor, value: baseColor(lastHighlight), range: ranges[lastHighlight])
        }
        if frame.highlight >= 0, frame.highlight < ranges.count, let color = frame.highlightColor {
            storage.addAttribute(.foregroundColor, value: color, range: ranges[frame.highlight])
            lastHighlight = frame.highlight
        } else {
            lastHighlight = -1
        }

        let origin = CGPoint(x: padding, y: size.height * 0.5 - y(at: frame.fractionalToken))
        let visible = CGRect(x: 0, y: -origin.y, width: size.width, height: size.height)
        let glyphs = layoutManager.glyphRange(forBoundingRect: visible, in: container)
        if glyphs.length > 0 {
            layoutManager.drawGlyphs(forGlyphRange: glyphs, at: origin)
        }

        // Status dot, top-right.
        let dotColor: UIColor
        switch frame.status {
        case .listening: dotColor = .systemGreen
        case .lost: dotColor = .systemOrange
        case .auto: dotColor = .systemBlue
        case .interrupted: dotColor = .systemRed
        case .off: dotColor = .gray
        }
        dotColor.setFill()
        let r: CGFloat = max(4, size.width / 90)
        UIBezierPath(ovalIn: CGRect(x: size.width - padding - r * 2, y: padding, width: r * 2, height: r * 2)).fill()
        if frame.paused {
            UIColor.white.withAlphaComponent(0.85).setFill()
            let barW = r * 0.9, barH = r * 2.6
            let x0 = size.width - padding - r * 2 - barW * 3.4
            UIBezierPath(rect: CGRect(x: x0, y: padding - r * 0.3, width: barW, height: barH)).fill()
            UIBezierPath(rect: CGRect(x: x0 + barW * 1.6, y: padding - r * 0.3, width: barW, height: barH)).fill()
        }
        UIGraphicsPopContext()

        return makeSampleBuffer(pixelBuffer)
    }

    private func makePixelBuffer() -> CVPixelBuffer? {
        if pool == nil {
            let attrs: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Int(size.width),
                kCVPixelBufferHeightKey as String: Int(size.height),
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ]
            var p: CVPixelBufferPool?
            CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &p)
            pool = p
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
        return buffer
    }

    private func makeSampleBuffer(_ pixelBuffer: CVPixelBuffer) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixelBuffer, formatDescriptionOut: &format) == noErr,
              let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pixelBuffer, formatDescription: format,
                                                       sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [CFMutableDictionary],
           let first = attachments.first {
            CFDictionarySetValue(first, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}

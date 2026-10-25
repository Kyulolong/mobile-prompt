// Play Store graphics: 512×512 icon PNG and 1024×500 feature graphic, drawn with
// the same artwork as make_icon.swift. Usage: swift make_feature_graphic.swift <outdir>
import AppKit

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "android"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let pointGreen = NSColor(calibratedRed: 0x8F / 255.0, green: 1.0, blue: 0.0, alpha: 1)
let recRed = NSColor(calibratedRed: 1.0, green: 0.27, blue: 0.23, alpha: 1)

func gradient(_ ctx: CGContext, w: CGFloat, h: CGFloat) {
    let colors = [NSColor(calibratedRed: 0.10, green: 0.11, blue: 0.15, alpha: 1).cgColor,
                  NSColor(calibratedRed: 0.03, green: 0.04, blue: 0.06, alpha: 1).cgColor] as CFArray
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: h), end: CGPoint(x: 0, y: 0), options: [])
}

/// Script-lines artwork in a unit box of `size`, origin at (ox, oy) in flipped (top-left) coords.
func artwork(_ ctx: CGContext, ox: CGFloat, oy: CGFloat, size: CGFloat, canvasH: CGFloat) {
    let s = size / 1024
    func bar(y: CGFloat, x: CGFloat, w: CGFloat, color: NSColor) {
        let h = 64 * s
        let rect = CGRect(x: ox + x * s, y: canvasH - (oy + y * s) - h, width: w * s, height: h)
        ctx.addPath(CGPath(roundedRect: rect, cornerWidth: h / 2, cornerHeight: h / 2, transform: nil))
        ctx.setFillColor(color.cgColor)
        ctx.fillPath()
    }
    let white = NSColor(calibratedWhite: 1.0, alpha: 0.92)
    let dim = NSColor(calibratedWhite: 1.0, alpha: 0.38)
    bar(y: 200, x: 140, w: 560, color: dim)
    bar(y: 316, x: 140, w: 700, color: dim)
    bar(y: 432, x: 140, w: 500, color: pointGreen)
    bar(y: 548, x: 140, w: 660, color: white)
    bar(y: 664, x: 140, w: 580, color: white)
    bar(y: 780, x: 140, w: 380, color: white)
    ctx.setFillColor(recRed.cgColor)
    let d = 88 * s
    ctx.fillEllipse(in: CGRect(x: ox + (1024 - 140 - 88) * s, y: canvasH - (oy + 176 * s) - d, width: d, height: d))
}

func render(w: CGFloat, h: CGFloat, name: String, draw: (CGContext) -> Void) {
    let image = NSImage(size: NSSize(width: w, height: h))
    image.lockFocus()
    let ctx = NSGraphicsContext.current!.cgContext
    draw(ctx)
    image.unlockFocus()
    let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
    rep.size = NSSize(width: w, height: h)
    let png = rep.representation(using: .png, properties: [:])!
    let path = "\(outDir)/\(name)"
    try! png.write(to: URL(fileURLWithPath: path))
    print("wrote \(path) \(rep.pixelsWide)x\(rep.pixelsHigh)")
}

// 512×512 icon (Play wants a full-bleed square; Play applies its own corner mask).
render(w: 512, h: 512, name: "icon-512.png") { ctx in
    gradient(ctx, w: 512, h: 512)
    artwork(ctx, ox: 0, oy: 0, size: 512, canvasH: 512)
}

// 1024×500 feature graphic: artwork on the left, title on the right.
render(w: 1024, h: 500, name: "feature-graphic.png") { ctx in
    gradient(ctx, w: 1024, h: 500)
    artwork(ctx, ox: 60, oy: 20, size: 460, canvasH: 500)
    let title = NSAttributedString(string: "보이스 프롬프터", attributes: [
        .font: NSFont.systemFont(ofSize: 72, weight: .bold),
        .foregroundColor: NSColor.white,
    ])
    let sub = NSAttributedString(string: "목소리를 따라오는 텔레프롬프터", attributes: [
        .font: NSFont.systemFont(ofSize: 34, weight: .medium),
        .foregroundColor: NSColor(calibratedWhite: 1.0, alpha: 0.7),
    ])
    let tag = NSAttributedString(string: "온디바이스 음성인식 · 셀피 녹화 · 자막 자동 생성", attributes: [
        .font: NSFont.systemFont(ofSize: 24, weight: .regular),
        .foregroundColor: pointGreen,
    ])
    title.draw(at: NSPoint(x: 540, y: 500 - 190))
    sub.draw(at: NSPoint(x: 542, y: 500 - 250))
    tag.draw(at: NSPoint(x: 542, y: 500 - 305))
}

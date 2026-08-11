// Compose an App Store marketing screenshot:
// background art + headline/subline (native text rendering) + app screenshot
// with rounded corners, border and shadow, bleeding off the bottom.
// usage: swift compose_shot.swift bg.png shot.png W H "headline" "subline" out.png
import AppKit

let a = CommandLine.arguments
let bgPath = a[1], shotPath = a[2]
let W = CGFloat(Double(a[3])!), H = CGFloat(Double(a[4])!)
let headline = a[5], subline = a[6], outPath = a[7]

guard let bg = NSImage(contentsOfFile: bgPath), let shot = NSImage(contentsOfFile: shotPath) else {
    fatalError("cannot load images")
}

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(W), pixelsHigh: Int(H),
                           bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                           colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
let gctx = NSGraphicsContext(bitmapImageRep: rep)!
NSGraphicsContext.current = gctx
let ctx = gctx.cgContext

let bgSize = bg.size
let scale = max(W / bgSize.width, H / bgSize.height)
let bw = bgSize.width * scale, bh = bgSize.height * scale
bg.draw(in: CGRect(x: (W - bw) / 2, y: (H - bh) / 2, width: bw, height: bh))

func drawCentered(_ text: String, fontSize: CGFloat, weight: NSFont.Weight,
                  color: NSColor, topY: CGFloat) {
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.55)
    shadow.shadowBlurRadius = fontSize * 0.12
    shadow.shadowOffset = NSSize(width: 0, height: -fontSize * 0.03)
    let para = NSMutableParagraphStyle()
    para.alignment = .center
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: fontSize, weight: weight),
        .foregroundColor: color,
        .paragraphStyle: para,
        .shadow: shadow,
    ]
    let s = NSAttributedString(string: text, attributes: attrs)
    let size = s.boundingRect(with: NSSize(width: W - 80, height: 10000),
                              options: [.usesLineFragmentOrigin]).size
    s.draw(in: CGRect(x: 40, y: H - topY - size.height, width: W - 80, height: size.height + 10))
}

let headSize = W * 0.062
drawCentered(headline, fontSize: headSize, weight: .heavy, color: .white, topY: H * 0.055)
drawCentered(subline, fontSize: headSize * 0.44, weight: .medium,
             color: NSColor(calibratedRed: 1.0, green: 0.85, blue: 0.30, alpha: 1), topY: H * 0.055 + headSize * 2.45)

let shotAspect = shot.size.width / shot.size.height
let sw = W * 0.78
let sh = sw / shotAspect
let sx = (W - sw) / 2
let syTop = H * 0.235
let rect = CGRect(x: sx, y: H - syTop - sh, width: sw, height: sh)
let radius = sw * 0.085

ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: -18), blur: 70,
              color: NSColor.black.withAlphaComponent(0.65).cgColor)
let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
ctx.addPath(path)
ctx.setFillColor(NSColor.black.cgColor)
ctx.fillPath()
ctx.restoreGState()

ctx.saveGState()
ctx.addPath(path)
ctx.clip()
shot.draw(in: rect)
ctx.restoreGState()

ctx.addPath(CGPath(roundedRect: rect.insetBy(dx: -3, dy: -3), cornerWidth: radius + 3, cornerHeight: radius + 3, transform: nil))
ctx.setStrokeColor(NSColor(calibratedWhite: 0.75, alpha: 0.9).cgColor)
ctx.setLineWidth(6)
ctx.strokePath()

NSGraphicsContext.restoreGraphicsState()
let png = rep.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath) \(rep.pixelsWide)x\(rep.pixelsHigh)")

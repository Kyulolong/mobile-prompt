// Renders the app icon: dark backdrop, teleprompter script lines with the
// current line highlighted in Kyulolong point green (#8FFF00), and a record dot.
import AppKit

let size = CGFloat(1024)
let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()
guard let ctx = NSGraphicsContext.current?.cgContext else { fatalError("no context") }

let colors = [NSColor(calibratedRed: 0.10, green: 0.11, blue: 0.15, alpha: 1).cgColor,
              NSColor(calibratedRed: 0.03, green: 0.04, blue: 0.06, alpha: 1).cgColor] as CFArray
let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])!
ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: size), end: CGPoint(x: 0, y: 0), options: [])

func bar(y: CGFloat, x: CGFloat, w: CGFloat, h: CGFloat, color: NSColor) {
    let rect = CGRect(x: x, y: size - y - h, width: w, height: h)
    let path = CGPath(roundedRect: rect, cornerWidth: h / 2, cornerHeight: h / 2, transform: nil)
    ctx.addPath(path)
    ctx.setFillColor(color.cgColor)
    ctx.fillPath()
}

let white = NSColor(calibratedWhite: 1.0, alpha: 0.92)
let dim = NSColor(calibratedWhite: 1.0, alpha: 0.38)
// Kyulolong point color #8FFF00
let pointGreen = NSColor(calibratedRed: 0x8F / 255.0, green: 1.0, blue: 0.0, alpha: 1)

let lineH = CGFloat(64)
let left = CGFloat(140)
bar(y: 200, x: left, w: 560, h: lineH, color: dim)
bar(y: 316, x: left, w: 700, h: lineH, color: dim)
bar(y: 432, x: left, w: 500, h: lineH, color: pointGreen)
bar(y: 548, x: left, w: 660, h: lineH, color: white)
bar(y: 664, x: left, w: 580, h: lineH, color: white)
bar(y: 780, x: left, w: 380, h: lineH, color: white)

ctx.setFillColor(NSColor(calibratedRed: 1.0, green: 0.27, blue: 0.23, alpha: 1).cgColor)
ctx.fillEllipse(in: CGRect(x: size - 140 - 88, y: size - 200 - 24, width: 88, height: 88))

image.unlockFocus()

let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
rep.size = NSSize(width: size, height: size)
let png = rep.representation(using: .png, properties: [:])!
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
try! png.write(to: URL(fileURLWithPath: out))
print("wrote \(out) \(rep.pixelsWide)x\(rep.pixelsHigh)")

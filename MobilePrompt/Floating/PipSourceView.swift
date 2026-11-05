import SwiftUI
import AVFoundation

/// Hosts the AVSampleBufferDisplayLayer the Picture-in-Picture window is fed
/// from. Sits over the script in stage mode, nearly invisible: it only exists
/// so PiP has a layer in the window to animate out of.
struct PipSourceView: UIViewRepresentable {
    let controller: FloatingPrompterController

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
        var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.displayLayer.videoGravity = .resizeAspect
        controller.attach(layer: view.displayLayer)
        return view
    }

    func updateUIView(_ uiView: LayerView, context: Context) {}

    static func dismantleUIView(_ uiView: LayerView, coordinator: ()) {}
}

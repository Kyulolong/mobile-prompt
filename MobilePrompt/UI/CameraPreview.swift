import SwiftUI
import AVFoundation

/// Live front-camera preview backed by AVCaptureVideoPreviewLayer.
/// Registers its layer with the CaptureController so the rotation coordinator
/// can keep preview and recording upright in every orientation.
struct CameraPreview: UIViewRepresentable {
    let controller: CaptureController

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = controller.session
        view.previewLayer.videoGravity = .resizeAspectFill
        controller.attachPreviewLayer(view.previewLayer)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

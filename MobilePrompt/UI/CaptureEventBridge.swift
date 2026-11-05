import SwiftUI
import AVKit

/// Hardware shutter: the volume buttons (and Camera Control / AirPods presses
/// on devices that have them) start and stop recording while the prompter is
/// up. AVCaptureEventInteraction only delivers while the app is capturing,
/// which is exactly the prompter's lifetime — so volume keeps its normal job
/// everywhere else. iOS 17.2+; a no-op view before that.
struct CaptureEventBridge: UIViewRepresentable {
    var enabled: Bool
    var onPress: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPress: onPress) }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        if #available(iOS 17.2, *) {
            let coordinator = context.coordinator
            let interaction = AVCaptureEventInteraction { event in
                guard event.phase == .ended else { return } // once per press
                coordinator.onPress()
            }
            interaction.isEnabled = enabled
            view.addInteraction(interaction)
            coordinator.interaction = interaction
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onPress = onPress
        if #available(iOS 17.2, *) {
            (context.coordinator.interaction as? AVCaptureEventInteraction)?.isEnabled = enabled
        }
    }

    final class Coordinator {
        var onPress: () -> Void
        var interaction: Any?
        init(onPress: @escaping () -> Void) { self.onPress = onPress }
    }
}

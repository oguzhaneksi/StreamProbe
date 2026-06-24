import AVFoundation
import SwiftUI
import UIKit

/// A `UIView` whose backing layer IS an `AVPlayerLayer` (via `layerClass`), so it auto-tracks
/// bounds with no manual frame math. Chosen over SwiftUI `VideoPlayer` (cannot hide system
/// controls) and `AVPlayerViewController` (rigid styling) because the demo uses fully custom controls.
final class PlayerHostView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    var player: AVPlayer? {
        get { playerLayer.player }
        set {
            playerLayer.player = newValue
            playerLayer.videoGravity = .resizeAspect
        }
    }
}

/// SwiftUI wrapper for `PlayerHostView`. The `AVPlayer` lives in the view-model (not here), so
/// SwiftUI re-creating this representable never resets playback.
struct VideoSurfaceView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.player = player
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ uiView: PlayerHostView, context: Context) {
        if uiView.player !== player { uiView.player = player }
    }
}

import UIKit
import AVFoundation
import StreamProbe

class PlayerViewController: UIViewController {
    let probe = StreamProbe_()

    private var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?

    // Sample HLS stream (public Apple HLS test stream, no DRM)
    private let streamURL = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8")!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        let avPlayer = AVPlayer(url: streamURL)
        player = avPlayer

        let layer = AVPlayerLayer(player: avPlayer)
        layer.videoGravity = .resizeAspect
        view.layer.addSublayer(layer)
        playerLayer = layer

        probe.attach(player: avPlayer)
        probe.show()
        avPlayer.play()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerLayer?.frame = view.bounds
    }
}

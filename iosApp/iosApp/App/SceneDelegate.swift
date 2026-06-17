import UIKit
import StreamProbe

/// Creates two windows: the main app window (PlayerViewController) and a separate
/// `StreamProbeOverlayWindow` at `windowLevel = .alert + 1` so the overlay always
/// sits above the host app. Both windows are held with strong references.
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    var overlayWindow: StreamProbeOverlayWindow?

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        // Main app window
        let mainWindow = UIWindow(windowScene: windowScene)
        let playerVC = PlayerViewController()
        mainWindow.rootViewController = playerVC
        mainWindow.makeKeyAndVisible()
        window = mainWindow

        // Overlay window — separate root-level UIWindow
        let overlay = StreamProbeOverlayWindow(windowScene: windowScene)
        overlay.windowLevel = .alert + 1          // always above the host app
        overlay.backgroundColor = .clear
        overlay.isHidden = false
        let overlayVC = OverlayHostViewController(presenter: playerVC.probe.overlayPresenter)
        overlay.rootViewController = overlayVC
        overlayWindow = overlay
    }
}

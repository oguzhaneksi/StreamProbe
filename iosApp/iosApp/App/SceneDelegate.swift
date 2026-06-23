import StreamProbe
import UIKit

/// Creates and retains the StreamProbe overlay window (a separate root-level `UIWindow` at
/// `windowLevel = .alert + 1`) above the SwiftUI main window. Never `makeKeyAndVisible` — that
/// would steal key-window status from the player UI. The overlay observes the shared probe's presenter.
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var overlayWindow: UIWindow?   // strong ref — a detached UIWindow deallocates

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let deps = AppDependencies.shared
        let overlay = deps.probe.makeOverlayWindow(windowScene: windowScene)
        overlayWindow = overlay

        deps.registerOverlayWindow(overlay)
    }
}

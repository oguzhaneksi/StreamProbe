import Combine
import StreamProbe
import UIKit

/// Process-wide dependency holder. Owns the single `StreamProbe_` (shared by the player and the
/// overlay window) and the `SettingsStore`. Bridges `settings.overlayVisible` to the overlay
/// window's `isHidden` so the Settings toggle shows/hides the overlay live.
final class AppDependencies {
    static let shared = AppDependencies()

    let probe = StreamProbe_()
    let settings = SettingsStore()

    private weak var overlayWindow: UIWindow?
    private var cancellable: AnyCancellable?

    private init() {
        cancellable = settings.$overlayVisible.sink { [weak self] visible in
            self?.overlayWindow?.isHidden = !visible
        }
    }

    /// Called by `SceneDelegate` after the overlay window is created; applies the current pref.
    func registerOverlayWindow(_ window: UIWindow) {
        overlayWindow = window
        window.isHidden = !settings.overlayVisible
    }
}

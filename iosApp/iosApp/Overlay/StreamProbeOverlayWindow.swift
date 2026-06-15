import UIKit

/// A root-level UIWindow for the StreamProbe debug overlay.
///
/// Overrides hitTest so that touches outside the overlay panel's opaque subviews
/// fall through to the app's main window, keeping the host app fully interactive
/// even when the player is not full-screen.
final class StreamProbeOverlayWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hitView = super.hitTest(point, with: event) else { return nil }
        // Pass through if the hit lands on the transparent root itself (or its background).
        return hitView === self || hitView === rootViewController?.view ? nil : hitView
    }
}

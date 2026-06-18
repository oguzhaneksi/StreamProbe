import UIKit

/// A root-level UIWindow for the StreamProbe debug overlay.
///
/// Overrides hitTest so touches outside the overlay panel's opaque subviews fall through to the
/// app's main window, keeping the host app interactive even when the player is not full-screen.
///
/// iOS 18 calls `hitTest` TWICE per event (the second pass returns `rootViewController.view`); the
/// naive "return nil if hit == rootView" check swallows that second pass and breaks passthrough,
/// so we track seen events and let the second pass through. (Apple Dev Forums thread 762292.)
final class StreamProbeOverlayWindow: UIWindow {
    private var seenEvents = Set<UIEvent>()

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let rootView = rootViewController?.view else { return nil }
        guard let event else { return super.hitTest(point, with: nil) }

        guard let hitView = super.hitTest(point, with: event) else {
            seenEvents.removeAll()
            return nil
        }

        if seenEvents.contains(event) {
            seenEvents.removeAll()
            return hitView
        }
        if hitView === self || hitView === rootView {
            return nil
        }
        if #available(iOS 18, *) {
            seenEvents.insert(event)
        }
        return hitView
    }
}

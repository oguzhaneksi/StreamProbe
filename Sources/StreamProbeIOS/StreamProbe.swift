import AVFoundation
import StreamProbeCore
import UIKit

/// Public iOS entry point for the StreamProbe debug SDK.
///
/// Owns the Kotlin Core (`ProbeCore`): the shared session store, the common `OverlayPresenter`,
/// and the presenter-collector lifecycle. Attach it to an `AVPlayer`, call `show()`, and install
/// the built-in overlay with `makeOverlayWindow(windowScene:)` — or observe `overlayPresenter`
/// directly to drive a custom renderer.
///
/// Not thread-safe: call all methods from the main thread (mirrors `ProbeCore`'s contract). The
/// AVFoundation probe that feeds the Core is wired in a subsequent change; until then `attach`
/// only resets the session.
public final class StreamProbe {
    private let core = ProbeCore()

    public init() {}

    /// The common presenter. Observe `viewState` (SKIE async sequence) to drive a custom overlay,
    /// or use `makeOverlayWindow(windowScene:)` for the built-in one.
    public var overlayPresenter: OverlayPresenter { core.presenter }

    /// Attaches StreamProbe to `player` and clears the previous session. Probe wiring lands in a
    /// later change; the `player` is not yet observed.
    public func attach(player: AVPlayer) {
        core.clear()
    }

    /// Starts the presenter collectors so the overlay updates live. Idempotent.
    public func show() {
        core.start()
    }

    /// Stops the presenter collectors so live updates freeze (the overlay stays visible). Idempotent.
    public func hide() {
        core.stop()
    }

    /// Detaches: stops the collectors and clears the session.
    public func detach() {
        core.stop()
        core.clear()
    }

    /// Creates the built-in always-on-top overlay window for `windowScene`, rendering this probe's
    /// diagnostics via `overlayPresenter`. The caller retains the returned window and controls its
    /// `isHidden` to show/hide the overlay UI (independent of `show()`/`hide()`, which control the
    /// live data feed).
    public func makeOverlayWindow(windowScene: UIWindowScene) -> UIWindow {
        let window = StreamProbeOverlayWindow(windowScene: windowScene)
        window.windowLevel = .alert + 1
        window.backgroundColor = .clear
        let host = OverlayHostViewController(presenter: core.presenter)
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = false
        return window
    }
}

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
/// Not thread-safe: call all methods from the main thread (mirrors `ProbeCore`'s contract). `attach`
/// installs an `AVPlayerProbe` that observes AVFoundation and feeds the Core.
public final class StreamProbe {
    private let core = ProbeCore()
    private var probe: AVPlayerProbe?

    public init() {}

    /// The common presenter. Observe `viewState` (SKIE async sequence) to drive a custom overlay,
    /// or use `makeOverlayWindow(windowScene:)` for the built-in one.
    public var overlayPresenter: OverlayPresenter { core.presenter }

    /// Attaches StreamProbe to `player`, clears the previous session, and starts observing
    /// AVFoundation. Call `show()` afterward to start the overlay's live updates.
    public func attach(player: AVPlayer) {
        core.clear()
        let probe = AVPlayerProbe(sink: core)
        self.probe = probe
        probe.attach(player: player)
    }

    /// Starts the presenter collectors so the overlay updates live. Idempotent.
    public func show() {
        core.start()
    }

    /// Stops the presenter collectors so live updates freeze (the overlay stays visible). Idempotent.
    public func hide() {
        core.stop()
    }

    /// Detaches: stops the AVFoundation observer, stops the presenter collectors, and clears the session.
    public func detach() {
        probe?.detach()
        probe = nil
        core.stop()
        core.clear()
    }

    /// Creates the built-in always-on-top overlay window for `windowScene`, rendering this probe's
    /// diagnostics via `overlayPresenter`. The caller **must** retain the returned window (a detached
    /// `UIWindow` with no strong reference is silently deallocated) and controls its `isHidden` to
    /// show/hide the overlay UI (independent of `show()`/`hide()`, which control the live data feed).
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

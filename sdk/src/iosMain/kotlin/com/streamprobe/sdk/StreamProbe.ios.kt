package com.streamprobe.sdk

import com.streamprobe.sdk.internal.AVPlayerProbe
import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.internal.presenter.OverlayPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.AVFoundation.AVPlayer

/**
 * iOS entry point for the StreamProbe debug SDK.
 *
 * Owns the shared [SessionStore], the [AVPlayerProbe] that feeds it from AVFoundation, and the
 * common [OverlayPresenter] that turns the store into a render-ready [com.streamprobe.sdk.internal.presenter.OverlayViewState].
 *
 * The SDK creates and destroys **no** UIKit objects: the host app owns the overlay window
 * lifecycle and observes [overlayPresenter] (via SKIE) to render it. [show] only starts the
 * presenter collectors; [hide] only cancels them (live updates freeze, the overlay stays visible).
 *
 * Typical usage:
 * ```swift
 * let probe = StreamProbe_()
 * probe.attach(player: avPlayer)
 * probe.show()
 * // In SceneDelegate: create StreamProbeOverlayWindow, pass probe.overlayPresenter to it
 * ```
 */
actual class StreamProbe {
    private val store = SessionStore()

    /** Exposed internally so [iosTest] can assert on store contents without a UIKit overlay. */
    internal val sessionStore: SessionStore get() = store

    private val probe = AVPlayerProbe(store)

    /** Common presenter; observe [OverlayPresenter.viewState] via SKIE to drive the UIKit overlay. */
    public val overlayPresenter: OverlayPresenter = OverlayPresenter(store)

    private var presenterScope: CoroutineScope? = null

    /**
     * Starts the [OverlayPresenter] coroutine collectors on the main dispatcher.
     * Call once after [attach]; the overlay window (created by the host app in Swift)
     * observes [overlayPresenter] independently.
     */
    public fun show() {
        if (presenterScope != null) return
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        presenterScope = scope
        overlayPresenter.start(scope)
    }

    /**
     * Cancels the presenter scope so live updates stop (overlay freezes).
     * Call when hiding the overlay window; call [show] again to resume.
     */
    public fun hide() {
        presenterScope?.cancel()
        presenterScope = null
    }

    /**
     * Attaches StreamProbe to [player]. Scoped to the player lifecycle; no UIKit involvement.
     * Clears the previous session; call [show] after to start the overlay.
     */
    fun attach(player: AVPlayer) {
        store.clear()
        probe.attach(player)
    }

    actual fun detach() {
        hide()
        probe.detach()
        store.clear()
    }
}

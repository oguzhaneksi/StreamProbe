package com.streamprobe.sdk

import com.streamprobe.sdk.internal.AVPlayerProbe
import com.streamprobe.sdk.internal.SessionStore
import platform.AVFoundation.AVPlayer

/**
 * iOS entry point for the StreamProbe debug SDK. Headless in Phase 3 — it owns the shared
 * [SessionStore] and an [AVPlayerProbe] that feeds it from AVFoundation. The Phase 4 overlay
 * (separate `UIWindow`) and `show`/`hide` are added later; the store is already observable now.
 */
actual class StreamProbe {
    private val store = SessionStore()
    private val probe = AVPlayerProbe(store)

    /**
     * The live session store. `internal` so same-module consumers (the Phase 4 iOS overlay and
     * the Phase 3 PoC test harness) can observe diagnostics; not part of the public Swift surface
     * until the presenter/SKIE bridge lands in Phase 4/5.
     */
    internal val sessionStore: SessionStore get() = store

    /**
     * Attaches StreamProbe to [player]. Scoped to the player lifecycle; no UIKit involvement.
     */
    fun attach(player: AVPlayer) {
        store.clear()
        probe.attach(player)
    }

    actual fun detach() {
        probe.detach()
        store.clear()
    }
}

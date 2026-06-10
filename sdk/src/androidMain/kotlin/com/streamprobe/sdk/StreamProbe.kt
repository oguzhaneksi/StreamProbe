package com.streamprobe.sdk

import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.streamprobe.sdk.internal.NetworkTimingRegistry
import com.streamprobe.sdk.internal.PlayerInterceptor
import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.internal.TimingDataSourceFactory
import com.streamprobe.sdk.internal.overlay.OverlayManager

/**
 * Entry point for the StreamProbe debug SDK.
 *
 * The player-scoped session and the Activity-scoped overlay have independent lifecycles:
 *
 * ```kotlin
 * // In the player owner (e.g. ViewModel):
 * val probe = StreamProbe()
 * probe.attach(player)
 *
 * // In each Activity onCreate (handles rotation + recreation automatically):
 * probe.show(this)
 *
 * // When the player is released:
 * probe.detach()
 * ```
 */
@androidx.annotation.OptIn(UnstableApi::class)
actual class StreamProbe {
    private val sessionStore = SessionStore()
    private val networkTimingRegistry = NetworkTimingRegistry()
    private val playerInterceptor = PlayerInterceptor(sessionStore, networkTimingRegistry)
    private val overlayManager = OverlayManager(sessionStore)

    private var attachedPlayer: ExoPlayer? = null

    /**
     * Attaches StreamProbe to [player]. Call this when the player is created.
     * This is scoped to the player lifecycle and does not require an Activity.
     */
    fun attach(player: ExoPlayer) {
        if (attachedPlayer != null) detach()
        attachedPlayer = player
        playerInterceptor.attach(player)
    }

    /**
     * Shows the debug overlay on [activity]. The overlay auto-hides when the Activity is
     * destroyed (via lifecycle observer), so no manual [hide] call is needed for the normal
     * Activity lifecycle. Call this from each Activity `onCreate` — it is safe to call on
     * every Activity recreation (config change).
     */
    fun show(activity: ComponentActivity) {
        overlayManager.show(activity)
    }

    /**
     * Removes the debug overlay. Safe to call when nothing is shown.
     */
    fun hide() {
        overlayManager.hide()
    }

    /**
     * Wraps a [DataSource.Factory] to measure open()-duration as a best-effort TTFB estimate.
     *
     * Pass the returned factory into `DefaultMediaSourceFactory.setDataSourceFactory`.
     * This must be the **outermost** wrapper — place it around any debug/error-injection
     * factories so that exceptions thrown by inner factories do not record a false TTFB.
     *
     * TTFB values are estimates: on cold connections they include connection-setup time;
     * on warm keep-alive connections they closely approximate pure server TTFB.
     *
     * In release-bound hosts gate this behind `BuildConfig.DEBUG`.
     */
    fun wrapDataSourceFactory(factory: DataSource.Factory): DataSource.Factory = TimingDataSourceFactory(factory, networkTimingRegistry)

    /**
     * Detaches StreamProbe from the player and removes the debug overlay.
     */
    actual fun detach() {
        playerInterceptor.detach()
        hide()
        sessionStore.clear()
        networkTimingRegistry.clear()
        attachedPlayer = null
    }
}

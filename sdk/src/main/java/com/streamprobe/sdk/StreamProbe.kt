package com.streamprobe.sdk

import android.app.Activity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.streamprobe.sdk.internal.PlayerInterceptor
import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.internal.overlay.OverlayManager

/**
 * Entry point for the StreamProbe debug SDK.
 *
 * Usage:
 * ```
 * val probe = StreamProbe()
 * probe.attach(player, activity)
 * // …
 * probe.detach()
 * ```
 */
@androidx.annotation.OptIn(UnstableApi::class)
class StreamProbe {

    private val sessionStore = SessionStore()


    private val playerInterceptor = PlayerInterceptor(sessionStore)

    private val overlayManager = OverlayManager(sessionStore)

    private var attachedPlayer: ExoPlayer? = null

    /**
     * Attaches StreamProbe to the given [player] and shows the debug overlay
     * on the provided [activity].
     *
     * Call [detach] to clean up when the player is being released or the
     * activity is finishing.
     */
    fun attach(player: ExoPlayer, activity: Activity) {
        if (attachedPlayer != null) detach()

        attachedPlayer = player
        playerInterceptor.attach(player)
        overlayManager.show(activity)
    }

    /**
     * Detaches StreamProbe from the player and removes the debug overlay.
     */
    fun detach() {
        playerInterceptor.detach()
        overlayManager.hide()
        sessionStore.clear()
        attachedPlayer = null
    }
}
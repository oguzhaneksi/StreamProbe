package com.streamprobe.sdk

/**
 * Entry point for the StreamProbe debug SDK.
 *
 * Each platform `actual` adds its own player-attach surface (Android: `attach(ExoPlayer)` + overlay
 * `show`/`hide` + `wrapDataSourceFactory`; iOS: `attach(AVPlayer)`, headless until the Phase 4 overlay).
 * The only member common to every platform is session teardown.
 */
expect class StreamProbe() {
    /**
     * Detaches from the player, tears down any overlay, and clears the in-memory session.
     */
    fun detach()
}

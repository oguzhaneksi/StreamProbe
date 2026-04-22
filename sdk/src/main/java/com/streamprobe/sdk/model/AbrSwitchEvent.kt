package com.streamprobe.sdk.model

/**
 * A single ABR quality switch event recorded during playback.
 */
data class AbrSwitchEvent(
    /** Wall-clock timestamp of the switch (System.currentTimeMillis). */
    val timestampMs: Long,
    /** Track that was active before the switch, or null if this is the initial selection. */
    val previousTrack: ActiveTrackInfo?,
    /** Track that is now active. */
    val newTrack: ActiveTrackInfo,
    /** Total buffered duration at the instant of the switch (ms). */
    val bufferDurationMs: Long,
    /** The reason Media3 reported for this track selection. */
    val reason: SwitchReason,
)

package com.streamprobe.sdk.model

/**
 * Describes the track currently selected by the player for playback.
 */
data class ActiveTrackInfo(
    /** Bitrate in bits per second. */
    val bitrate: Int,
    /** Video width in pixels, or -1 if unknown. */
    val width: Int,
    /** Video height in pixels, or -1 if unknown. */
    val height: Int,
    /** Codec string, or null if unavailable. */
    val codecs: String?,
)

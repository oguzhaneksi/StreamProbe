package com.streamprobe.sdk.model

/**
 * Describes the video track currently being decoded for playback. Sourced from the decoder,
 * not from the track group — deliberately distinct from [VariantInfo].
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
    /** Media3 Format id; used for reliable active-track matching. Null if unavailable. */
    val id: String? = null,
)

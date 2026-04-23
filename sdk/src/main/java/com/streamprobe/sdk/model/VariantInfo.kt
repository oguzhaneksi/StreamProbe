package com.streamprobe.sdk.model

/**
 * SDK-owned representation of a single stream variant (HLS variant or DASH Representation).
 * Maps from Media3's [androidx.media3.common.Format] without leaking any Media3 types.
 */
data class VariantInfo(
    /** Declared bitrate in bits per second. */
    val bitrate: Int,
    /** Video width in pixels, or -1 if unknown. */
    val width: Int,
    /** Video height in pixels, or -1 if unknown. */
    val height: Int,
    /** Codec string, e.g. "avc1.42e00a,mp4a.40.2". */
    val codecs: String?,
    /** Frame rate in fps, or -1f if unknown. */
    val frameRate: Float,
)

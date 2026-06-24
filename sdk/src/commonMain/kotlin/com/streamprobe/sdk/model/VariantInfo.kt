package com.streamprobe.sdk.model

/**
 * SDK-owned representation of a single stream variant (HLS variant or DASH Representation).
 * Maps from Media3's [androidx.media3.common.Format] without leaking any Media3 types.
 *
 * The nullable [id] is preferred over width/height dimension matching for DiffUtil identity.
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
    /** Player/Format-provided track identifier used for reliable active-track matching. Null if unavailable. */
    val id: String? = null,
    /**
     * True if this variant is currently selected by the player. Sourced directly from
     * `Tracks.Group.isTrackSelected(i)` (Android) — not a secondary active-track comparison.
     */
    val isSelected: Boolean = false,
)

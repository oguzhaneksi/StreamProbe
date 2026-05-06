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
    /** Format id from the manifest; used for reliable active-track matching. Null if unavailable. */
    val id: String? = null,
    /** True if this variant is currently selected by the player. Set from player.currentTracks. */
    val isSelected: Boolean = false,
)

/**
 * Returns true if this manifest variant corresponds to the given [active] player track.
 *
 * Matching strategy (in priority order):
 * 1. **id** — when both sides carry a non-null id (reliable for DASH; HLS variant URI).
 * 2. **resolution** — `(width, height)` when both are positive. Bitrate is intentionally
 *    excluded: the manifest's declared `BANDWIDTH` and the player-reported bitrate can diverge
 *    (peak vs. actual; muxed vs. video-only accounting). Codecs are also excluded because the
 *    manifest encodes a combined video+audio string while the player exposes a video-only value.
 * 3. **bitrate** — last resort when dimensions are unavailable (e.g. audio-only fallback).
 */
internal fun VariantInfo.isSameRenditionAs(active: ActiveTrackInfo): Boolean =
    if (id != null && active.id != null) {
        id == active.id
    } else if (width > 0 && height > 0) {
        width == active.width && height == active.height
    } else {
        bitrate == active.bitrate
    }

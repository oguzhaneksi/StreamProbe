package com.streamprobe.sdk.model

/**
 * Protocol-agnostic snapshot of all available tracks as reported by the player.
 * Data is sourced from [androidx.media3.common.Tracks] (not from HLS/DASH manifest parsing).
 */
sealed interface TrackListInfo {
    /** All video renditions found in the current track groups. */
    val variants: List<VariantInfo>

    /** All audio renditions found in the current track groups. */
    val audioTracks: List<AudioTrackInfo>

    /** All subtitle / closed-caption renditions found in the current track groups. */
    val subtitleTracks: List<SubtitleTrackInfo>
}

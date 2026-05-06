package com.streamprobe.sdk.model

/**
 * Concrete implementation of [TrackListInfo] built from the player's
 * [androidx.media3.common.Tracks] API. Protocol-agnostic: covers both HLS and DASH streams.
 */
data class TracksSnapshot(
    override val variants: List<VariantInfo>,
    override val audioTracks: List<AudioTrackInfo> = emptyList(),
    override val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
) : TrackListInfo

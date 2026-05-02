package com.streamprobe.sdk.model

/**
 * Parsed representation of an HLS multivariant (master) playlist.
 */
data class HlsManifestInfo(
    override val variants: List<VariantInfo>,
    override val audioTracks: List<AudioTrackInfo> = emptyList(),
    override val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
) : ManifestInfo

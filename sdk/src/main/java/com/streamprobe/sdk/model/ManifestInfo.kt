package com.streamprobe.sdk.model

/**
 * Protocol-agnostic manifest information.
 * Both HLS and DASH manifests are normalized into common lists.
 */
sealed interface ManifestInfo {
    /** All variant streams / representations found in the manifest. */
    val variants: List<VariantInfo>
    /** All audio renditions found in the manifest. */
    val audioTracks: List<AudioTrackInfo>
    /** All subtitle / closed-caption renditions found in the manifest. */
    val subtitleTracks: List<SubtitleTrackInfo>
}

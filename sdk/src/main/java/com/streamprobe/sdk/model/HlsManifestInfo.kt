package com.streamprobe.sdk.model

/**
 * Parsed representation of an HLS multivariant (master) playlist.
 */
data class HlsManifestInfo(
    /** All variant streams declared in the master playlist. */
    val variants: List<VariantInfo>,
)

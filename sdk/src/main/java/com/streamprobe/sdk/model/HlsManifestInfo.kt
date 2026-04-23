package com.streamprobe.sdk.model

/**
 * Parsed representation of an HLS multivariant (master) playlist.
 */
data class HlsManifestInfo(
    override val variants: List<VariantInfo>,
) : ManifestInfo

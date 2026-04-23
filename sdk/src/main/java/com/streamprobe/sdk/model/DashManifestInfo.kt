package com.streamprobe.sdk.model

/**
 * Parsed representation of a DASH MPD (Media Presentation Description).
 * Video Representations across all Periods and AdaptationSets are
 * flattened into a single [variants] list for display in the overlay.
 */
data class DashManifestInfo(
    override val variants: List<VariantInfo>,
) : ManifestInfo

package com.streamprobe.sdk.model

/**
 * Protocol-agnostic manifest information.
 * Both HLS and DASH manifests are normalized into a common [variants] list.
 */
sealed interface ManifestInfo {
    /** All variant streams / representations found in the manifest. */
    val variants: List<VariantInfo>
}

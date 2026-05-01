package com.streamprobe.sdk.model

/**
 * Origin of a subtitle / closed-caption rendition.
 */
enum class SubtitleKind {
    /** Sidecar WebVTT/TTML rendition declared in the manifest. */
    SIDECAR,
    /** CEA-608/708 closed caption explicitly declared at manifest level. */
    CC_DECLARED,
    /** Closed caption embedded inside a video variant (HLS muxedCaptionFormats). */
    CC_MUXED,
}

/**
 * A single subtitle or closed-caption rendition from the manifest,
 * or the currently active subtitle track.
 */
data class SubtitleTrackInfo(
    /** BCP 47 language tag. Null if unspecified. */
    val language: String?,
    /** Human-readable display label from the manifest. Null if absent. */
    val label: String?,
    val mimeType: String?,
    val kind: SubtitleKind,
)

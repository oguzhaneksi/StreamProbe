package com.streamprobe.sdk.model

/**
 * Origin of a subtitle / closed-caption rendition.
 */
enum class SubtitleKind {
    /** Sidecar WebVTT/TTML rendition declared in the manifest. */
    SIDECAR,

    /** CEA-608/708 closed caption (declared in manifest or muxed in a video variant). */
    CC,
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
    /** Format id from the manifest; used for reliable active-track matching. Null if unavailable. */
    val id: String? = null,
)

/** Returns true if [other] refers to the same subtitle rendition. Prefers [id] when both are non-null. */
internal fun SubtitleTrackInfo.isSameRenditionAs(other: SubtitleTrackInfo): Boolean =
    if (id != null && other.id != null) {
        id == other.id
    } else {
        language == other.language &&
            mimeType == other.mimeType &&
            kind == other.kind &&
            (label == null || other.label == null || label == other.label)
    }

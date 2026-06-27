package com.streamprobe.sdk.model

/**
 * Media kind of a captured segment.
 *
 * Android derives this from Media3's `MediaLoadData.trackType`; iOS currently leaves it
 * [UNKNOWN] because neither the access-log roll-up nor iOS 18 AVMetrics expose a reliable
 * per-segment media type. The overlay badge is hidden for [UNKNOWN], so an unclassified
 * segment renders exactly as it did before this field existed.
 */
enum class SegmentTrackType {
    VIDEO,
    AUDIO,
    TEXT,
    UNKNOWN,
}

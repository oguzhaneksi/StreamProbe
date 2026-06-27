package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SegmentTrackType

/**
 * Pure formatting for the segment timeline rows and the latest-segment stat line.
 * Split out of the former monolithic `OverlayFormatters` so each formatter object stays
 * cohesive and under the detekt `TooManyFunctions` threshold.
 */
internal object SegmentFormatters {
    /** Upper bound on a plausible file extension length; longer strings are treated as "no extension". */
    private const val MAX_EXT_LEN = 5

    /**
     * Full track-type word for the plain (uncolored) latest-segment stat line, or null for UNKNOWN.
     * Single source of truth for the V/A/T text — [segmentTrackBadge] derives its letter from this.
     */
    fun segmentTrackLabel(trackType: SegmentTrackType): String? =
        when (trackType) {
            SegmentTrackType.VIDEO -> "VIDEO"
            SegmentTrackType.AUDIO -> "AUDIO"
            SegmentTrackType.TEXT -> "TEXT"
            SegmentTrackType.UNKNOWN -> null
        }

    /**
     * Single-letter badge for a segment's track type (the first letter of [segmentTrackLabel]),
     * or null when it should not be shown (UNKNOWN). The colored pill carries the meaning visually.
     */
    fun segmentTrackBadge(trackType: SegmentTrackType): String? = segmentTrackLabel(trackType)?.take(1)

    /**
     * Query-string-safe file extension from a segment URI, or null when there is none.
     * Strips `?query` and `#fragment`, reads the last path segment, and rejects
     * extensions longer than [MAX_EXT_LEN] so a stray dot in a path can't yield a giant label.
     */
    fun segmentExtension(uri: String): String? {
        val path = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (!path.contains('.')) return null
        val ext = path.substringAfterLast('.')
        return ext.takeIf { it.isNotBlank() && it.length <= MAX_EXT_LEN }
    }

    fun formatSegmentMetric(metric: SegmentMetric?): String {
        if (metric == null) return "—"
        // Track-type word (uncolored stat line) + extension; both omitted when absent so the
        // line degrades to "DL: 200ms" exactly as before this field existed.
        val tags = listOfNotNull(segmentTrackLabel(metric.trackType), segmentExtension(metric.uri))
        val firstLine =
            buildString {
                append("DL: ${metric.totalDurationMs}ms")
                if (tags.isNotEmpty()) append("  ·  ${tags.joinToString("  ·  ")}")
            }
        return "$firstLine\n${formatSegmentDetails(metric)}"
    }

    fun formatSegmentDetails(metric: SegmentMetric): String =
        buildList {
            add("Size: ${ByteFormatters.formatBytes(metric.sizeBytes)}")
            add("TP: ${ByteFormatters.formatThroughput(metric.throughputBytesPerSec)}")
            metric.networkTiming?.let { add("TTFB: ${TimeFormatters.formatTtfb(it)}") }
        }.joinToString("  ·  ")
}

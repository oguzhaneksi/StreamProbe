package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.SegmentMetric
import java.util.Locale

/**
 * Pure formatting functions for segment and CDN data displayed in the overlay.
 * These are extracted from [OverlayManager] so they can be unit-tested without Robolectric.
 */
internal object OverlayFormatters {

    fun formatSegmentMetric(metric: SegmentMetric?): String {
        if (metric == null) return "\u2014"
        val duration = "Total: ${metric.totalDurationMs}ms"
        val size = formatBytes(metric.sizeBytes)
        val throughput = formatThroughput(metric.throughputBytesPerSec)
        return "$duration  \u00b7  Size: $size  \u00b7  TP: $throughput"
    }

    fun formatCdnStatus(cdnInfo: CdnHeaderInfo?): String {
        if (cdnInfo == null) return "\u2014"
        val indicator = when (cdnInfo.cacheStatus) {
            CacheStatus.HIT -> "\u25cf HIT"
            CacheStatus.MISS -> "\u25cb MISS"
            CacheStatus.UNKNOWN -> "\u25cc UNKNOWN"
        }
        val headerSnippet = when {
            cdnInfo.xCache != null -> "X-Cache: ${cdnInfo.xCache}"
            cdnInfo.cdnSpecificHeaders.isNotEmpty() -> {
                val (k, v) = cdnInfo.cdnSpecificHeaders.entries.first()
                "${k.uppercase(Locale.ROOT)}: $v"
            }
            cdnInfo.cacheControl != null -> "Cache-Control: ${cdnInfo.cacheControl}"
            else -> null
        }
        return if (headerSnippet != null) "$indicator  \u00b7  $headerSnippet" else indicator
    }

    fun buildSegmentTimeline(metrics: List<SegmentMetric>): String {
        if (metrics.isEmpty()) return ""
        val sb = StringBuilder()
        metrics.forEachIndexed { index, m ->
            sb.append("#${index + 1}  ")
            sb.append("${m.totalDurationMs}ms  ")
            sb.append(formatBytes(m.sizeBytes))
            sb.append("  ")
            sb.append(formatThroughput(m.throughputBytesPerSec))
            sb.append("  ")
            sb.appendLine(m.cdnInfo.cacheStatus.name)
        }
        return sb.toString().trimEnd()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun formatThroughput(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> String.format(Locale.ROOT, "%.1f KB/s", bytesPerSec / 1_000.0)
        else -> "$bytesPerSec B/s"
    }
}

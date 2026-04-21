package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ActiveTrackInfo
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

    private fun formatScaledBytes(value: Long, suffix: String = ""): String = when {
        value >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB%s", value / 1_000_000.0, suffix)
        value >= 1_000 -> String.format(Locale.ROOT, "%.1f KB%s", value / 1_000.0, suffix)
        else -> "$value B$suffix"
    }

    fun formatBytes(bytes: Long): String = formatScaledBytes(bytes)

    fun formatThroughput(bytesPerSec: Long): String = formatScaledBytes(bytesPerSec, "/s")

    fun formatBitrate(bps: Int): String = when {
        bps >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(Locale.ROOT, "%d kbps", bps / 1_000)
        bps > 0 -> "$bps bps"
        else -> "? bps"
    }

    fun formatResolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "${width}\u00d7${height}" else "Audio only"

    fun formatActiveTrack(track: ActiveTrackInfo?): String {
        if (track == null) return "Loading\u2026"
        return "${formatResolution(track.width, track.height)}  \u00b7  ${formatBitrate(track.bitrate)}"
    }
}

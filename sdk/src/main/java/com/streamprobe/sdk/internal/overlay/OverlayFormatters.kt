package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.CdnProvider
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SwitchReason
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
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
        val providerPrefix = when (cdnInfo.cdnProvider) {
            CdnProvider.CLOUDFLARE -> "[CLOUDFLARE]"
            CdnProvider.CLOUDFRONT -> "[CLOUDFRONT]"
            CdnProvider.FASTLY -> "[FASTLY]"
            CdnProvider.AKAMAI -> "[AKAMAI]"
            CdnProvider.UNKNOWN, null -> null
        }
        val indicator = when (cdnInfo.cacheStatus) {
            CacheStatus.HIT -> "\u25cf HIT"
            CacheStatus.MISS -> "\u25cb MISS"
            CacheStatus.STALE -> "\u25d4 STALE"
            CacheStatus.BYPASS -> "\u25a1 BYPASS"
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
        val statusPart = if (headerSnippet != null) "$indicator  \u00b7  $headerSnippet" else indicator
        return if (providerPrefix != null) "$providerPrefix  $statusPart" else statusPart
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

    /** "720p → 1080p" or "1.5 Mbps → 5.0 Mbps" when resolution is identical. */
    fun formatAbrSwitch(from: ActiveTrackInfo?, to: ActiveTrackInfo): String {
        val toLabel = if (to.height > 0) "${to.height}p" else formatBitrate(to.bitrate)
        if (from == null) return "\u2014 \u2192 $toLabel"
        val fromLabel = if (from.height > 0) "${from.height}p" else formatBitrate(from.bitrate)
        return if (fromLabel == toLabel) {
            "${formatBitrate(from.bitrate)} \u2192 ${formatBitrate(to.bitrate)}"
        } else {
            "$fromLabel \u2192 $toLabel"
        }
    }

    /** "buf: 12.4s" */
    fun formatBufferDuration(bufferMs: Long): String =
        String.format(Locale.ROOT, "buf: %.1fs", bufferMs / 1000.0)

    /** "+0:42" relative to a base timestamp. */
    fun formatRelativeTimestamp(timestampMs: Long, baseTimestampMs: Long): String {
        val diffMs = (timestampMs - baseTimestampMs).coerceAtLeast(0L)
        val totalSec = diffMs / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.ROOT, "+%d:%02d", minutes, seconds)
    }

    /** "ADAPTIVE", "MANUAL", etc. */
    fun formatSwitchReason(reason: SwitchReason): String = reason.name

    fun formatErrorCategory(category: ErrorCategory): String = when (category) {
        ErrorCategory.LOAD_ERROR        -> "LOAD"
        ErrorCategory.VIDEO_CODEC_ERROR -> "CODEC"
        ErrorCategory.DROPPED_FRAMES    -> "FRAMES"
        ErrorCategory.AUDIO_SINK_ERROR  -> "AUDIO"
        ErrorCategory.AUDIO_CODEC_ERROR -> "ACODEC"
    }

    private val absoluteTimestampFormatter = ThreadLocal.withInitial<SimpleDateFormat> {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val dateTimeFormatter: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }

    /** "HH:mm:ss.SSS" absolute wall-clock timestamp. */
    fun formatAbsoluteTimestamp(timestampMs: Long): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dateTimeFormatter.format(Instant.ofEpochMilli(timestampMs))
        } else {
            absoluteTimestampFormatter.get().format(Date(timestampMs))
        }
    }

    fun formatErrorsForExport(
        errors: List<PlaybackErrorEvent>,
        baseTimestampMs: Long,
    ): String {
        val header = "[StreamProbe] ${errors.size} errors"
        val rows = errors.mapIndexed { i, e ->
            val rel = formatRelativeTimestamp(e.timestampMs, baseTimestampMs)
            val cat = formatErrorCategory(e.category)
            val abs = formatAbsoluteTimestamp(e.timestampMs)
            buildString {
                append("#${i + 1} $rel $cat ${e.message} [$abs]")
                if (!e.detail.isNullOrBlank()) {
                    append("\n    ${e.detail}")
                }
            }
        }
        return (listOf(header) + rows).joinToString("\n")
    }
}

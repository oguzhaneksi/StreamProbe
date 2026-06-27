package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.NetworkTiming
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SegmentTrackType
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.util.oneDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Pure formatting functions for segment and track data displayed in the overlay.
 * These are extracted from [OverlayManager] so they can be unit-tested without Robolectric.
 * CDN/cache-status formatting lives in [CdnFormatters].
 */
internal object OverlayFormatters {
    /** Upper bound on a plausible file extension length; longer strings are treated as "no extension". */
    private const val MAX_EXT_LEN = 5

    /** Single-letter badge for a segment's track type, or null when it should not be shown (UNKNOWN). */
    fun segmentTrackBadge(trackType: SegmentTrackType): String? =
        when (trackType) {
            SegmentTrackType.VIDEO -> "V"
            SegmentTrackType.AUDIO -> "A"
            SegmentTrackType.TEXT -> "T"
            SegmentTrackType.UNKNOWN -> null
        }

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
        // Full track-type word (the single-letter [segmentTrackBadge] relies on color to be legible,
        // but this stat line is plain uncolored text); null for UNKNOWN so the tag is omitted.
        val trackLabel =
            when (metric.trackType) {
                SegmentTrackType.VIDEO -> "VIDEO"
                SegmentTrackType.AUDIO -> "AUDIO"
                SegmentTrackType.TEXT -> "TEXT"
                SegmentTrackType.UNKNOWN -> null
            }
        val tags = listOfNotNull(trackLabel, segmentExtension(metric.uri))
        val firstLine =
            buildString {
                append("DL: ${metric.totalDurationMs}ms")
                if (tags.isNotEmpty()) append("  ·  ${tags.joinToString("  ·  ")}")
            }
        return "$firstLine\n${formatSegmentDetails(metric)}"
    }

    fun formatSegmentDetails(metric: SegmentMetric): String =
        buildList {
            add("Size: ${formatBytes(metric.sizeBytes)}")
            add("TP: ${formatThroughput(metric.throughputBytesPerSec)}")
            metric.networkTiming?.let { add("TTFB: ${formatTtfb(it)}") }
        }.joinToString("  ·  ")

    fun formatBytes(
        value: Long,
        suffix: String = "",
    ): String =
        when {
            value >= 1_000_000 -> "${oneDecimal(value / 1_000_000.0)} MB$suffix"
            value >= 1_000 -> "${oneDecimal(value / 1_000.0)} KB$suffix"
            else -> "$value B$suffix"
        }

    fun formatThroughput(bytesPerSec: Long): String = formatBytes(bytesPerSec, "/s")

    fun formatBitrate(bps: Int): String =
        when {
            bps >= 1_000_000 -> "${oneDecimal(bps / 1_000_000.0)} Mbps"
            bps >= 1_000 -> "${bps / 1_000} kbps"
            bps > 0 -> "$bps bps"
            else -> "? bps"
        }

    fun formatResolution(
        width: Int,
        height: Int,
    ): String = if (width > 0 && height > 0) "$width×$height" else "Audio only"

    fun formatActiveTrack(track: ActiveTrackInfo?): String {
        if (track == null) return "Loading…"
        return "${formatResolution(track.width, track.height)}  ·  ${formatBitrate(track.bitrate)}"
    }

    fun formatActiveAudio(audio: AudioTrackInfo?): String {
        if (audio == null) return "Loading…"
        val parts = mutableListOf<String>()
        val lang =
            audio.label
                ?: audio.language?.let { displayLanguage(it) }
        if (!lang.isNullOrBlank()) parts += lang
        val codec =
            audio.codecs
                ?.split(".")
                ?.firstOrNull()
                ?.uppercase()
        val channels =
            when (audio.channelCount) {
                1 -> "mono"
                2 -> "stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> if (audio.channelCount > 0) "${audio.channelCount}ch" else null
            }
        listOfNotNull(codec, channels)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
            ?.let { parts += it }
        if (audio.bitrate > 0) parts += formatBitrate(audio.bitrate)
        if (audio.sampleRate > 0) parts += "${oneDecimal(audio.sampleRate / 1000.0)} kHz"
        return parts.joinToString("  ·  ").ifBlank { "Unknown" }
    }

    fun formatActiveSubtitle(subtitle: SubtitleTrackInfo?): String {
        if (subtitle == null) return "Off"
        val parts = mutableListOf<String>()
        val lang =
            subtitle.label
                ?: subtitle.language?.let { displayLanguage(it) }
        if (!lang.isNullOrBlank()) parts += lang
        if (subtitle.kind == SubtitleKind.CC) parts += "(CC)"
        val mimeShort =
            when (subtitle.mimeType) {
                "text/vtt", "application/x-media3-webvtt" -> "WebVTT"
                "application/ttml+xml" -> "TTML"
                "application/x-subrip" -> "SRT"
                "text/x-ssa" -> "SSA"
                "application/cea-608", "application/cea-708" -> null // implied by (CC)
                else -> subtitle.mimeType?.substringAfterLast("/")
            }
        if (!mimeShort.isNullOrBlank()) parts += mimeShort
        return parts.joinToString("  ·  ").ifBlank { "Unknown" }
    }

    /** "720p → 1080p" or "1.5 Mbps → 5.0 Mbps" when resolution is identical. */
    fun formatAbrSwitch(
        from: ActiveTrackInfo?,
        to: ActiveTrackInfo,
    ): String {
        val toLabel = if (to.height > 0) "${to.height}p" else formatBitrate(to.bitrate)
        if (from == null) return "— → $toLabel"
        val fromLabel = if (from.height > 0) "${from.height}p" else formatBitrate(from.bitrate)
        return if (fromLabel == toLabel) {
            "${formatBitrate(from.bitrate)} → ${formatBitrate(to.bitrate)}"
        } else {
            "$fromLabel → $toLabel"
        }
    }

    /** "buf: 12.4s" */
    fun formatBufferDuration(bufferMs: Long): String = "buf: ${oneDecimal(bufferMs / 1000.0)}s"

    /** "+0:42" relative to a base timestamp. */
    fun formatRelativeTimestamp(
        timestampMs: Long,
        baseTimestampMs: Long,
    ): String {
        val diffMs = (timestampMs - baseTimestampMs).coerceAtLeast(0L)
        val totalSec = diffMs / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "+$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** "ADAPTIVE", "MANUAL", etc. */
    fun formatSwitchReason(reason: SwitchReason): String = reason.name

    fun formatErrorCategory(category: ErrorCategory): String =
        when (category) {
            ErrorCategory.LOAD_ERROR -> "LOAD"
            ErrorCategory.VIDEO_CODEC_ERROR -> "CODEC"
            ErrorCategory.DROPPED_FRAMES -> "FRAMES"
            ErrorCategory.AUDIO_SINK_ERROR -> "AUDIO"
            ErrorCategory.AUDIO_CODEC_ERROR -> "ACODEC"
            ErrorCategory.DRM_ERROR -> "DRM"
        }

    // "HH:mm:ss.SSS" — the kotlinx-datetime equivalent of SimpleDateFormat("HH:mm:ss.SSS").
    private val TIME_FORMAT =
        LocalDateTime.Format {
            hour()
            char(':')
            minute()
            char(':')
            second()
            char('.')
            secondFraction(3)
        }

    /** "HH:mm:ss.SSS" absolute wall-clock timestamp in the system-default time zone. */
    fun formatAbsoluteTimestamp(timestampMs: Long): String {
        val local = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return TIME_FORMAT.format(local)
    }

    fun formatTtfb(networkTiming: NetworkTiming?): String {
        if (networkTiming == null) return "—"
        return "${if (networkTiming.isEstimated) "~" else ""}${networkTiming.ttfbMs}ms"
    }

    fun formatErrorsForExport(
        errors: List<PlaybackErrorEvent>,
        baseTimestampMs: Long,
    ): String {
        val header = "[StreamProbe] ${errors.size} errors"
        val rows =
            errors.mapIndexed { i, e ->
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

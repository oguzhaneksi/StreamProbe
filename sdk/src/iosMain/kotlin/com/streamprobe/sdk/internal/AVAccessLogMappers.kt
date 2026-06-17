package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric

/*
 * Maps AVFoundation access-log / error-log events to the SDK's coarse segment / switch / error
 * models. iOS exposes only roll-up access-log entries — no per-segment TTFB and no response headers
 * — so `SegmentMetric.networkTiming` is null and `CdnHeaderInfo` degrades to UNKNOWN via an
 * empty-header parse (`CdnHeaderParser.parse(emptyMap())`), and the `serverAddress` (a CDN IP, not a
 * header) has no home in the model. Throughput prefers AVFoundation's `observedBitrate / 8` (else
 * size/duration); `SegmentMetric.requestTimestampMs` comes from
 * `event.playbackStartDate?.timeIntervalSince1970` (falling back to `nowMs()`). The numeric/string
 * conversions are pure top-level helpers, unit-tested without live AVFoundation events.
 */

private const val BITS_PER_BYTE = 8
private const val UNKNOWN_URI = "(unknown)"
private val UNKNOWN_CDN_INFO = CdnHeaderParser.parse(emptyMap())

/** Converts a duration in seconds to whole milliseconds; 0 for the AVFoundation -1 "unknown" sentinel. */
internal fun secondsToMillis(seconds: Double): Long = if (seconds > 0) (seconds * MILLIS_PER_SECOND).toLong() else 0

/** Bytes-per-second throughput: prefer AVFoundation's observed bitrate, else size/duration, else 0. */
internal fun segmentThroughput(
    observedBitrate: Double,
    sizeBytes: Long,
    durationMs: Long,
): Long =
    when {
        observedBitrate > 0 -> (observedBitrate / BITS_PER_BYTE).toLong()
        sizeBytes > 0 && durationMs > 0 -> sizeBytes * MILLIS_PER_SECOND / durationMs
        else -> 0
    }

/** Builds a coarse [SegmentMetric] from one access-log entry (sentinels -> 0 / null / UNKNOWN). */
internal fun accessLogSegmentMetric(
    nowMs: Long,
    uri: String?,
    sizeBytes: Long,
    observedBitrate: Double,
    transferDurationSeconds: Double,
): SegmentMetric {
    val size = sizeBytes.coerceAtLeast(0)
    val durationMs = secondsToMillis(transferDurationSeconds)
    return SegmentMetric(
        requestTimestampMs = nowMs,
        totalDurationMs = durationMs,
        sizeBytes = size,
        throughputBytesPerSec = segmentThroughput(observedBitrate, size, durationMs),
        uri = uri?.takeIf { it.isNotBlank() } ?: UNKNOWN_URI,
        cdnInfo = UNKNOWN_CDN_INFO,
        networkTiming = null,
    )
}

/** A degraded [ActiveTrackInfo] carrying only the indicated bitrate (access log has no resolution/codecs). */
internal fun activeTrackFromIndicatedBitrate(indicatedBitrate: Double): ActiveTrackInfo =
    ActiveTrackInfo(
        bitrate = if (indicatedBitrate > 0) indicatedBitrate.toInt() else 0,
        width = -1,
        height = -1,
        codecs = null,
        id = null,
    )

/** True when the indicated bitrate changed to a new valid value — i.e. an adaptive variant switch. */
internal fun isBitrateSwitch(
    previousIndicated: Double,
    currentIndicated: Double,
): Boolean = currentIndicated > 0 && currentIndicated != previousIndicated

/** A DROPPED_FRAMES error for one access-log entry; the store merges bursts within its 5s window. */
internal fun droppedFramesError(
    nowMs: Long,
    droppedFrames: Long,
): PlaybackErrorEvent =
    PlaybackErrorEvent(
        timestampMs = nowMs,
        category = ErrorCategory.DROPPED_FRAMES,
        message = "$droppedFrames dropped frames",
        categoryDetail =
            ErrorDetail.DroppedFrames(
                totalFrames = droppedFrames.toInt(),
                burstCount = 1,
                lastUpdateMs = nowMs,
            ),
    )

/** A LOAD_ERROR from one error-log entry. */
internal fun loadError(
    nowMs: Long,
    errorDomain: String?,
    statusCode: Long,
    uri: String?,
    comment: String?,
): PlaybackErrorEvent =
    PlaybackErrorEvent(
        timestampMs = nowMs,
        category = ErrorCategory.LOAD_ERROR,
        message = loadErrorMessage(errorDomain, statusCode, uri),
        detail = comment,
    )

/** Formats an error-log entry as "Domain code: lastPathSegment" (mirrors the Android one-liner). */
internal fun loadErrorMessage(
    errorDomain: String?,
    statusCode: Long,
    uri: String?,
): String {
    val domain = errorDomain?.takeIf { it.isNotBlank() } ?: "Error"
    val code = if (statusCode != 0L) " $statusCode" else ""
    val segment = uri?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    return if (segment != null) "$domain$code: $segment" else "$domain$code"
}

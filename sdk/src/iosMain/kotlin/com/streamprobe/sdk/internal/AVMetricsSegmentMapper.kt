package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.NetworkTiming
import com.streamprobe.sdk.model.SegmentMetric

/*
 * Maps iOS 18 AVMetrics per-segment data (AVMetricHLSMediaSegmentRequestEvent) to the common
 * SegmentMetric. Unlike the access-log path, these are true per-segment requests carrying real
 * timing and — when the response is available — CDN response headers, so both networkTiming and
 * cdnInfo can be filled (Android parity).
 *
 * Takes plain Kotlin parameters so it unit-tests without AVFoundation/AVMetrics types: the Swift
 * adapter (AVMetricsSegmentAdapter) extracts them from the value objects and calls this through the
 * generated AVMetricsSegmentMapperKt bridge. requestTimestampMs is the probe's monotonic nowMs()
 * (never the AVMetrics wall-clock Date); totalDurationMs and the networkTiming fields are Date
 * deltas computed Swift-side. CdnHeaderParser expects Map<String, List<String>>, so the
 * single-valued header map is wrapped one value per list.
 */

private const val UNKNOWN_URI = "(unknown)"

/** Builds a per-segment [SegmentMetric] from extracted iOS 18 AVMetrics values (size/duration sentinels -> 0). */
public fun avMetricsSegmentMetric(
    requestTimestampMs: Long,
    totalDurationMs: Long,
    sizeBytes: Long,
    uri: String,
    responseHeaders: Map<String, String>,
    networkTiming: NetworkTiming?,
): SegmentMetric {
    val size = sizeBytes.coerceAtLeast(0)
    val durationMs = totalDurationMs.coerceAtLeast(0)
    return SegmentMetric(
        requestTimestampMs = requestTimestampMs,
        totalDurationMs = durationMs,
        sizeBytes = size,
        throughputBytesPerSec = segmentThroughput(observedBitrate = 0.0, sizeBytes = size, durationMs = durationMs),
        uri = uri.takeIf { it.isNotBlank() } ?: UNKNOWN_URI,
        cdnInfo = CdnHeaderParser.parse(responseHeaders.mapValues { listOf(it.value) }),
        networkTiming = networkTiming,
    )
}

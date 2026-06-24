package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnProvider
import com.streamprobe.sdk.model.NetworkTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure iOS 18 AVMetrics per-segment mapper, exercised without live
 * AVFoundation/AVMetrics types (the Swift extraction + end-to-end path is device-verified).
 */
class AVMetricsSegmentMapperTest {
    private val timing =
        NetworkTiming(
            ttfbMs = 40,
            transferDurationMs = 120,
            dnsMs = 5,
            connectMs = 10,
            tlsMs = 15,
            isEstimated = false,
        )

    @Test
    fun avMetricsSegmentMetric_populatedHeaders_parsesCdnAndAttachesTiming() {
        val metric =
            avMetricsSegmentMetric(
                requestTimestampMs = 1_000,
                totalDurationMs = 160,
                sizeBytes = 480_000,
                uri = "https://cdn.example/v0/seg_5.ts",
                responseHeaders =
                    mapOf(
                        "Cache-Control" to "max-age=6",
                        "CF-Cache-Status" to "HIT",
                    ),
                networkTiming = timing,
            )
        assertEquals(1_000, metric.requestTimestampMs)
        assertEquals(160, metric.totalDurationMs)
        assertEquals(480_000, metric.sizeBytes)
        // 480_000 bytes / 160 ms => 3_000_000 B/s (size/duration path; no observed bitrate).
        assertEquals(3_000_000, metric.throughputBytesPerSec)
        assertEquals("https://cdn.example/v0/seg_5.ts", metric.uri)
        assertEquals(CacheStatus.HIT, metric.cdnInfo.cacheStatus)
        assertEquals(CdnProvider.CLOUDFLARE, metric.cdnInfo.cdnProvider)
        assertEquals("max-age=6", metric.cdnInfo.cacheControl)
        assertEquals(timing, metric.networkTiming)
        assertEquals(false, metric.networkTiming?.isEstimated)
    }

    @Test
    fun avMetricsSegmentMetric_failedTransaction_degradesHeadersAndTiming() {
        val metric =
            avMetricsSegmentMetric(
                requestTimestampMs = 2_000,
                totalDurationMs = 0,
                sizeBytes = -1,
                uri = "",
                responseHeaders = emptyMap(),
                networkTiming = null,
            )
        assertEquals(0, metric.sizeBytes)
        assertEquals(0, metric.totalDurationMs)
        assertEquals(0, metric.throughputBytesPerSec)
        assertEquals("(unknown)", metric.uri)
        assertEquals(CacheStatus.UNKNOWN, metric.cdnInfo.cacheStatus)
        assertEquals(CdnProvider.UNKNOWN, metric.cdnInfo.cdnProvider)
        assertNull(metric.networkTiming)
    }
}

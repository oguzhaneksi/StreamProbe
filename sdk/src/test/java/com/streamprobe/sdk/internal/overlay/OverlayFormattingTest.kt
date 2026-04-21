package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.SegmentMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFormattingTest {

    private fun makeCdnInfo(
        status: CacheStatus = CacheStatus.UNKNOWN,
        xCache: String? = null,
        cacheControl: String? = null,
        cdnSpecificHeaders: Map<String, String> = emptyMap(),
    ) = CdnHeaderInfo(
        cacheControl = cacheControl,
        xCache = xCache,
        via = null,
        cdnSpecificHeaders = cdnSpecificHeaders,
        cacheStatus = status,
    )

    private fun makeMetric(
        totalDurationMs: Long = 200L,
        sizeBytes: Long = 500_000L,
        throughputBytesPerSec: Long = 2_500_000L,
        cdnInfo: CdnHeaderInfo = makeCdnInfo(),
    ) = SegmentMetric(
        requestTimestampMs = 1_000L,
        totalDurationMs = totalDurationMs,
        sizeBytes = sizeBytes,
        throughputBytesPerSec = throughputBytesPerSec,
        uri = "https://example.com/seg.ts",
        cdnInfo = cdnInfo,
    )

    @Test
    fun `formatSegmentMetric with null returns placeholder`() {
        val result = OverlayFormatters.formatSegmentMetric(null)
        assertEquals("\u2014", result)
    }

    @Test
    fun `formatSegmentMetric formats large size in MB`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(sizeBytes = 1_200_000L))
        assertTrue("Expected '1.2 MB' in: $result", result.contains("1.2 MB"))
    }

    @Test
    fun `formatSegmentMetric formats throughput in MB slash s`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(throughputBytesPerSec = 3_800_000L))
        assertTrue("Expected '3.8 MB/s' in: $result", result.contains("3.8 MB/s"))
    }

    @Test
    fun `formatCdnStatus with HIT shows indicator`() {
        val result = OverlayFormatters.formatCdnStatus(makeCdnInfo(status = CacheStatus.HIT))
        assertTrue("Expected 'HIT' in: $result", result.contains("HIT"))
    }

    @Test
    fun `formatCdnStatus with MISS shows indicator`() {
        val result = OverlayFormatters.formatCdnStatus(makeCdnInfo(status = CacheStatus.MISS))
        assertTrue("Expected 'MISS' in: $result", result.contains("MISS"))
    }

    @Test
    fun `formatCdnStatus with null returns placeholder`() {
        val result = OverlayFormatters.formatCdnStatus(null)
        assertEquals("\u2014", result)
    }

}

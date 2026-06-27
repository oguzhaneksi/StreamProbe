package com.streamprobe.sdk.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentMetricTest {
    @Test
    fun `trackType defaults to UNKNOWN when not supplied`() {
        val metric =
            SegmentMetric(
                requestTimestampMs = 0L,
                totalDurationMs = 0L,
                sizeBytes = 0L,
                throughputBytesPerSec = 0L,
                uri = "https://example.com/seg.ts",
                cdnInfo =
                    CdnHeaderInfo(
                        cacheControl = null,
                        xCache = null,
                        via = null,
                        cdnSpecificHeaders = emptyMap(),
                        cacheStatus = CacheStatus.UNKNOWN,
                        cdnProvider = null,
                    ),
            )
        assertEquals(SegmentTrackType.UNKNOWN, metric.trackType)
    }

    @Test
    fun `trackType retains supplied value`() {
        val metric =
            SegmentMetric(
                requestTimestampMs = 0L,
                totalDurationMs = 0L,
                sizeBytes = 0L,
                throughputBytesPerSec = 0L,
                uri = "https://example.com/seg.ts",
                cdnInfo =
                    CdnHeaderInfo(
                        cacheControl = null,
                        xCache = null,
                        via = null,
                        cdnSpecificHeaders = emptyMap(),
                        cacheStatus = CacheStatus.UNKNOWN,
                        cdnProvider = null,
                    ),
                trackType = SegmentTrackType.VIDEO,
            )
        assertEquals(SegmentTrackType.VIDEO, metric.trackType)
    }
}

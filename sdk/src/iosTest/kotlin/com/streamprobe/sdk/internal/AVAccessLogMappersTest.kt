package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure access-log / error-log mapping helpers (3.4–3.6), exercised without a
 * live AVFoundation event. The end-to-end observation path is covered by the live PoC test.
 */
class AVAccessLogMappersTest {
    @Test
    fun secondsToMillis_convertsAndZeroesUnknown() {
        assertEquals(500, secondsToMillis(0.5))
        assertEquals(2000, secondsToMillis(2.0))
        assertEquals(0, secondsToMillis(0.0))
        assertEquals(0, secondsToMillis(-1.0))
    }

    @Test
    fun segmentThroughput_prefersObservedBitrateThenSizeOverDuration() {
        assertEquals(1_000_000, segmentThroughput(observedBitrate = 8_000_000.0, sizeBytes = 0, durationMs = 0))
        assertEquals(2000, segmentThroughput(observedBitrate = -1.0, sizeBytes = 500, durationMs = 250))
        assertEquals(0, segmentThroughput(observedBitrate = -1.0, sizeBytes = 0, durationMs = 0))
        assertEquals(0, segmentThroughput(observedBitrate = 0.0, sizeBytes = 100, durationMs = 0))
    }

    @Test
    fun accessLogSegmentMetric_mapsRealEntry() {
        val metric =
            accessLogSegmentMetric(
                nowMs = 1_000,
                uri = "https://cdn.example/v0/seg_5.ts",
                sizeBytes = 500_000,
                observedBitrate = 8_000_000.0,
                transferDurationSeconds = 0.5,
            )
        assertEquals(1_000, metric.requestTimestampMs)
        assertEquals(500, metric.totalDurationMs)
        assertEquals(500_000, metric.sizeBytes)
        assertEquals(1_000_000, metric.throughputBytesPerSec)
        assertEquals("https://cdn.example/v0/seg_5.ts", metric.uri)
        assertEquals(CacheStatus.UNKNOWN, metric.cdnInfo.cacheStatus)
        assertNull(metric.networkTiming, "iOS has no per-segment TTFB")
    }

    @Test
    fun accessLogSegmentMetric_degradesUnknownSentinels() {
        val metric =
            accessLogSegmentMetric(
                nowMs = 1,
                uri = null,
                sizeBytes = -1,
                observedBitrate = -1.0,
                transferDurationSeconds = -1.0,
            )
        assertEquals(0, metric.sizeBytes)
        assertEquals(0, metric.totalDurationMs)
        assertEquals(0, metric.throughputBytesPerSec)
        assertEquals("(unknown)", metric.uri)
    }

    @Test
    fun activeTrackFromIndicatedBitrate_carriesBitrateOnly() {
        val track = activeTrackFromIndicatedBitrate(2_500_000.0)
        assertEquals(2_500_000, track.bitrate)
        assertEquals(-1, track.width)
        assertEquals(-1, track.height)
        assertNull(track.codecs)
        assertEquals(0, activeTrackFromIndicatedBitrate(-1.0).bitrate)
    }

    @Test
    fun isBitrateSwitch_detectsValidChangeOnly() {
        assertTrue(isBitrateSwitch(previousIndicated = -1.0, currentIndicated = 200_000.0))
        assertTrue(isBitrateSwitch(previousIndicated = 200_000.0, currentIndicated = 500_000.0))
        assertFalse(isBitrateSwitch(previousIndicated = 200_000.0, currentIndicated = 200_000.0))
        assertFalse(isBitrateSwitch(previousIndicated = 200_000.0, currentIndicated = -1.0))
        assertFalse(isBitrateSwitch(previousIndicated = 200_000.0, currentIndicated = 0.0))
    }

    @Test
    fun droppedFramesError_buildsBurstOfOne() {
        val error = droppedFramesError(nowMs = 5_000, droppedFrames = 12)
        assertEquals(ErrorCategory.DROPPED_FRAMES, error.category)
        assertEquals(5_000, error.timestampMs)
        val detail = assertIs<ErrorDetail.DroppedFrames>(error.categoryDetail)
        assertEquals(12, detail.totalFrames)
        assertEquals(1, detail.burstCount)
        assertEquals(5_000, detail.lastUpdateMs)
    }

    @Test
    fun loadErrorMessage_formatsDomainCodeAndSegment() {
        assertEquals(
            "CoreMediaErrorDomain -12660: seg_3.ts",
            loadErrorMessage("CoreMediaErrorDomain", -12660, "https://cdn/v0/seg_3.ts"),
        )
        assertEquals("Error 404: seg.ts", loadErrorMessage(null, 404, "https://cdn/x/seg.ts"))
        assertEquals("Domain", loadErrorMessage("Domain", 0, null))
        assertEquals("Error", loadErrorMessage(null, 0, null))
    }

    @Test
    fun loadError_mapsToLoadErrorCategory() {
        val error = loadError(7_000, "CoreMediaErrorDomain", 404, "https://cdn/seg.ts", "comment")
        assertEquals(ErrorCategory.LOAD_ERROR, error.category)
        assertEquals(7_000, error.timestampMs)
        assertEquals("CoreMediaErrorDomain 404: seg.ts", error.message)
        assertEquals("comment", error.detail)
    }
}

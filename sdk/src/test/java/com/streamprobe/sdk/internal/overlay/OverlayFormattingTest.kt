package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.CdnProvider
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SwitchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFormattingTest {

    private fun makeCdnInfo(
        status: CacheStatus = CacheStatus.UNKNOWN,
        xCache: String? = null,
        cacheControl: String? = null,
        cdnSpecificHeaders: Map<String, String> = emptyMap(),
        cdnProvider: CdnProvider? = null,
    ) = CdnHeaderInfo(
        cacheControl = cacheControl,
        xCache = xCache,
        via = null,
        cdnSpecificHeaders = cdnSpecificHeaders,
        cacheStatus = status,
        cdnProvider = cdnProvider,
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
    @Test
    fun `formatCdnStatus with known provider prepends provider tag`() {
        val result = OverlayFormatters.formatCdnStatus(
            makeCdnInfo(status = CacheStatus.HIT, cdnProvider = CdnProvider.CLOUDFLARE)
        )
        assertTrue("Expected '[CLOUDFLARE]' prefix in: $result", result.startsWith("[CLOUDFLARE]"))
        assertTrue("Expected 'HIT' in: $result", result.contains("HIT"))
    }

    @Test
    fun `formatCdnStatus with UNKNOWN provider omits provider tag`() {
        val result = OverlayFormatters.formatCdnStatus(
            makeCdnInfo(status = CacheStatus.HIT, cdnProvider = CdnProvider.UNKNOWN)
        )
        assertTrue("Expected no brackets in: $result", !result.startsWith("["))
    }

    @Test
    fun `formatCdnStatus with null provider omits provider tag`() {
        val result = OverlayFormatters.formatCdnStatus(
            makeCdnInfo(status = CacheStatus.MISS, cdnProvider = null)
        )
        assertTrue("Expected no brackets in: $result", !result.startsWith("["))
    }

    @Test
    fun `formatCdnStatus includes all provider labels correctly`() {
        mapOf(
            CdnProvider.CLOUDFLARE to "[CLOUDFLARE]",
            CdnProvider.CLOUDFRONT to "[CLOUDFRONT]",
            CdnProvider.FASTLY to "[FASTLY]",
            CdnProvider.AKAMAI to "[AKAMAI]",
        ).forEach { (provider, expectedTag) ->
            val result = OverlayFormatters.formatCdnStatus(
                makeCdnInfo(status = CacheStatus.HIT, cdnProvider = provider)
            )
            assertTrue("Expected '$expectedTag' in: $result", result.startsWith(expectedTag))
        }
    }
    // ── ABR formatting tests ──────────────────────────────────────────────────

    private fun makeTrack(height: Int, bitrate: Int = 2_500_000) = ActiveTrackInfo(
        bitrate = bitrate,
        width = height * 16 / 9,
        height = height,
        codecs = "avc1.42e00a",
    )

    @Test
    fun `formatAbrSwitch with resolution change shows height labels`() {
        val from = makeTrack(720)
        val to = makeTrack(1080)
        val result = OverlayFormatters.formatAbrSwitch(from, to)
        assertEquals("720p \u2192 1080p", result)
    }

    @Test
    fun `formatAbrSwitch with bitrate-only change shows bitrate labels`() {
        val from = makeTrack(720, bitrate = 1_500_000)
        val to = makeTrack(720, bitrate = 5_000_000)
        val result = OverlayFormatters.formatAbrSwitch(from, to)
        assertTrue("Expected bitrate labels in: $result", result.contains("Mbps") || result.contains("kbps"))
    }

    @Test
    fun `formatAbrSwitch with null from shows dash arrow label`() {
        val to = makeTrack(720)
        val result = OverlayFormatters.formatAbrSwitch(null, to)
        assertTrue("Expected '→ 720p' in: $result", result.contains("720p"))
    }

    @Test
    fun `formatBufferDuration formats seconds`() {
        val result = OverlayFormatters.formatBufferDuration(12_400L)
        assertEquals("buf: 12.4s", result)
    }

    @Test
    fun `formatRelativeTimestamp formats minutes and seconds`() {
        val base = 1_000L
        val result = OverlayFormatters.formatRelativeTimestamp(base + 62_000L, base)
        assertEquals("+1:02", result)
    }

    @Test
    fun `formatRelativeTimestamp zero offset returns plus zero`() {
        val base = 5_000L
        val result = OverlayFormatters.formatRelativeTimestamp(base, base)
        assertEquals("+0:00", result)
    }

    @Test
    fun `formatSwitchReason returns correct label for each enum value`() {
        assertEquals("INITIAL", OverlayFormatters.formatSwitchReason(SwitchReason.INITIAL))
        assertEquals("ADAPTIVE", OverlayFormatters.formatSwitchReason(SwitchReason.ADAPTIVE))
        assertEquals("MANUAL", OverlayFormatters.formatSwitchReason(SwitchReason.MANUAL))
        assertEquals("TRICKPLAY", OverlayFormatters.formatSwitchReason(SwitchReason.TRICKPLAY))
        assertEquals("UNKNOWN", OverlayFormatters.formatSwitchReason(SwitchReason.UNKNOWN))
    }

}

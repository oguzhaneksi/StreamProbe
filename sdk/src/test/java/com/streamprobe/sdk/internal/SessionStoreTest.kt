package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AbrSwitchEvent
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.DashManifestInfo
import com.streamprobe.sdk.model.HlsManifestInfo
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.VariantInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionStoreTest {

    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        store = SessionStore()
    }

    @Test
    fun `initial state is null`() = runTest {
        assertNull(store.manifestInfo.first())
        assertNull(store.activeTrack.first())
    }

    @Test
    fun `updateManifest publishes manifest info`() = runTest {
        val info = HlsManifestInfo(
            variants = listOf(
                VariantInfo(
                    bitrate = 5_000_000,
                    width = 1920,
                    height = 1080,
                    codecs = "avc1.42e00a,mp4a.40.2",
                    frameRate = 30f,
                )
            )
        )

        store.updateManifest(info)

        val result = store.manifestInfo.first()
        assertEquals(info, result)
    }

    @Test
    fun `updateManifest accepts DashManifestInfo`() = runTest {
        val info = DashManifestInfo(
            variants = listOf(
                VariantInfo(
                    bitrate = 2_500_000,
                    width = 1280,
                    height = 720,
                    codecs = "avc1.42e00a",
                    frameRate = 30f,
                )
            )
        )

        store.updateManifest(info)

        val result = store.manifestInfo.first()
        assertEquals(info, result)
        assertEquals(1, result!!.variants.size)
        assertEquals(720, result.variants[0].height)
    }

    @Test
    fun `updateActiveTrack publishes active track`() = runTest {
        val track = ActiveTrackInfo(
            bitrate = 2_500_000,
            width = 1280,
            height = 720,
            codecs = "avc1.42e00a",
        )

        store.updateActiveTrack(track)

        val result = store.activeTrack.first()
        assertEquals(track, result)
    }

    @Test
    fun `clear resets all state to null`() = runTest {
        store.updateManifest(
            HlsManifestInfo(
                variants = listOf(
                    VariantInfo(1_000_000, 640, 360, null, -1f)
                )
            )
        )
        store.updateActiveTrack(
            ActiveTrackInfo(1_000_000, 640, 360, null)
        )

        store.clear()

        assertNull(store.manifestInfo.first())
        assertNull(store.activeTrack.first())
    }

    @Test
    fun `multiple updates overwrite previous values`() = runTest {
        val first = HlsManifestInfo(
            variants = listOf(VariantInfo(1_000_000, 640, 360, null, -1f))
        )
        val second = HlsManifestInfo(
            variants = listOf(
                VariantInfo(1_000_000, 640, 360, null, -1f),
                VariantInfo(5_000_000, 1920, 1080, "avc1.42e00a", 30f),
            )
        )

        store.updateManifest(first)
        assertEquals(1, store.manifestInfo.first()!!.variants.size)

        store.updateManifest(second)
        assertEquals(2, store.manifestInfo.first()!!.variants.size)
    }

    // ── Segment metric tests ──────────────────────────────────────────────────

    private fun makeCdnInfo(status: CacheStatus = CacheStatus.UNKNOWN) = CdnHeaderInfo(
        cacheControl = null,
        xCache = null,
        via = null,
        cdnSpecificHeaders = emptyMap(),
        cacheStatus = status,
        cdnProvider = null,
    )

    private fun makeMetric(uri: String = "https://example.com/seg.ts") = SegmentMetric(
        requestTimestampMs = 1_000L,
        totalDurationMs = 200L,
        sizeBytes = 500_000L,
        throughputBytesPerSec = 2_500_000L,
        uri = uri,
        cdnInfo = makeCdnInfo(CacheStatus.HIT),
    )

    @Test
    fun `initial segment metrics list is empty`() = runTest {
        assertEquals(emptyList<SegmentMetric>(), store.segmentMetrics.first())
    }

    @Test
    fun `initial latest segment metric is null`() = runTest {
        assertNull(store.latestSegmentMetric.first())
    }

    @Test
    fun `addSegmentMetric appends to list`() = runTest {
        val m1 = makeMetric("https://example.com/seg1.ts")
        val m2 = makeMetric("https://example.com/seg2.ts")

        store.addSegmentMetric(m1)
        store.addSegmentMetric(m2)

        val result = store.segmentMetrics.first()
        assertEquals(2, result.size)
        assertEquals(m1, result[0])
        assertEquals(m2, result[1])
    }

    @Test
    fun `addSegmentMetric updates latestSegmentMetric`() = runTest {
        val m1 = makeMetric("https://example.com/seg1.ts")
        val m2 = makeMetric("https://example.com/seg2.ts")

        store.addSegmentMetric(m1)
        store.addSegmentMetric(m2)

        assertEquals(m2, store.latestSegmentMetric.first())
    }

    @Test
    fun `segment metric embeds cdn info correctly`() = runTest {
        val cdnInfo = CdnHeaderInfo(
            cacheControl = "max-age=3600",
            xCache = "HIT",
            via = "1.1 varnish",
            cdnSpecificHeaders = mapOf("cf-cache-status" to "HIT"),
            cacheStatus = CacheStatus.HIT,
            cdnProvider = null,
        )
        val metric = SegmentMetric(
            requestTimestampMs = 1_000L,
            totalDurationMs = 300L,
            sizeBytes = 1_000_000L,
            throughputBytesPerSec = 3_333_333L,
            uri = "https://example.com/seg.ts",
            cdnInfo = cdnInfo,
        )

        store.addSegmentMetric(metric)

        val result = store.segmentMetrics.first().first()
        assertEquals(cdnInfo, result.cdnInfo)
    }

    @Test
    fun `clear resets segment state`() = runTest {
        store.addSegmentMetric(makeMetric())

        store.clear()

        assertEquals(emptyList<SegmentMetric>(), store.segmentMetrics.first())
        assertNull(store.latestSegmentMetric.first())
    }

    @Test
    fun `segment metrics list caps at max size`() = runTest {
        repeat(501) { i ->
            store.addSegmentMetric(makeMetric("https://example.com/seg$i.ts"))
        }

        val result = store.segmentMetrics.first()
        assertEquals(500, result.size)
        // The first metric (index 0) should have been dropped; the last remaining is seg1
        assertEquals("https://example.com/seg1.ts", result.first().uri)
        assertEquals("https://example.com/seg500.ts", result.last().uri)
    }

    // ── ABR switch event tests ────────────────────────────────────────────────

    private fun makeTrack(height: Int = 720, bitrate: Int = 2_500_000) = ActiveTrackInfo(
        bitrate = bitrate,
        width = height * 16 / 9,
        height = height,
        codecs = "avc1.42e00a",
    )

    private fun makeAbrEvent(
        timestampMs: Long = 1_000L,
        previousTrack: ActiveTrackInfo? = makeTrack(480),
        newTrack: ActiveTrackInfo = makeTrack(720),
        reason: SwitchReason = SwitchReason.ADAPTIVE,
    ) = AbrSwitchEvent(
        timestampMs = timestampMs,
        previousTrack = previousTrack,
        newTrack = newTrack,
        bufferDurationMs = 5_000L,
        reason = reason,
    )

    @Test
    fun `initial ABR switch events list is empty`() = runTest {
        assertEquals(emptyList<AbrSwitchEvent>(), store.abrSwitchEvents.first())
    }

    @Test
    fun `addAbrSwitchEvent appends to list`() = runTest {
        val e1 = makeAbrEvent(timestampMs = 1_000L)
        val e2 = makeAbrEvent(timestampMs = 2_000L)

        store.addAbrSwitchEvent(e1)
        store.addAbrSwitchEvent(e2)

        val result = store.abrSwitchEvents.first()
        assertEquals(2, result.size)
        assertEquals(e1, result[0])
        assertEquals(e2, result[1])
    }

    @Test
    fun `ABR switch events list caps at max size`() = runTest {
        repeat(201) { i ->
            store.addAbrSwitchEvent(makeAbrEvent(timestampMs = i.toLong()))
        }

        val result = store.abrSwitchEvents.first()
        assertEquals(200, result.size)
        assertEquals(1L, result.first().timestampMs)
        assertEquals(200L, result.last().timestampMs)
    }

    @Test
    fun `clear resets ABR switch events`() = runTest {
        store.addAbrSwitchEvent(makeAbrEvent())

        store.clear()

        assertEquals(emptyList<AbrSwitchEvent>(), store.abrSwitchEvents.first())
    }
}

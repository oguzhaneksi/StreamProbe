package com.streamprobe.sdk.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.CacheStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlayerInterceptorTest {

    private lateinit var sessionStore: SessionStore
    private lateinit var interceptor: PlayerInterceptor
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        sessionStore = SessionStore()
        interceptor = PlayerInterceptor(sessionStore)
        player = mock(ExoPlayer::class.java)
    }

    @Test
    fun `attach registers listener on player`() {
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        verify(player).addListener(interceptor)
    }

    @Test
    fun `detach removes listener from player`() {
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)
        interceptor.detach()

        verify(player).removeListener(interceptor)
    }

    @Test
    fun `manifest remains null for non-HLS content`() = runTest {
        `when`(player.currentManifest).thenReturn("not an HLS manifest")
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        assertNull(sessionStore.manifestInfo.first())
    }

    @Test
    fun `probes manifest immediately on attach when available`() = runTest {
        val format1080p = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(5_000_000)
            .setWidth(1920)
            .setHeight(1080)
            .setCodecs("avc1.42e00a,mp4a.40.2")
            .setFrameRate(30f)
            .build()

        val variant = HlsMultivariantPlaylist.Variant(
            /* url= */ Uri.EMPTY,
            /* format= */ format1080p,
            /* videoGroupId= */ null,
            /* audioGroupId= */ null,
            /* subtitleGroupId= */ null,
            /* captionGroupId= */ null,
            /* pathwayId= */null,
            /* stableVariantId= */null
        )

        val multivariantPlaylist = HlsMultivariantPlaylist(
            /* baseUri= */ "https://example.com/master.m3u8",
            /* tags= */ emptyList(),
            /* variants= */ listOf(variant),
            /* videos= */ emptyList(),
            /* audios= */ emptyList(),
            /* subtitles= */ emptyList(),
            /* closedCaptions= */ emptyList(),
            /* muxedAudioFormat= */ null,
            /* muxedCaptionFormats= */ null,
            /* hasIndependentSegments= */ false,
            /* variableDefinitions= */ emptyMap(),
            /* sessionKeyDrmInitData= */ emptyList(),
        )

        val mediaPlaylist = mock(HlsMediaPlaylist::class.java)
        val manifest = HlsManifest::class.java
            .getDeclaredConstructor(HlsMultivariantPlaylist::class.java, HlsMediaPlaylist::class.java)
            .also { it.isAccessible = true }
            .newInstance(multivariantPlaylist, mediaPlaylist)

        `when`(player.currentManifest).thenReturn(manifest)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        val result = sessionStore.manifestInfo.first()
        assertNotNull(result)
        assertEquals(1, result!!.variants.size)

        val v = result.variants[0]
        assertEquals(5_000_000, v.bitrate)
        assertEquals(1920, v.width)
        assertEquals(1080, v.height)
        assertEquals("avc1.42e00a,mp4a.40.2", v.codecs)
        assertEquals(30f, v.frameRate)
    }

    @Test
    fun `clear after detach resets session store`() = runTest {
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)
        interceptor.detach()
        sessionStore.clear()

        assertNull(sessionStore.manifestInfo.first())
        assertNull(sessionStore.activeTrack.first())
    }

    // ── onLoadCompleted tests ─────────────────────────────────────────────────

    private fun makeEventTime(): AnalyticsListener.EventTime =
        mock(AnalyticsListener.EventTime::class.java)

    private fun makeLoadEventInfo(
        uri: Uri = Uri.parse("https://example.com/seg.ts"),
        responseHeaders: Map<String, List<String>> = emptyMap(),
        loadDurationMs: Long = 200L,
        bytesLoaded: Long = 500_000L,
    ): LoadEventInfo = LoadEventInfo(
        /* loadTaskId= */ 1L,
        /* dataSpec= */ DataSpec(uri),
        /* uri= */ uri,
        /* responseHeaders= */ responseHeaders,
        /* elapsedRealtimeMs= */ 1000L,
        /* loadDurationMs= */ loadDurationMs,
        /* bytesLoaded= */ bytesLoaded,
    )

    @Test
    fun `onLoadCompleted for media type creates segment metric`() = runTest {
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metrics = sessionStore.segmentMetrics.first()
        assertEquals(1, metrics.size)
    }

    @Test
    fun `onLoadCompleted for non-media type is ignored`() = runTest {
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MANIFEST)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        assertTrue(sessionStore.segmentMetrics.first().isEmpty())
    }

    @Test
    fun `segment metric has correct duration and size`() = runTest {
        val loadEventInfo = makeLoadEventInfo(loadDurationMs = 320L, bytesLoaded = 1_200_000L)
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metric = sessionStore.segmentMetrics.first().first()
        assertEquals(320L, metric.totalDurationMs)
        assertEquals(1_200_000L, metric.sizeBytes)
    }

    @Test
    fun `segment metric throughput calculation`() = runTest {
        val loadEventInfo = makeLoadEventInfo(loadDurationMs = 500L, bytesLoaded = 1_000L)
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metric = sessionStore.segmentMetrics.first().first()
        assertEquals(2000L, metric.throughputBytesPerSec)
    }

    @Test
    fun `zero duration load does not divide by zero`() = runTest {
        val loadEventInfo = makeLoadEventInfo(loadDurationMs = 0L, bytesLoaded = 1_000L)
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metric = sessionStore.segmentMetrics.first().first()
        assertEquals(0L, metric.throughputBytesPerSec)
    }

    @Test
    fun `onLoadCompleted with response headers populates cdnInfo`() = runTest {
        val headers = mapOf("X-Cache" to listOf("HIT"))
        val loadEventInfo = makeLoadEventInfo(responseHeaders = headers)
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metric = sessionStore.segmentMetrics.first().first()
        assertEquals(CacheStatus.HIT, metric.cdnInfo.cacheStatus)
    }

    @Test
    fun `onLoadCompleted with empty headers produces UNKNOWN cdn status`() = runTest {
        val loadEventInfo = makeLoadEventInfo(responseHeaders = emptyMap())
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

        val metric = sessionStore.segmentMetrics.first().first()
        assertEquals(CacheStatus.UNKNOWN, metric.cdnInfo.cacheStatus)
    }
}

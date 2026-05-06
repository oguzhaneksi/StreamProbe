package com.streamprobe.sdk.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.TrackSwitchEvent
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
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        verify(player).addListener(interceptor)
    }

    @Test
    fun `detach removes listener from player`() {
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)
        interceptor.detach()

        verify(player).removeListener(interceptor)
    }

    @Test
    fun `track list is empty snapshot when player has no tracks`() =
        runTest {
            `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

            interceptor.attach(player)

            val info = sessionStore.trackListInfo.first()
            assertNotNull(info)
            assertTrue(info!!.variants.isEmpty())
            assertTrue(info.audioTracks.isEmpty())
            assertTrue(info.subtitleTracks.isEmpty())
        }

    @Test
    fun `trackListInfo is cleared to empty snapshot when player transitions to no tracks`() =
        runTest {
            val format1080p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(5_000_000)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()
            `when`(player.currentTracks).thenReturn(makeSingleVideoTrackGroup(format1080p))
            interceptor.attach(player)
            assertEquals(
                1,
                sessionStore.trackListInfo
                    .first()!!
                    .variants.size,
            )

            // Simulate a transition to empty tracks (e.g., between media items).
            `when`(player.currentTracks).thenReturn(Tracks.EMPTY)
            interceptor.onTracksChanged(Tracks.EMPTY)

            val info = sessionStore.trackListInfo.first()
            assertNotNull(info)
            assertTrue(info!!.variants.isEmpty())
        }

    @Test
    fun `probes tracks immediately on attach when available`() =
        runTest {
            val format1080p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(5_000_000)
                    .setWidth(1920)
                    .setHeight(1080)
                    .setCodecs("avc1.42e00a,mp4a.40.2")
                    .setFrameRate(30f)
                    .build()

            `when`(player.currentTracks).thenReturn(makeSingleVideoTrackGroup(format1080p))

            interceptor.attach(player)

            val result = sessionStore.trackListInfo.first()
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
    fun `clear after detach resets session store`() =
        runTest {
            `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

            interceptor.attach(player)
            interceptor.detach()
            sessionStore.clear()

            assertNull(sessionStore.trackListInfo.first())
            assertNull(sessionStore.activeTrack.first())
        }

    // ── onLoadCompleted tests ─────────────────────────────────────────────────

    private fun makeEventTime(): AnalyticsListener.EventTime = mock(AnalyticsListener.EventTime::class.java)

    private fun makeLoadEventInfo(
        uri: Uri = Uri.parse("https://example.com/seg.ts"),
        responseHeaders: Map<String, List<String>> = emptyMap(),
        loadDurationMs: Long = 200L,
        bytesLoaded: Long = 500_000L,
    ): LoadEventInfo =
        LoadEventInfo(
            // loadTaskId=
            1L,
            // dataSpec=
            DataSpec(uri),
            // uri=
            uri,
            // responseHeaders=
            responseHeaders,
            // elapsedRealtimeMs=
            1000L,
            // loadDurationMs=
            loadDurationMs,
            // bytesLoaded=
            bytesLoaded,
        )

    @Test
    fun `onLoadCompleted for media type creates segment metric`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo()
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metrics = sessionStore.segmentMetrics.first()
            assertEquals(1, metrics.size)
        }

    @Test
    fun `onLoadCompleted for non-media type is ignored`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo()
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MANIFEST)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            assertTrue(sessionStore.segmentMetrics.first().isEmpty())
        }

    @Test
    fun `segment metric has correct duration and size`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo(loadDurationMs = 320L, bytesLoaded = 1_200_000L)
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metric = sessionStore.segmentMetrics.first().first()
            assertEquals(320L, metric.totalDurationMs)
            assertEquals(1_200_000L, metric.sizeBytes)
        }

    @Test
    fun `segment metric throughput calculation`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo(loadDurationMs = 500L, bytesLoaded = 1_000L)
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metric = sessionStore.segmentMetrics.first().first()
            assertEquals(2000L, metric.throughputBytesPerSec)
        }

    @Test
    fun `zero duration load does not divide by zero`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo(loadDurationMs = 0L, bytesLoaded = 1_000L)
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metric = sessionStore.segmentMetrics.first().first()
            assertEquals(0L, metric.throughputBytesPerSec)
        }

    @Test
    fun `onLoadCompleted with response headers populates cdnInfo`() =
        runTest {
            val headers = mapOf("X-Cache" to listOf("HIT"))
            val loadEventInfo = makeLoadEventInfo(responseHeaders = headers)
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metric = sessionStore.segmentMetrics.first().first()
            assertEquals(CacheStatus.HIT, metric.cdnInfo.cacheStatus)
        }

    @Test
    fun `onLoadCompleted with empty headers produces UNKNOWN cdn status`() =
        runTest {
            val loadEventInfo = makeLoadEventInfo(responseHeaders = emptyMap())
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

            interceptor.onLoadCompleted(makeEventTime(), loadEventInfo, mediaLoadData)

            val metric = sessionStore.segmentMetrics.first().first()
            assertEquals(CacheStatus.UNKNOWN, metric.cdnInfo.cacheStatus)
        }

    // ── onDownstreamFormatChanged / ABR tests ─────────────────────────────────

    private fun makeVideoFormat(
        height: Int = 720,
        bitrate: Int = 2_500_000,
    ) = Format
        .Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setAverageBitrate(bitrate)
        .setWidth(height * 16 / 9)
        .setHeight(height)
        .setCodecs("avc1.42e00a")
        .build()

    private fun makeVideoMediaLoadData(
        format: Format,
        selectionReason: Int = C.SELECTION_REASON_ADAPTIVE,
    ) = MediaLoadData(
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_VIDEO,
        format,
        selectionReason,
        null,
        0L,
        0L,
    )

    @Test
    fun `onDownstreamFormatChanged with non-video track type emits audio event`() =
        runTest {
            val format =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setAverageBitrate(128_000)
                    .setChannelCount(2)
                    .setSampleRate(48_000)
                    .build()
            val mediaLoadData =
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_AUDIO,
                    format,
                    C.SELECTION_REASON_INITIAL,
                    null,
                    0L,
                    0L,
                )

            interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
            assertTrue(events[0] is TrackSwitchEvent.AudioSwitch)
        }

    @Test
    fun `onDownstreamFormatChanged with DEFAULT track type and no video dimensions is ignored`() =
        runTest {
            val audioFormat =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setAverageBitrate(128_000)
                    .setWidth(Format.NO_VALUE)
                    .setHeight(Format.NO_VALUE)
                    .build()
            val mediaLoadData =
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_DEFAULT,
                    audioFormat,
                    C.SELECTION_REASON_ADAPTIVE,
                    null,
                    0L,
                    0L,
                )

            interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

            assertTrue(sessionStore.trackSwitchEvents.first().isEmpty())
        }

    @Test
    fun `DEFAULT track type with no video dimensions does not overwrite pendingVideoSwitchReason`() =
        runTest {
            val videoFormat = makeVideoFormat(720)
            val audioFormat =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setAverageBitrate(128_000)
                    .setWidth(Format.NO_VALUE)
                    .setHeight(Format.NO_VALUE)
                    .build()

            // Set pending reason to INITIAL via a real video downstream event.
            interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(videoFormat, C.SELECTION_REASON_INITIAL))

            // A DEFAULT event with no video dimensions (e.g., muxed audio) fires with ADAPTIVE.
            interceptor.onDownstreamFormatChanged(
                makeEventTime(),
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_DEFAULT,
                    audioFormat,
                    C.SELECTION_REASON_ADAPTIVE,
                    null,
                    0L,
                    0L,
                ),
            )

            // The video input format event should still use the original INITIAL reason.
            interceptor.onVideoInputFormatChanged(makeEventTime(), videoFormat, null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
            assertEquals(SwitchReason.INITIAL, (events[0] as TrackSwitchEvent.VideoSwitch).reason)
        }

    @Test
    fun `onDownstreamFormatChanged with DEFAULT track type does not emit VideoSwitch`() =
        runTest {
            val format = makeVideoFormat(720)
            val mediaLoadData =
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_DEFAULT,
                    format,
                    C.SELECTION_REASON_INITIAL,
                    null,
                    0L,
                    0L,
                )

            interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

            assertTrue(sessionStore.trackSwitchEvents.first().isEmpty())
        }

    @Test
    fun `initial video input format creates VideoSwitch event with null previousTrack`() =
        runTest {
            val format = makeVideoFormat(720)

            interceptor.onVideoInputFormatChanged(makeEventTime(), format, null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
            val e = events[0] as TrackSwitchEvent.VideoSwitch
            assertNull(e.previousTrack)
            assertEquals(720, e.newTrack.height)
            assertEquals(SwitchReason.INITIAL, e.reason)
        }

    @Test
    fun `second video input format creates VideoSwitch event with previous track and cached reason`() =
        runTest {
            val format480 = makeVideoFormat(480)
            val format720 = makeVideoFormat(720)

            // Prime the pending reason then fire the input format events.
            interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format480, C.SELECTION_REASON_INITIAL))
            interceptor.onVideoInputFormatChanged(makeEventTime(), format480, null)
            interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format720, C.SELECTION_REASON_ADAPTIVE))
            interceptor.onVideoInputFormatChanged(makeEventTime(), format720, null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(2, events.size)
            val e = events[1] as TrackSwitchEvent.VideoSwitch
            assertEquals(480, e.previousTrack!!.height)
            assertEquals(720, e.newTrack.height)
            assertEquals(SwitchReason.ADAPTIVE, e.reason)
        }

    @Test
    fun `same video input format repeated does not create duplicate VideoSwitch event`() =
        runTest {
            val format = makeVideoFormat(720)

            interceptor.onVideoInputFormatChanged(makeEventTime(), format, null)
            interceptor.onVideoInputFormatChanged(makeEventTime(), format, null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
        }

    @Test
    fun `VideoSwitch event captures buffer duration`() =
        runTest {
            `when`(player.currentTracks).thenReturn(Tracks.EMPTY)
            `when`(player.totalBufferedDuration).thenReturn(12_400L)

            interceptor.attach(player)
            interceptor.onVideoInputFormatChanged(makeEventTime(), makeVideoFormat(), null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(12_400L, (events[0] as TrackSwitchEvent.VideoSwitch).bufferDurationMs)
        }

    @Test
    fun `mapSelectionReason maps all C_SELECTION_REASON constants`() {
        assertEquals(SwitchReason.INITIAL, interceptor.mapSelectionReason(C.SELECTION_REASON_INITIAL))
        assertEquals(SwitchReason.ADAPTIVE, interceptor.mapSelectionReason(C.SELECTION_REASON_ADAPTIVE))
        assertEquals(SwitchReason.MANUAL, interceptor.mapSelectionReason(C.SELECTION_REASON_MANUAL))
        assertEquals(SwitchReason.TRICKPLAY, interceptor.mapSelectionReason(C.SELECTION_REASON_TRICK_PLAY))
        assertEquals(SwitchReason.UNKNOWN, interceptor.mapSelectionReason(C.SELECTION_REASON_UNKNOWN))
        assertEquals(SwitchReason.UNKNOWN, interceptor.mapSelectionReason(999))
    }

    @Test
    fun `detach resets video state so next input format event has null previousTrack`() =
        runTest {
            `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

            interceptor.attach(player)
            interceptor.onVideoInputFormatChanged(makeEventTime(), makeVideoFormat(480), null)
            interceptor.detach()
            sessionStore.clear()

            val player2 = mock(ExoPlayer::class.java)
            `when`(player2.currentTracks).thenReturn(Tracks.EMPTY)
            interceptor.attach(player2)
            interceptor.onVideoInputFormatChanged(makeEventTime(), makeVideoFormat(720), null)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
            assertNull((events[0] as TrackSwitchEvent.VideoSwitch).previousTrack)
        }

    @Test
    fun `onVideoInputFormatChanged updates active track and emits VideoSwitch`() =
        runTest {
            val format = makeVideoFormat(1080)

            interceptor.onVideoInputFormatChanged(makeEventTime(), format, null)

            val track = sessionStore.activeTrack.first()
            assertNotNull(track)
            assertEquals(1080, track!!.height)

            val events = sessionStore.trackSwitchEvents.first()
            assertEquals(1, events.size)
            assertEquals(1080, (events[0] as TrackSwitchEvent.VideoSwitch).newTrack.height)
        }

    private fun makeSingleVideoTrackGroup(format: Format): Tracks {
        val trackGroup = TrackGroup(format)
        val group =
            Tracks.Group(
                trackGroup,
                // adaptiveSupported=
                false,
                // trackSupport=
                intArrayOf(C.FORMAT_HANDLED),
                // trackSelected=
                booleanArrayOf(true),
            )
        return Tracks(listOf(group))
    }
}

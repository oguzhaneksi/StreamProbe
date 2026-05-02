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
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SingleSegmentBase
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.DashManifestInfo
import com.streamprobe.sdk.model.SubtitleKind
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

    // ── onDownstreamFormatChanged / ABR tests ─────────────────────────────────

    private fun makeVideoFormat(height: Int = 720, bitrate: Int = 2_500_000) = Format.Builder()
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
    fun `onDownstreamFormatChanged with non-video track type emits audio event`() = runTest {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(128_000)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        val mediaLoadData = MediaLoadData(
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
    fun `onDownstreamFormatChanged with DEFAULT track type and no video dimensions is ignored`() = runTest {
        val audioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(128_000)
            .setWidth(Format.NO_VALUE)
            .setHeight(Format.NO_VALUE)
            .build()
        val mediaLoadData = MediaLoadData(
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
    fun `onDownstreamFormatChanged with DEFAULT track type and video dimensions creates VideoSwitch event`() = runTest {
        val format = makeVideoFormat(720)
        val mediaLoadData = MediaLoadData(
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_DEFAULT,
            format,
            C.SELECTION_REASON_INITIAL,
            null,
            0L,
            0L,
        )

        interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

        val events = sessionStore.trackSwitchEvents.first()
        assertEquals(1, events.size)
        assertEquals(720, (events[0] as TrackSwitchEvent.VideoSwitch).newTrack.height)
    }

    @Test
    fun `initial downstream format creates VideoSwitch event with null previousTrack`() = runTest {
        val format = makeVideoFormat(720)

        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format, C.SELECTION_REASON_INITIAL))

        val events = sessionStore.trackSwitchEvents.first()
        assertEquals(1, events.size)
        val e = events[0] as TrackSwitchEvent.VideoSwitch
        assertNull(e.previousTrack)
        assertEquals(720, e.newTrack.height)
        assertEquals(SwitchReason.INITIAL, e.reason)
    }

    @Test
    fun `second downstream format creates VideoSwitch event with previous track`() = runTest {
        val format480 = makeVideoFormat(480)
        val format720 = makeVideoFormat(720)

        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format480, C.SELECTION_REASON_INITIAL))
        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format720, C.SELECTION_REASON_ADAPTIVE))

        val events = sessionStore.trackSwitchEvents.first()
        assertEquals(2, events.size)
        val e = events[1] as TrackSwitchEvent.VideoSwitch
        assertEquals(480, e.previousTrack!!.height)
        assertEquals(720, e.newTrack.height)
        assertEquals(SwitchReason.ADAPTIVE, e.reason)
    }

    @Test
    fun `same format repeated does not create duplicate VideoSwitch event`() = runTest {
        val format = makeVideoFormat(720)

        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format, C.SELECTION_REASON_INITIAL))
        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(format, C.SELECTION_REASON_ADAPTIVE))

        val events = sessionStore.trackSwitchEvents.first()
        assertEquals(1, events.size)
    }

    @Test
    fun `VideoSwitch event captures buffer duration`() = runTest {
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)
        `when`(player.totalBufferedDuration).thenReturn(12_400L)

        interceptor.attach(player)
        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(makeVideoFormat()))

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
    fun `detach resets lastDownstreamTrack so next event has null previousTrack`() = runTest {
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)
        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(makeVideoFormat(480), C.SELECTION_REASON_INITIAL))
        interceptor.detach()
        sessionStore.clear()

        val player2 = mock(ExoPlayer::class.java)
        `when`(player2.currentManifest).thenReturn(null)
        `when`(player2.currentTracks).thenReturn(Tracks.EMPTY)
        interceptor.attach(player2)
        interceptor.onDownstreamFormatChanged(makeEventTime(), makeVideoMediaLoadData(makeVideoFormat(720), C.SELECTION_REASON_INITIAL))

        val events = sessionStore.trackSwitchEvents.first()
        assertEquals(1, events.size)
        assertNull((events[0] as TrackSwitchEvent.VideoSwitch).previousTrack)
    }

    @Test
    fun `onVideoInputFormatChanged still updates active track`() = runTest {
        val format = makeVideoFormat(1080)

        interceptor.onVideoInputFormatChanged(makeEventTime(), format, null)

        val track = sessionStore.activeTrack.first()
        assertNotNull(track)
        assertEquals(1080, track!!.height)
    }

    // ── DASH manifest tests ───────────────────────────────────────────────────

    private fun makeVideoRepresentation(format: Format): Representation =
        Representation.newInstance(0L, format, listOf(BaseUrl("")), SingleSegmentBase())

    private fun makeAdaptationSet(
        type: Int,
        vararg representations: Representation,
    ): AdaptationSet = AdaptationSet(
        /* id= */ 0L,
        /* type= */ type,
        /* representations= */ representations.toList(),
        /* accessibilityDescriptors= */ emptyList(),
        /* essentialProperties= */ emptyList(),
        /* supplementalProperties= */ emptyList(),
    )

    private fun makeDashPeriod(vararg adaptationSets: AdaptationSet): Period =
        Period("period-0", 0L, adaptationSets.toList())

    private fun makeDashManifest(vararg periods: Period): DashManifest = DashManifest(
        /* availabilityStartTimeMs= */ 0L,
        /* durationMs= */ 60_000L,
        /* minBufferTimeMs= */ 1_500L,
        /* dynamic= */ false,
        /* minUpdatePeriodMs= */ C.TIME_UNSET,
        /* timeShiftBufferDepthMs= */ C.TIME_UNSET,
        /* suggestedPresentationDelayMs= */ C.TIME_UNSET,
        /* publishTimeMs= */ C.TIME_UNSET,
        /* programInformation= */ null,
        /* utcTiming= */ null,
        /* serviceDescription= */ null,
        /* location= */ null,
        /* periods= */ periods.toList(),
    )

    @Test
    fun `probes DASH manifest immediately on attach when available`() = runTest {
        val format1080p = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(5_000_000)
            .setWidth(1920)
            .setHeight(1080)
            .setCodecs("avc1.42e00a")
            .setFrameRate(30f)
            .build()
        val format720p = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(2_500_000)
            .setWidth(1280)
            .setHeight(720)
            .setCodecs("avc1.42e00a")
            .setFrameRate(30f)
            .build()

        val adaptationSet = makeAdaptationSet(
            C.TRACK_TYPE_VIDEO,
            makeVideoRepresentation(format1080p),
            makeVideoRepresentation(format720p),
        )
        val manifest = makeDashManifest(makeDashPeriod(adaptationSet))

        `when`(player.currentManifest).thenReturn(manifest)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        val result = sessionStore.manifestInfo.first()
        assertNotNull(result)
        assertTrue(result is DashManifestInfo)
        assertEquals(2, result!!.variants.size)
    }

    @Test
    fun `DASH manifest extracts correct format fields`() = runTest {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(5_000_000)
            .setWidth(1920)
            .setHeight(1080)
            .setCodecs("avc1.64001f")
            .setFrameRate(60f)
            .build()

        val manifest = makeDashManifest(
            makeDashPeriod(makeAdaptationSet(C.TRACK_TYPE_VIDEO, makeVideoRepresentation(format)))
        )

        `when`(player.currentManifest).thenReturn(manifest)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        val variant = sessionStore.manifestInfo.first()!!.variants[0]
        assertEquals(5_000_000, variant.bitrate)
        assertEquals(1920, variant.width)
        assertEquals(1080, variant.height)
        assertEquals("avc1.64001f", variant.codecs)
        assertEquals(60f, variant.frameRate)
    }

    @Test
    fun `DASH manifest captures audio AdaptationSets`() = runTest {
        val videoFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(2_500_000)
            .setWidth(1280).setHeight(720)
            .build()
        val audioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(128_000)
            .setLanguage("en")
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()

        val videoAdaptationSet = makeAdaptationSet(C.TRACK_TYPE_VIDEO, makeVideoRepresentation(videoFormat))
        val audioAdaptationSet = makeAdaptationSet(C.TRACK_TYPE_AUDIO, makeVideoRepresentation(audioFormat))
        val manifest = makeDashManifest(makeDashPeriod(videoAdaptationSet, audioAdaptationSet))

        `when`(player.currentManifest).thenReturn(manifest)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        val result = sessionStore.manifestInfo.first()!!
        assertEquals(1, result.variants.size)
        assertEquals(720, result.variants[0].height)
        assertEquals(1, result.audioTracks.size)
        assertEquals("en", result.audioTracks[0].language)
    }

    @Test
    fun `DASH manifest flattens multiple Periods`() = runTest {
        val format1 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(1_000_000).setWidth(640).setHeight(360).build()
        val format2 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(5_000_000).setWidth(1920).setHeight(1080).build()

        val period1 = makeDashPeriod(makeAdaptationSet(C.TRACK_TYPE_VIDEO, makeVideoRepresentation(format1)))
        val period2 = makeDashPeriod(makeAdaptationSet(C.TRACK_TYPE_VIDEO, makeVideoRepresentation(format2)))
        val manifest = makeDashManifest(period1, period2)

        `when`(player.currentManifest).thenReturn(manifest)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)

        interceptor.attach(player)

        val result = sessionStore.manifestInfo.first()!!
        assertEquals(2, result.variants.size)
        assertEquals(360, result.variants[0].height)
        assertEquals(1080, result.variants[1].height)
    }

    // ── probeTracks / onDownstreamFormatChanged track type inference ──────────

    private fun makeSingleSelectedTrackGroup(format: Format): Tracks {
        val trackGroup = TrackGroup(format)
        val group = Tracks.Group(
            trackGroup,
            /* adaptiveSupported= */ false,
            /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
            /* trackSelected= */ booleanArrayOf(true),
        )
        return Tracks(listOf(group))
    }

    @Test
    fun `probeTracks infers isMuxed true when audio containerMimeType starts with video`() = runTest {
        val muxedAudioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setContainerMimeType(MimeTypes.VIDEO_MP2T)
            .setLanguage("en")
            .setAverageBitrate(128_000)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()

        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(makeSingleSelectedTrackGroup(muxedAudioFormat))

        interceptor.attach(player)

        val audio = sessionStore.activeAudioTrack.first()
        assertNotNull(audio)
        assertTrue(audio!!.isMuxed)
    }

    @Test
    fun `probeTracks infers isMuxed false when audio containerMimeType is non-video`() = runTest {
        val audioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("en")
            .setAverageBitrate(128_000)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()

        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(makeSingleSelectedTrackGroup(audioFormat))

        interceptor.attach(player)

        val audio = sessionStore.activeAudioTrack.first()
        assertNotNull(audio)
        assertTrue(!audio!!.isMuxed)
    }

    @Test
    fun `probeTracks infers CC for any CEA-608 subtitle`() = runTest {
        val ccFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
            .setLanguage("en")
            .build()

        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(makeSingleSelectedTrackGroup(ccFormat))

        interceptor.attach(player)

        val subtitle = sessionStore.activeSubtitleTrack.first()
        assertNotNull(subtitle)
        assertEquals(SubtitleKind.CC, subtitle!!.kind)
    }

    @Test
    fun `probeTracks infers SIDECAR for non-CEA subtitle`() = runTest {
        val sidecarFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("fr")
            .build()

        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(makeSingleSelectedTrackGroup(sidecarFormat))

        interceptor.attach(player)

        val subtitle = sessionStore.activeSubtitleTrack.first()
        assertNotNull(subtitle)
        assertEquals(SubtitleKind.SIDECAR, subtitle!!.kind)
    }

    @Test
    fun `onDownstreamFormatChanged infers isMuxed true for audio in video container`() = runTest {
        val muxedAudioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setContainerMimeType(MimeTypes.VIDEO_MP2T)
            .setLanguage("en")
            .setAverageBitrate(128_000)
            .build()
        val mediaLoadData = MediaLoadData(
            C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, muxedAudioFormat,
            C.SELECTION_REASON_INITIAL, null, 0L, 0L,
        )

        interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

        val event = sessionStore.trackSwitchEvents.first()[0] as TrackSwitchEvent.AudioSwitch
        assertTrue(event.newTrack.isMuxed)
    }

    @Test
    fun `onDownstreamFormatChanged infers CC for any CEA-608 subtitle`() = runTest {
        val ccFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
            .setLanguage("en")
            .build()
        val mediaLoadData = MediaLoadData(
            C.DATA_TYPE_MEDIA, C.TRACK_TYPE_TEXT, ccFormat,
            C.SELECTION_REASON_INITIAL, null, 0L, 0L,
        )

        interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

        val event = sessionStore.trackSwitchEvents.first()[0] as TrackSwitchEvent.SubtitleSwitch
        assertEquals(SubtitleKind.CC, event.newTrack!!.kind)
    }
}

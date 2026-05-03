package com.streamprobe.sdk.internal

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SingleSegmentBase
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.DashManifestInfo
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.TrackSwitchEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlayerInterceptorDashManifestTest {
    private lateinit var sessionStore: SessionStore
    private lateinit var interceptor: PlayerInterceptor
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        sessionStore = SessionStore()
        interceptor = PlayerInterceptor(sessionStore)
        player = mock(ExoPlayer::class.java)
    }

    private fun makeEventTime(): AnalyticsListener.EventTime = mock(AnalyticsListener.EventTime::class.java)

    // ── DASH manifest test helpers ────────────────────────────────────────────

    private fun makeVideoRepresentation(format: Format): Representation =
        Representation.newInstance(0L, format, listOf(BaseUrl("")), SingleSegmentBase())

    private fun makeAdaptationSet(
        type: Int,
        vararg representations: Representation,
    ): AdaptationSet =
        AdaptationSet(
            // id=
            0L,
            // type=
            type,
            // representations=
            representations.toList(),
            // accessibilityDescriptors=
            emptyList(),
            // essentialProperties=
            emptyList(),
            // supplementalProperties=
            emptyList(),
        )

    private fun makeDashPeriod(vararg adaptationSets: AdaptationSet): Period = Period("period-0", 0L, adaptationSets.toList())

    private fun makeDashManifest(vararg periods: Period): DashManifest =
        DashManifest(
            // availabilityStartTimeMs=
            0L,
            // durationMs=
            60_000L,
            // minBufferTimeMs=
            1_500L,
            // dynamic=
            false,
            // minUpdatePeriodMs=
            C.TIME_UNSET,
            // timeShiftBufferDepthMs=
            C.TIME_UNSET,
            // suggestedPresentationDelayMs=
            C.TIME_UNSET,
            // publishTimeMs=
            C.TIME_UNSET,
            // programInformation=
            null,
            // utcTiming=
            null,
            // serviceDescription=
            null,
            // location=
            null,
            // periods=
            periods.toList(),
        )

    // ── DASH manifest tests ───────────────────────────────────────────────────

    @Test
    fun `probes DASH manifest immediately on attach when available`() =
        runTest {
            val format1080p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(5_000_000)
                    .setWidth(1920)
                    .setHeight(1080)
                    .setCodecs("avc1.42e00a")
                    .setFrameRate(30f)
                    .build()
            val format720p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(2_500_000)
                    .setWidth(1280)
                    .setHeight(720)
                    .setCodecs("avc1.42e00a")
                    .setFrameRate(30f)
                    .build()

            val adaptationSet =
                makeAdaptationSet(
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
    fun `DASH manifest extracts correct format fields`() =
        runTest {
            val format =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(5_000_000)
                    .setWidth(1920)
                    .setHeight(1080)
                    .setCodecs("avc1.64001f")
                    .setFrameRate(60f)
                    .build()

            val manifest =
                makeDashManifest(
                    makeDashPeriod(makeAdaptationSet(C.TRACK_TYPE_VIDEO, makeVideoRepresentation(format))),
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
    fun `DASH manifest captures audio AdaptationSets`() =
        runTest {
            val videoFormat =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(2_500_000)
                    .setWidth(1280)
                    .setHeight(720)
                    .build()
            val audioFormat =
                Format
                    .Builder()
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
    fun `DASH manifest flattens multiple Periods`() =
        runTest {
            val format1 =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(1_000_000)
                    .setWidth(640)
                    .setHeight(360)
                    .build()
            val format2 =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(5_000_000)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()

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

    @Test
    fun `probeTracks infers isMuxed true when audio containerMimeType starts with video`() =
        runTest {
            val muxedAudioFormat =
                Format
                    .Builder()
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
    fun `probeTracks infers isMuxed false when audio containerMimeType is non-video`() =
        runTest {
            val audioFormat =
                Format
                    .Builder()
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
    fun `probeTracks infers CC for any CEA-608 subtitle`() =
        runTest {
            val ccFormat =
                Format
                    .Builder()
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
    fun `probeTracks infers SIDECAR for non-CEA subtitle`() =
        runTest {
            val sidecarFormat =
                Format
                    .Builder()
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
    fun `onDownstreamFormatChanged infers isMuxed true for audio in video container`() =
        runTest {
            val muxedAudioFormat =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                    .setLanguage("en")
                    .setAverageBitrate(128_000)
                    .build()
            val mediaLoadData =
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_AUDIO,
                    muxedAudioFormat,
                    C.SELECTION_REASON_INITIAL,
                    null,
                    0L,
                    0L,
                )

            interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

            val event = sessionStore.trackSwitchEvents.first()[0] as TrackSwitchEvent.AudioSwitch
            assertTrue(event.newTrack.isMuxed)
        }

    @Test
    fun `onDownstreamFormatChanged infers CC for any CEA-608 subtitle`() =
        runTest {
            val ccFormat =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                    .setLanguage("en")
                    .build()
            val mediaLoadData =
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    C.TRACK_TYPE_TEXT,
                    ccFormat,
                    C.SELECTION_REASON_INITIAL,
                    null,
                    0L,
                    0L,
                )

            interceptor.onDownstreamFormatChanged(makeEventTime(), mediaLoadData)

            val event = sessionStore.trackSwitchEvents.first()[0] as TrackSwitchEvent.SubtitleSwitch
            assertEquals(SubtitleKind.CC, event.newTrack!!.kind)
        }
}

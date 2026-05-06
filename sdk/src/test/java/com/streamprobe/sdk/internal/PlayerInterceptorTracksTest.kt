package com.streamprobe.sdk.internal

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
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
class PlayerInterceptorTracksTest {
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

    // ── Track group helpers ───────────────────────────────────────────────────

    private fun makeVideoTracksGroup(
        vararg formats: Format,
        selectedIndex: Int = 0,
    ): Tracks {
        val trackGroup = TrackGroup(*formats)
        val trackSelected = BooleanArray(formats.size) { it == selectedIndex }
        val trackSupport = IntArray(formats.size) { C.FORMAT_HANDLED }
        val group = Tracks.Group(trackGroup, true, trackSupport, trackSelected)
        return Tracks(listOf(group))
    }

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

    private fun makeTracksWithVideoAndAudio(
        videoFormat: Format,
        audioFormat: Format,
    ): Tracks {
        val videoGroup = Tracks.Group(TrackGroup(videoFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val audioGroup = Tracks.Group(TrackGroup(audioFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        return Tracks(listOf(videoGroup, audioGroup))
    }

    // ── probeTracks manifest building tests ──────────────────────────────────

    @Test
    fun `probeTracks populates manifest info with multiple video renditions`() =
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

            `when`(player.currentTracks).thenReturn(makeVideoTracksGroup(format1080p, format720p, selectedIndex = 1))

            interceptor.attach(player)

            val result = sessionStore.trackListInfo.first()
            assertNotNull(result)
            assertEquals(2, result!!.variants.size)
        }

    @Test
    fun `probeTracks extracts correct format fields into VariantInfo`() =
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

            `when`(player.currentTracks).thenReturn(makeSingleSelectedTrackGroup(format))

            interceptor.attach(player)

            val variant = sessionStore.trackListInfo.first()!!.variants[0]
            assertEquals(5_000_000, variant.bitrate)
            assertEquals(1920, variant.width)
            assertEquals(1080, variant.height)
            assertEquals("avc1.64001f", variant.codecs)
            assertEquals(60f, variant.frameRate)
        }

    @Test
    fun `probeTracks captures audio tracks alongside video`() =
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

            `when`(player.currentTracks).thenReturn(makeTracksWithVideoAndAudio(videoFormat, audioFormat))

            interceptor.attach(player)

            val result = sessionStore.trackListInfo.first()!!
            assertEquals(1, result.variants.size)
            assertEquals(720, result.variants[0].height)
            assertEquals(1, result.audioTracks.size)
            assertEquals("en", result.audioTracks[0].language)
        }

    @Test
    fun `probeTracks sets isSelected true only for selected video rendition`() =
        runTest {
            val format360p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(640)
                    .setHeight(360)
                    .build()
            val format1080p =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()

            // Select 1080p (index 1)
            `when`(player.currentTracks).thenReturn(makeVideoTracksGroup(format360p, format1080p, selectedIndex = 1))

            interceptor.attach(player)

            val variants = sessionStore.trackListInfo.first()!!.variants
            assertEquals(2, variants.size)
            val sorted = variants.sortedBy { it.height }
            assertTrue(!sorted[0].isSelected) // 360p not selected
            assertTrue(sorted[1].isSelected) // 1080p selected
        }

    // ── probeTracks / onDownstreamFormatChanged track type inference ──────────

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

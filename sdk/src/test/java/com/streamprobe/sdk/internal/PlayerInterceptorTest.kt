package com.streamprobe.sdk.internal

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private lateinit var player: Player

    @Before
    fun setUp() {
        sessionStore = SessionStore()
        interceptor = PlayerInterceptor(sessionStore)
        player = mock(Player::class.java)
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
}

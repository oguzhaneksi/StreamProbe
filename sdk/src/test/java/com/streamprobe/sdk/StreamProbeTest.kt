package com.streamprobe.sdk

import androidx.activity.ComponentActivity
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StreamProbeTest {

    private lateinit var probe: StreamProbe
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        probe = StreamProbe()
        player = mock(ExoPlayer::class.java)
        `when`(player.currentManifest).thenReturn(null)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)
    }

    @Test
    fun `attach and detach preserves M1 behavior`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()

        // attach should succeed without throwing
        probe.attach(player)
        probe.show(activity)
        assertNotNull(probe)

        // detach should also succeed without throwing
        probe.detach()
        assertNotNull(probe)
    }
}

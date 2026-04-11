package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
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
}

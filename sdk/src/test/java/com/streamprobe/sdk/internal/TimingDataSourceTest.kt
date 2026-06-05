package com.streamprobe.sdk.internal

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class TimingDataSourceTest {
    private lateinit var registry: NetworkTimingRegistry
    private lateinit var delegate: DataSource
    private lateinit var factory: TimingDataSourceFactory

    @Before
    fun setUp() {
        registry = NetworkTimingRegistry()
        delegate = mock(DataSource::class.java)
        factory =
            TimingDataSourceFactory(
                delegate = DataSource.Factory { delegate },
                registry = registry,
            )
    }

    @Test
    fun `http open records ttfb in registry`() {
        val uri = Uri.parse("https://example.com/seg.ts")
        val dataSpec = DataSpec(uri)
        `when`(delegate.open(dataSpec)).thenReturn(1024L)

        factory.createDataSource().open(dataSpec)

        val ttfb = registry.consume(uri.toString(), 0L)
        assertNotNull(ttfb)
        assertTrue("Expected ttfbMs >= 0 but was $ttfb", ttfb!! >= 0L)
    }

    @Test
    fun `open throwing records nothing in registry`() {
        val uri = Uri.parse("https://example.com/seg.ts")
        val dataSpec = DataSpec(uri)
        `when`(delegate.open(dataSpec)).thenThrow(IOException("network error"))

        try {
            factory.createDataSource().open(dataSpec)
        } catch (_: IOException) {
        }

        assertNull(registry.consume(uri.toString(), 0L))
    }

    @Test
    fun `non-http scheme skips recording`() {
        val uri = Uri.parse("file:///sdcard/video.mp4")
        val dataSpec = DataSpec(uri)
        `when`(delegate.open(dataSpec)).thenReturn(2048L)

        factory.createDataSource().open(dataSpec)

        assertNull(registry.consume(uri.toString(), 0L))
    }

    @Test
    fun `position is part of the registry key`() {
        val uri = Uri.parse("https://example.com/seg.ts")
        val dataSpec =
            DataSpec
                .Builder()
                .setUri(uri)
                .setPosition(512L)
                .build()
        `when`(delegate.open(dataSpec)).thenReturn(1024L)

        factory.createDataSource().open(dataSpec)

        assertNull("Expected no entry at position 0", registry.consume(uri.toString(), 0L))
        assertNotNull("Expected entry at position 512", registry.consume(uri.toString(), 512L))
    }

    @Test
    fun `delegate open is called exactly once`() {
        val uri = Uri.parse("https://example.com/seg.ts")
        val dataSpec = DataSpec(uri)
        `when`(delegate.open(dataSpec)).thenReturn(1024L)

        factory.createDataSource().open(dataSpec)

        verify(delegate, times(1)).open(dataSpec)
    }

    @Test
    fun `getResponseHeaders is forwarded to delegate`() {
        val headers = mapOf("X-Cache" to listOf("HIT"))
        `when`(delegate.getResponseHeaders()).thenReturn(headers)

        assertEquals(headers, factory.createDataSource().getResponseHeaders())
    }
}

package com.streamprobe.sdk.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackErrorTrackingTest {

    private lateinit var sessionStore: SessionStore
    private lateinit var interceptor: PlayerInterceptor

    @Before
    fun setUp() {
        sessionStore = SessionStore()
        interceptor = PlayerInterceptor(sessionStore)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun makeEventTime(): AnalyticsListener.EventTime =
        mock(AnalyticsListener.EventTime::class.java)

    private fun makeLoadEventInfo(
        uri: Uri = Uri.parse("https://example.com/seg_42.ts"),
    ): LoadEventInfo = LoadEventInfo(
        /* loadTaskId= */ 1L,
        /* dataSpec= */ DataSpec(uri),
        /* uri= */ uri,
        /* responseHeaders= */ emptyMap(),
        /* elapsedRealtimeMs= */ 1000L,
        /* loadDurationMs= */ 200L,
        /* bytesLoaded= */ 500_000L,
    )

    private fun makeHttpException(responseCode: Int): HttpDataSource.InvalidResponseCodeException {
        return HttpDataSource.InvalidResponseCodeException(
            responseCode,
            "Response code: $responseCode",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com/seg.ts")),
            byteArrayOf(),
        )
    }

    // ── onLoadError tests ──────────────────────────────────────────────────────

    @Test
    fun `onLoadError creates LOAD_ERROR event with HTTP status`() = runTest {
        val error = makeHttpException(404)
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, false)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertEquals(ErrorCategory.LOAD_ERROR, errors[0].category)
        assertTrue("Expected 'HTTP 404' in: ${errors[0].message}", errors[0].message.contains("HTTP 404"))
    }

    @Test
    fun `onLoadError creates LOAD_ERROR event for generic IOException`() = runTest {
        val error = IOException("Connection reset")
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, false)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertTrue(
            "Expected exception class name in: ${errors[0].message}",
            errors[0].message.contains("IOException")
        )
    }

    @Test
    fun `onLoadError prefixes manifest data-type errors`() = runTest {
        val error = makeHttpException(404)
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MANIFEST)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, false)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertTrue(
            "Expected 'MANIFEST ' prefix in: ${errors[0].message}",
            errors[0].message.startsWith("MANIFEST ")
        )
    }

    @Test
    fun `onLoadError ignores DRM data-type errors`() = runTest {
        val error = IOException("DRM license error")
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_DRM)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, false)

        assertTrue(sessionStore.playbackErrors.first().isEmpty())
    }

    @Test
    fun `onLoadError with wasCanceled=true is ignored`() = runTest {
        val error = makeHttpException(404)
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, true)

        assertTrue(sessionStore.playbackErrors.first().isEmpty())
    }

    @Test
    fun `onLoadError stores event in SessionStore`() = runTest {
        val error = makeHttpException(404)
        val loadEventInfo = makeLoadEventInfo()
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        interceptor.onLoadError(makeEventTime(), loadEventInfo, mediaLoadData, error, false)

        assertEquals(1, sessionStore.playbackErrors.first().size)
    }

    // ── onVideoCodecError tests ────────────────────────────────────────────────

    @Test
    fun `onVideoCodecError creates VIDEO_CODEC_ERROR event`() = runTest {
        val error = Exception("MediaCodec failed")

        interceptor.onVideoCodecError(makeEventTime(), error)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertEquals(ErrorCategory.VIDEO_CODEC_ERROR, errors[0].category)
        assertTrue(errors[0].message.contains("MediaCodec failed"))
    }

    // ── onAudioCodecError tests ──────────────────────────────────────────────────

    @Test
    fun `onAudioCodecError creates AUDIO_CODEC_ERROR event`() = runTest {
        val error = Exception("AudioCodec decode failed")

        interceptor.onAudioCodecError(makeEventTime(), error)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertEquals(ErrorCategory.AUDIO_CODEC_ERROR, errors[0].category)
        assertTrue(errors[0].message.contains("AudioCodec decode failed"))
        assertEquals(error.toString(), errors[0].detail)
    }

    // ── onDroppedVideoFrames tests ─────────────────────────────────────────────

    @Test
    fun `onDroppedVideoFrames at threshold creates event`() = runTest {
        interceptor.onDroppedVideoFrames(makeEventTime(), PlayerInterceptor.DROPPED_FRAME_THRESHOLD, 100L)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertEquals(ErrorCategory.DROPPED_FRAMES, errors[0].category)
        val detail = errors[0].categoryDetail as ErrorDetail.DroppedFrames
        assertEquals(PlayerInterceptor.DROPPED_FRAME_THRESHOLD, detail.totalFrames)
        assertEquals(1, detail.burstCount)
    }

    @Test
    fun `onDroppedVideoFrames below threshold is ignored`() = runTest {
        interceptor.onDroppedVideoFrames(makeEventTime(), PlayerInterceptor.DROPPED_FRAME_THRESHOLD - 1, 100L)

        assertTrue(sessionStore.playbackErrors.first().isEmpty())
    }

    @Test
    fun `consecutive dropped-frames within window are merged`() = runTest {
        val windowMs = SessionStore.DROPPED_FRAMES_DEDUP_WINDOW_MS

        // First event at t=1000
        val eventTime = makeEventTime()
        interceptor.onDroppedVideoFrames(eventTime, 5, 100L)
        val firstTimestamp = sessionStore.playbackErrors.value.first().timestampMs

        // Simulate second event within window by directly calling addPlaybackError
        // (since we can't control System.currentTimeMillis in the interceptor easily)
        val secondTimestamp = firstTimestamp + (windowMs / 2)
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = secondTimestamp,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "3 frames in 80ms",
                categoryDetail = ErrorDetail.DroppedFrames(
                    totalFrames = 3,
                    burstCount = 1,
                    lastUpdateMs = secondTimestamp,
                ),
            )
        )

        val errors = sessionStore.playbackErrors.first()
        assertEquals("List should have 1 merged entry", 1, errors.size)
        val merged = errors[0]
        val detail = merged.categoryDetail as ErrorDetail.DroppedFrames
        assertEquals(8, detail.totalFrames)
        assertEquals(2, detail.burstCount)
        assertTrue("Merged message should mention bursts", merged.message.contains("2 bursts"))
        // timestampMs is preserved from first event
        assertEquals(firstTimestamp, merged.timestampMs)
        assertEquals(secondTimestamp, detail.lastUpdateMs)
    }

    @Test
    fun `dropped-frames after window appends new entry`() = runTest {
        val windowMs = SessionStore.DROPPED_FRAMES_DEDUP_WINDOW_MS

        val t1 = System.currentTimeMillis()
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = t1,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "5 frames in 100ms",
                categoryDetail = ErrorDetail.DroppedFrames(totalFrames = 5, burstCount = 1, lastUpdateMs = t1),
            )
        )

        val t2 = t1 + windowMs + 1_000L  // well after the window
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = t2,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "3 frames in 80ms",
                categoryDetail = ErrorDetail.DroppedFrames(totalFrames = 3, burstCount = 1, lastUpdateMs = t2),
            )
        )

        assertEquals(2, sessionStore.playbackErrors.first().size)
    }

    @Test
    fun `dropped-frames merge does not affect non-dropped categories`() = runTest {
        val error = IOException("Connection reset")
        val loadEventInfo = makeLoadEventInfo()
        interceptor.onLoadError(makeEventTime(), loadEventInfo, MediaLoadData(C.DATA_TYPE_MEDIA), error, false)

        val t = System.currentTimeMillis()
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = t,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "5 frames in 100ms",
                categoryDetail = ErrorDetail.DroppedFrames(totalFrames = 5, burstCount = 1, lastUpdateMs = t),
            )
        )

        assertEquals(2, sessionStore.playbackErrors.first().size)
    }

    @Test
    fun `dropped-frames merge at MAX_PLAYBACK_ERRORS does not drop entry`() = runTest {
        val windowMs = SessionStore.DROPPED_FRAMES_DEDUP_WINDOW_MS
        val maxErrors = 200

        // Fill list to max with non-merged dropped-frames events (spaced apart beyond window)
        var t = 0L
        repeat(maxErrors) {
            t += windowMs + 1_000L
            sessionStore.addPlaybackError(
                com.streamprobe.sdk.model.PlaybackErrorEvent(
                    timestampMs = t,
                    category = ErrorCategory.DROPPED_FRAMES,
                    message = "5 frames in 100ms",
                    categoryDetail = ErrorDetail.DroppedFrames(totalFrames = 5, burstCount = 1, lastUpdateMs = t),
                )
            )
        }
        assertEquals(maxErrors, sessionStore.playbackErrors.value.size)

        // Next event within window should merge into last entry
        val withinWindowT = t + (windowMs / 2)
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = withinWindowT,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "3 frames in 80ms",
                categoryDetail = ErrorDetail.DroppedFrames(totalFrames = 3, burstCount = 1, lastUpdateMs = withinWindowT),
            )
        )

        assertEquals("Size should remain at max after merge", maxErrors, sessionStore.playbackErrors.value.size)
        val last = sessionStore.playbackErrors.value.last()
        val detail = last.categoryDetail as ErrorDetail.DroppedFrames
        assertEquals(2, detail.burstCount)
    }

    // ── onAudioSinkError tests ─────────────────────────────────────────────────

    @Test
    fun `onAudioSinkError creates AUDIO_SINK_ERROR event`() = runTest {
        val error = Exception("AudioTrack write failed")

        interceptor.onAudioSinkError(makeEventTime(), error)

        val errors = sessionStore.playbackErrors.first()
        assertEquals(1, errors.size)
        assertEquals(ErrorCategory.AUDIO_SINK_ERROR, errors[0].category)
        assertTrue(errors[0].message.contains("AudioTrack write failed"))
    }

    // ── Error cap tests ────────────────────────────────────────────────────────

    @Test
    fun `error list is capped at MAX_PLAYBACK_ERRORS`() = runTest {
        val maxErrors = 200
        repeat(maxErrors + 1) { i ->
            sessionStore.addPlaybackError(
                com.streamprobe.sdk.model.PlaybackErrorEvent(
                    timestampMs = i.toLong(),
                    category = ErrorCategory.VIDEO_CODEC_ERROR,
                    message = "Error $i",
                )
            )
        }

        assertEquals(maxErrors, sessionStore.playbackErrors.first().size)
        // Oldest entry should have been dropped
        assertTrue(sessionStore.playbackErrors.first().none { it.message == "Error 0" })
    }

    // ── clearPlaybackErrors tests ──────────────────────────────────────────────

    @Test
    fun `clearPlaybackErrors empties errors but preserves other state`() = runTest {
        sessionStore.updateManifest(
            com.streamprobe.sdk.model.HlsManifestInfo(
                variants = listOf(com.streamprobe.sdk.model.VariantInfo(1_000_000, 640, 360, null, -1f))
            )
        )
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = 1000L,
                category = ErrorCategory.LOAD_ERROR,
                message = "HTTP 404: seg.ts",
            )
        )

        sessionStore.clearPlaybackErrors()

        assertTrue(sessionStore.playbackErrors.first().isEmpty())
        // manifestInfo should still be present
        assertEquals(1, sessionStore.manifestInfo.first()!!.variants.size)
    }

    @Test
    fun `clear resets errors along with other state`() = runTest {
        sessionStore.addPlaybackError(
            com.streamprobe.sdk.model.PlaybackErrorEvent(
                timestampMs = 1000L,
                category = ErrorCategory.LOAD_ERROR,
                message = "HTTP 404: seg.ts",
            )
        )

        sessionStore.clear()

        assertTrue(sessionStore.playbackErrors.first().isEmpty())
        assertNull(sessionStore.manifestInfo.first())
    }
}

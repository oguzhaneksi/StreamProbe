package com.streamprobe.sdk.internal

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DrmSchemeDetectorTest {
    private fun makeEventTime(
        timeline: Timeline = Timeline.EMPTY,
        windowIndex: Int = 0,
    ): AnalyticsListener.EventTime = AnalyticsListener.EventTime(0L, timeline, windowIndex, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L)

    // ── mapUuidToScheme ──────────────────────────────────────────────────────

    @Test
    fun `mapUuidToScheme returns WIDEVINE for Widevine UUID`() {
        assertEquals(DrmScheme.WIDEVINE, DrmSchemeDetector.mapUuidToScheme(C.WIDEVINE_UUID))
    }

    @Test
    fun `mapUuidToScheme returns PLAYREADY for PlayReady UUID`() {
        assertEquals(DrmScheme.PLAYREADY, DrmSchemeDetector.mapUuidToScheme(C.PLAYREADY_UUID))
    }

    @Test
    fun `mapUuidToScheme returns CLEARKEY for ClearKey UUID`() {
        assertEquals(DrmScheme.CLEARKEY, DrmSchemeDetector.mapUuidToScheme(C.CLEARKEY_UUID))
    }

    @Test
    fun `mapUuidToScheme returns null for unknown UUID`() {
        assertNull(DrmSchemeDetector.mapUuidToScheme(UUID.randomUUID()))
    }

    // ── mapDrmState ──────────────────────────────────────────────────────────

    @Test
    fun `mapDrmState maps all five DrmSession states`() {
        assertEquals(DrmSessionState.OPENING, DrmSchemeDetector.mapDrmState(DrmSession.STATE_OPENING))
        assertEquals(DrmSessionState.OPENED, DrmSchemeDetector.mapDrmState(DrmSession.STATE_OPENED))
        assertEquals(DrmSessionState.OPENED_WITH_KEYS, DrmSchemeDetector.mapDrmState(DrmSession.STATE_OPENED_WITH_KEYS))
        assertEquals(DrmSessionState.RELEASED, DrmSchemeDetector.mapDrmState(DrmSession.STATE_RELEASED))
        assertEquals(DrmSessionState.ERROR, DrmSchemeDetector.mapDrmState(DrmSession.STATE_ERROR))
    }

    @Test
    fun `mapDrmState defaults to UNKNOWN for unrecognised state`() {
        assertEquals(DrmSessionState.UNKNOWN, DrmSchemeDetector.mapDrmState(-1))
    }

    // ── detectScheme ─────────────────────────────────────────────────────────

    @Test
    fun `detectScheme returns UNKNOWN for empty timeline`() {
        val result = DrmSchemeDetector.detectScheme(makeEventTime(Timeline.EMPTY))
        assertEquals(DrmScheme.UNKNOWN, result)
    }
}

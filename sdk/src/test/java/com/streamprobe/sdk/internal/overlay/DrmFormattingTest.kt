package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.ErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class DrmFormattingTest {
    // ── formatDrmStatus ──────────────────────────────────────────────────────

    @Test
    fun `formatDrmStatus with null returns dash`() {
        assertEquals("—", DrmFormatters.formatDrmStatus(null))
    }

    @Test
    fun `formatDrmStatus with Widevine keys loaded and latency`() {
        val info = DrmStatusInfo(DrmScheme.WIDEVINE, DrmSessionState.OPENED_WITH_KEYS, 312L)
        val result = DrmFormatters.formatDrmStatus(info)
        assertEquals("Widevine  ·  Keys Loaded  ·  312ms", result)
    }

    @Test
    fun `formatDrmStatus without latency omits latency part`() {
        val info = DrmStatusInfo(DrmScheme.WIDEVINE, DrmSessionState.OPENING, null)
        val result = DrmFormatters.formatDrmStatus(info)
        assertEquals("Widevine  ·  Opening", result)
    }

    // ── formatDrmScheme ──────────────────────────────────────────────────────

    @Test
    fun `formatDrmScheme for all values`() {
        assertEquals("Widevine", DrmFormatters.formatDrmScheme(DrmScheme.WIDEVINE))
        assertEquals("PlayReady", DrmFormatters.formatDrmScheme(DrmScheme.PLAYREADY))
        assertEquals("ClearKey", DrmFormatters.formatDrmScheme(DrmScheme.CLEARKEY))
        assertEquals("Unknown DRM", DrmFormatters.formatDrmScheme(DrmScheme.UNKNOWN))
    }

    // ── formatDrmSchemeBadge ─────────────────────────────────────────────────

    @Test
    fun `formatDrmSchemeBadge for all values`() {
        assertEquals("WV", DrmFormatters.formatDrmSchemeBadge(DrmScheme.WIDEVINE))
        assertEquals("PR", DrmFormatters.formatDrmSchemeBadge(DrmScheme.PLAYREADY))
        assertEquals("CK", DrmFormatters.formatDrmSchemeBadge(DrmScheme.CLEARKEY))
        assertEquals("DRM", DrmFormatters.formatDrmSchemeBadge(DrmScheme.UNKNOWN))
    }

    // ── formatDrmSessionState ────────────────────────────────────────────────

    @Test
    fun `formatDrmSessionState for all values`() {
        assertEquals("Opening", DrmFormatters.formatDrmSessionState(DrmSessionState.OPENING))
        assertEquals("Opened", DrmFormatters.formatDrmSessionState(DrmSessionState.OPENED))
        assertEquals("Keys Loaded", DrmFormatters.formatDrmSessionState(DrmSessionState.OPENED_WITH_KEYS))
        assertEquals("Released", DrmFormatters.formatDrmSessionState(DrmSessionState.RELEASED))
        assertEquals("Error", DrmFormatters.formatDrmSessionState(DrmSessionState.ERROR))
    }

    // ── formatDrmEventLabel ──────────────────────────────────────────────────

    @Test
    fun `formatDrmEventLabel for all four event types`() {
        val acquired = DrmSessionEvent.SessionAcquired(0L, DrmScheme.WIDEVINE, DrmSessionState.OPENING)
        assertEquals("Session Acquired (Opening)", DrmFormatters.formatDrmEventLabel(acquired))

        val keysLoaded = DrmSessionEvent.KeysLoaded(0L, DrmScheme.WIDEVINE, 312L)
        assertEquals("Keys Loaded", DrmFormatters.formatDrmEventLabel(keysLoaded))

        val released = DrmSessionEvent.SessionReleased(0L, DrmScheme.WIDEVINE)
        assertEquals("Session Released", DrmFormatters.formatDrmEventLabel(released))

        val error = DrmSessionEvent.SessionError(0L, DrmScheme.WIDEVINE, "License expired", null)
        assertEquals("Error: License expired", DrmFormatters.formatDrmEventLabel(error))
    }

    // ── formatErrorCategory for DRM_ERROR ────────────────────────────────────

    @Test
    fun `formatErrorCategory for DRM_ERROR returns DRM`() {
        assertEquals("DRM", OverlayFormatters.formatErrorCategory(ErrorCategory.DRM_ERROR))
    }
}

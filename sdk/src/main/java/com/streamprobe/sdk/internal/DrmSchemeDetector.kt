package com.streamprobe.sdk.internal

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionState
import java.util.UUID

/**
 * Pure, framework-independent helper for DRM scheme detection and state mapping.
 * Extracted from the tracker so PlayerInterceptor's function count stays within detekt limits.
 */
@UnstableApi
internal object DrmSchemeDetector {
    /**
     * Resolves the DRM scheme for the window referenced by [eventTime].
     * Uses [eventTime.timeline] rather than player.currentMediaItem so the correct
     * MediaItem is obtained during playlist transitions.
     */
    fun detectScheme(eventTime: AnalyticsListener.EventTime): DrmScheme {
        val timeline = eventTime.timeline
        if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return DrmScheme.UNKNOWN
        val uuid =
            timeline
                .getWindow(eventTime.windowIndex, Timeline.Window())
                .mediaItem.localConfiguration
                ?.drmConfiguration
                ?.scheme
        return uuid?.let { mapUuidToScheme(it) } ?: DrmScheme.UNKNOWN
    }

    fun mapUuidToScheme(uuid: UUID): DrmScheme? =
        when (uuid) {
            C.WIDEVINE_UUID -> DrmScheme.WIDEVINE
            C.PLAYREADY_UUID -> DrmScheme.PLAYREADY
            C.CLEARKEY_UUID -> DrmScheme.CLEARKEY
            else -> null
        }

    fun mapDrmState(
        @DrmSession.State state: Int,
    ): DrmSessionState =
        when (state) {
            DrmSession.STATE_OPENING -> DrmSessionState.OPENING
            DrmSession.STATE_OPENED -> DrmSessionState.OPENED
            DrmSession.STATE_OPENED_WITH_KEYS -> DrmSessionState.OPENED_WITH_KEYS
            DrmSession.STATE_RELEASED -> DrmSessionState.RELEASED
            DrmSession.STATE_ERROR -> DrmSessionState.ERROR
            else -> DrmSessionState.UNKNOWN
        }
}

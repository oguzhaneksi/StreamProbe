package com.streamprobe.sdk.internal

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionState
import java.util.UUID

/**
 * Android-side helper for DRM scheme detection from a Media3 timeline. The pure mapping logic
 * (UUID → scheme, state-int → [DrmSessionState]) lives in [DrmSchemeDetectorCommon]; this object
 * adapts the Media3 [UUID] / [DrmSession.State] types onto it.
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

    fun mapUuidToScheme(uuid: UUID): DrmScheme? = DrmSchemeDetectorCommon.mapUuidToScheme(uuid.toString())

    fun mapDrmState(
        @DrmSession.State state: Int,
    ): DrmSessionState = DrmSchemeDetectorCommon.mapDrmState(state)
}

package com.streamprobe.sdk.internal

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.KeyRequestInfo
import com.streamprobe.sdk.internal.DrmSchemeDetector.mapDrmState
import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.PlaybackErrorEvent

/**
 * Separate [AnalyticsListener] that handles only DRM callbacks, keeping
 * [PlayerInterceptor]'s function count within detekt's TooManyFunctions limit.
 *
 * Registered by [PlayerInterceptor.attach] as an additional analytics listener;
 * unregistered and reset in [PlayerInterceptor.detach].
 *
 * **Known limitations (single-session assumption):**
 * - [lastDrmAcquireTimestampMs] is a single value; concurrent audio/video DRM sessions
 *   will overwrite each other's acquire timestamp, causing inaccurate latency attribution.
 * - [onDrmKeysLoaded] may fire multiple times on key rotation; each call measures latency
 *   relative to the original acquire timestamp, so later measurements inflate (informational).
 */
@UnstableApi
internal class DrmSessionTracker(
    private val sessionStore: SessionStore,
) : AnalyticsListener {
    private var lastDrmAcquireTimestampMs: Long = 0L
    private var currentDrmScheme: DrmScheme = DrmScheme.UNKNOWN

    override fun onDrmSessionAcquired(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        val now = System.currentTimeMillis()
        lastDrmAcquireTimestampMs = now
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        val drmState = mapDrmState(state)
        sessionStore.addDrmSessionEvent(DrmSessionEvent.SessionAcquired(now, currentDrmScheme, drmState))
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, drmState))
        Log.d(TAG, "DRM session acquired: ${currentDrmScheme.name}")
    }

    override fun onDrmKeysLoaded(
        eventTime: AnalyticsListener.EventTime,
        keyRequestInfo: KeyRequestInfo,
    ) {
        val now = System.currentTimeMillis()
        val latency = if (lastDrmAcquireTimestampMs > 0) now - lastDrmAcquireTimestampMs else 0L
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        sessionStore.addDrmSessionEvent(DrmSessionEvent.KeysLoaded(now, currentDrmScheme, latency))
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, DrmSessionState.OPENED_WITH_KEYS, latency))
        Log.d(TAG, "DRM keys loaded: ${currentDrmScheme.name} latency=${latency}ms")
    }

    override fun onDrmSessionReleased(eventTime: AnalyticsListener.EventTime) {
        val now = System.currentTimeMillis()
        val releasedScheme = currentDrmScheme
        currentDrmScheme = DrmScheme.UNKNOWN
        lastDrmAcquireTimestampMs = 0L
        sessionStore.addDrmSessionEvent(DrmSessionEvent.SessionReleased(now, releasedScheme))
        sessionStore.updateDrmState(null)
        Log.d(TAG, "DRM session released: ${releasedScheme.name}")
    }

    override fun onDrmSessionManagerError(
        eventTime: AnalyticsListener.EventTime,
        error: Exception,
    ) {
        val now = System.currentTimeMillis()
        if (currentDrmScheme == DrmScheme.UNKNOWN) {
            currentDrmScheme = DrmSchemeDetector.detectScheme(eventTime)
        }
        sessionStore.addDrmSessionEvent(
            DrmSessionEvent.SessionError(
                timestampMs = now,
                scheme = currentDrmScheme,
                message = error.message ?: error::class.simpleName ?: "DRM error",
                detail = error.toString(),
            ),
        )
        sessionStore.updateDrmState(DrmStatusInfo(currentDrmScheme, DrmSessionState.ERROR))
        // Dual surface: also route to the Errors view (single source, no duplication).
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = now,
                category = ErrorCategory.DRM_ERROR,
                message = error.message ?: error::class.simpleName ?: "DRM session error",
                detail = error.toString(),
                categoryDetail =
                    ErrorDetail.DrmErrorInfo(
                        scheme = currentDrmScheme,
                        errorClass = error::class.simpleName ?: "Exception",
                    ),
            ),
        )
        Log.d(TAG, "DRM session error: ${error.message}")
    }

    /** Called from [PlayerInterceptor.detach] to reset per-session state. */
    fun reset() {
        lastDrmAcquireTimestampMs = 0L
        currentDrmScheme = DrmScheme.UNKNOWN
    }

    private companion object {
        const val TAG = "[StreamProbe] DrmSessionTracker"
    }
}

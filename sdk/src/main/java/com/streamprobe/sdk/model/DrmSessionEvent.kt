package com.streamprobe.sdk.model

/** DRM session lifecycle event for the DRM timeline tab. Follows the [TrackSwitchEvent] pattern. */
sealed interface DrmSessionEvent {
    val timestampMs: Long
    val scheme: DrmScheme

    data class SessionAcquired(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val state: DrmSessionState,
    ) : DrmSessionEvent

    data class KeysLoaded(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        // Approximate: may inflate on key rotation (informational only — see Known Limitations in DrmSessionTracker).
        val licenseLatencyMs: Long,
    ) : DrmSessionEvent

    data class SessionReleased(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
    ) : DrmSessionEvent

    data class SessionError(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val message: String,
        val detail: String?,
    ) : DrmSessionEvent
}

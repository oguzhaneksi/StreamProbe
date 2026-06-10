package com.streamprobe.sdk.model

import java.util.concurrent.atomic.AtomicLong

/** DRM session lifecycle event for the DRM timeline tab. Follows the [TrackSwitchEvent] pattern. */
sealed interface DrmSessionEvent {
    /** Monotonically increasing ID assigned at construction time; used as DiffUtil identity key. */
    val id: Long
    val timestampMs: Long
    val scheme: DrmScheme

    data class SessionAcquired(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val state: DrmSessionState,
        override val id: Long = nextId(),
    ) : DrmSessionEvent

    data class KeysLoaded(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        // Approximate: may inflate on key rotation (informational only — see Known Limitations in DrmSessionTracker).
        val licenseLatencyMs: Long,
        override val id: Long = nextId(),
    ) : DrmSessionEvent

    data class SessionReleased(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        override val id: Long = nextId(),
    ) : DrmSessionEvent

    data class SessionError(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val message: String,
        val detail: String?,
        override val id: Long = nextId(),
    ) : DrmSessionEvent

    companion object {
        private val counter = AtomicLong(0)

        fun nextId(): Long = counter.incrementAndGet()
    }
}

package com.streamprobe.sdk.model

/** DRM session lifecycle event for the DRM timeline tab. Follows the [TrackSwitchEvent] pattern. */
sealed interface DrmSessionEvent {
    /**
     * Monotonically increasing identity key used as the DiffUtil identity key.
     * Assigned by [com.streamprobe.sdk.internal.SessionStore] when the event is added
     * (single serialized writer); [UNASSIGNED_ID] until then.
     */
    val id: Long
    val timestampMs: Long
    val scheme: DrmScheme

    /** Returns a copy of this event with [id] stamped by the store. */
    fun withId(id: Long): DrmSessionEvent

    data class SessionAcquired(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val state: DrmSessionState,
        override val id: Long = UNASSIGNED_ID,
    ) : DrmSessionEvent {
        override fun withId(id: Long): DrmSessionEvent = copy(id = id)
    }

    data class KeysLoaded(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        // Approximate: may inflate on key rotation (informational only — see Known Limitations in DrmSessionTracker).
        val licenseLatencyMs: Long,
        override val id: Long = UNASSIGNED_ID,
    ) : DrmSessionEvent {
        override fun withId(id: Long): DrmSessionEvent = copy(id = id)
    }

    data class SessionReleased(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        override val id: Long = UNASSIGNED_ID,
    ) : DrmSessionEvent {
        override fun withId(id: Long): DrmSessionEvent = copy(id = id)
    }

    data class SessionError(
        override val timestampMs: Long,
        override val scheme: DrmScheme,
        val message: String,
        val detail: String?,
        override val id: Long = UNASSIGNED_ID,
    ) : DrmSessionEvent {
        override fun withId(id: Long): DrmSessionEvent = copy(id = id)
    }

    companion object {
        /** Sentinel [id] before the store stamps a stable, monotonic identity. */
        const val UNASSIGNED_ID: Long = 0L
    }
}

package com.streamprobe.sdk.model

enum class DrmSessionState {
    OPENING,
    OPENED,
    OPENED_WITH_KEYS,
    RELEASED,
    ERROR,

    /** Received an unrecognised DrmSession.State integer — safe fallback. */
    UNKNOWN,
}

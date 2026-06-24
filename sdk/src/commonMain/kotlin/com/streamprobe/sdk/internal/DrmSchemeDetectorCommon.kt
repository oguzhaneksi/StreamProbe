package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionState

/**
 * Platform-independent DRM scheme/state mapping. The Android [DrmSchemeDetector] resolves the
 * scheme from a Media3 timeline and delegates the pure mapping here; the iOS adapter can reuse the
 * same state mapping.
 *
 * Inputs are kept framework-neutral on purpose: a UUID *string* (rather than `java.util.UUID`) and
 * a raw state *int*, so this object references no Media3 or JVM types.
 */
internal object DrmSchemeDetectorCommon {
    // Canonical DRM system identifiers (lowercase, hyphenated) — matches Media3's C.*_UUID values.
    private const val WIDEVINE_UUID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY_UUID = "9a04f079-9840-4286-ab92-e65be0885f95"
    private const val CLEARKEY_UUID = "e2719d58-a985-b3c9-781a-b030af78d30e"

    // Mirrors Media3 DrmSession.State integer constants (stable public API). Validated against the
    // real constants by the Android DrmSchemeDetectorTest, which delegates here.
    private const val STATE_RELEASED = 0
    private const val STATE_ERROR = 1
    private const val STATE_OPENING = 2
    private const val STATE_OPENED = 3
    private const val STATE_OPENED_WITH_KEYS = 4

    fun mapUuidToScheme(uuidString: String): DrmScheme? =
        when (uuidString.lowercase()) {
            WIDEVINE_UUID -> DrmScheme.WIDEVINE
            PLAYREADY_UUID -> DrmScheme.PLAYREADY
            CLEARKEY_UUID -> DrmScheme.CLEARKEY
            else -> null
        }

    fun mapDrmState(state: Int): DrmSessionState =
        when (state) {
            STATE_OPENING -> DrmSessionState.OPENING
            STATE_OPENED -> DrmSessionState.OPENED
            STATE_OPENED_WITH_KEYS -> DrmSessionState.OPENED_WITH_KEYS
            STATE_RELEASED -> DrmSessionState.RELEASED
            STATE_ERROR -> DrmSessionState.ERROR
            else -> DrmSessionState.UNKNOWN
        }
}

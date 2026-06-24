package com.streamprobe.sdk.model

/** Live DRM summary displayed in the overlay summary row. */
data class DrmStatusInfo(
    val scheme: DrmScheme,
    val state: DrmSessionState,
    val lastLicenseLatencyMs: Long? = null,
)

package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.DrmStatusInfo

/** Formatting functions for DRM session data displayed in the overlay. */
internal object DrmFormatters {
    /** "Widevine · Keys Loaded · 312ms" or "Widevine · Opening" */
    fun formatDrmStatus(info: DrmStatusInfo?): String {
        if (info == null) return "—"
        val parts = mutableListOf(formatDrmScheme(info.scheme), formatDrmSessionState(info.state))
        info.lastLicenseLatencyMs?.let { parts += "${it}ms" }
        return parts.joinToString("  ·  ")
    }

    fun formatDrmScheme(scheme: DrmScheme): String =
        when (scheme) {
            DrmScheme.WIDEVINE -> "Widevine"
            DrmScheme.PLAYREADY -> "PlayReady"
            DrmScheme.CLEARKEY -> "ClearKey"
            DrmScheme.UNKNOWN -> "Unknown DRM"
        }

    fun formatDrmSchemeBadge(scheme: DrmScheme): String =
        when (scheme) {
            DrmScheme.WIDEVINE -> "WV"
            DrmScheme.PLAYREADY -> "PR"
            DrmScheme.CLEARKEY -> "CK"
            DrmScheme.UNKNOWN -> "DRM"
        }

    fun formatDrmSessionState(state: DrmSessionState): String =
        when (state) {
            DrmSessionState.OPENING -> "Opening"
            DrmSessionState.OPENED -> "Opened"
            DrmSessionState.OPENED_WITH_KEYS -> "Keys Loaded"
            DrmSessionState.RELEASED -> "Released"
            DrmSessionState.ERROR -> "Error"
            DrmSessionState.UNKNOWN -> "Unknown"
        }

    fun formatDrmEventLabel(event: DrmSessionEvent): String =
        when (event) {
            is DrmSessionEvent.SessionAcquired -> "Session Acquired (${formatDrmSessionState(event.state)})"
            is DrmSessionEvent.KeysLoaded -> "Keys Loaded"
            is DrmSessionEvent.SessionReleased -> "Session Released"
            is DrmSessionEvent.SessionError -> "Error: ${event.message}"
        }
}

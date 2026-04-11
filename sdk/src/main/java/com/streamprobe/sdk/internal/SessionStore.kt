package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe in-memory store for the current debug session.
 * The overlay reads from the exposed [StateFlow]s; interception
 * components write via the update methods.
 */
internal class SessionStore {

    private val _manifestInfo = MutableStateFlow<HlsManifestInfo?>(null)
    val manifestInfo: StateFlow<HlsManifestInfo?> = _manifestInfo.asStateFlow()

    private val _activeTrack = MutableStateFlow<ActiveTrackInfo?>(null)
    val activeTrack: StateFlow<ActiveTrackInfo?> = _activeTrack.asStateFlow()

    fun updateManifest(info: HlsManifestInfo) {
        _manifestInfo.value = info
    }

    fun updateActiveTrack(info: ActiveTrackInfo) {
        _activeTrack.value = info
    }

    fun clear() {
        _manifestInfo.value = null
        _activeTrack.value = null
    }
}

package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.AbrSwitchEvent
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.ManifestInfo
import com.streamprobe.sdk.model.SegmentMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe in-memory store for the current debug session.
 * The overlay reads from the exposed [StateFlow]s; interception
 * components write via the update methods.
 */
internal class SessionStore {

    private val _manifestInfo = MutableStateFlow<ManifestInfo?>(null)
    val manifestInfo: StateFlow<ManifestInfo?> = _manifestInfo.asStateFlow()

    private val _activeTrack = MutableStateFlow<ActiveTrackInfo?>(null)
    val activeTrack: StateFlow<ActiveTrackInfo?> = _activeTrack.asStateFlow()

    private val _segmentMetrics = MutableStateFlow<List<SegmentMetric>>(emptyList())
    val segmentMetrics: StateFlow<List<SegmentMetric>> = _segmentMetrics.asStateFlow()

    private val _latestSegmentMetric = MutableStateFlow<SegmentMetric?>(null)
    val latestSegmentMetric: StateFlow<SegmentMetric?> = _latestSegmentMetric.asStateFlow()

    private val _abrSwitchEvents = MutableStateFlow<List<AbrSwitchEvent>>(emptyList())
    val abrSwitchEvents: StateFlow<List<AbrSwitchEvent>> = _abrSwitchEvents.asStateFlow()

    fun updateManifest(info: ManifestInfo) {
        _manifestInfo.value = info
    }

    fun updateActiveTrack(info: ActiveTrackInfo) {
        _activeTrack.value = info
    }

    fun addSegmentMetric(metric: SegmentMetric) {
        _segmentMetrics.update { current ->
            if (current.size >= MAX_SEGMENT_METRICS) {
                current.drop(1) + metric
            } else {
                current + metric
            }
        }
        _latestSegmentMetric.value = metric
    }

    fun addAbrSwitchEvent(event: AbrSwitchEvent) {
        _abrSwitchEvents.update { current ->
            if (current.size >= MAX_ABR_EVENTS) current.drop(1) + event
            else current + event
        }
    }

    fun clear() {
        _manifestInfo.value = null
        _activeTrack.value = null
        _segmentMetrics.value = emptyList()
        _latestSegmentMetric.value = null
        _abrSwitchEvents.value = emptyList()
    }

    companion object {
        private const val MAX_SEGMENT_METRICS = 500
        private const val MAX_ABR_EVENTS = 200
    }
}

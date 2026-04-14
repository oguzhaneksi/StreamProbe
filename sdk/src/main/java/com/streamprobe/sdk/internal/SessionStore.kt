package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
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

    private val _manifestInfo = MutableStateFlow<HlsManifestInfo?>(null)
    val manifestInfo: StateFlow<HlsManifestInfo?> = _manifestInfo.asStateFlow()

    private val _activeTrack = MutableStateFlow<ActiveTrackInfo?>(null)
    val activeTrack: StateFlow<ActiveTrackInfo?> = _activeTrack.asStateFlow()

    private val _segmentMetrics = MutableStateFlow<List<SegmentMetric>>(emptyList())
    val segmentMetrics: StateFlow<List<SegmentMetric>> = _segmentMetrics.asStateFlow()

    private val _latestSegmentMetric = MutableStateFlow<SegmentMetric?>(null)
    val latestSegmentMetric: StateFlow<SegmentMetric?> = _latestSegmentMetric.asStateFlow()

    fun updateManifest(info: HlsManifestInfo) {
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

    fun clear() {
        _manifestInfo.value = null
        _activeTrack.value = null
        _segmentMetrics.value = emptyList()
        _latestSegmentMetric.value = null
    }

    companion object {
        private const val MAX_SEGMENT_METRICS = 500
    }
}

package com.streamprobe.sdk.internal

import androidx.annotation.VisibleForTesting
import com.streamprobe.sdk.model.AbrSwitchEvent
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.ManifestInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
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

    private val _playbackErrors = MutableStateFlow<List<PlaybackErrorEvent>>(emptyList())
    val playbackErrors: StateFlow<List<PlaybackErrorEvent>> = _playbackErrors.asStateFlow()

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

    fun addPlaybackError(event: PlaybackErrorEvent) {
        _playbackErrors.update { current ->
            val last = current.lastOrNull()
            val lastDrop = last?.categoryDetail as? ErrorDetail.DroppedFrames
            val incomingDrop = event.categoryDetail as? ErrorDetail.DroppedFrames
            val canMerge = event.category == ErrorCategory.DROPPED_FRAMES &&
                last?.category == ErrorCategory.DROPPED_FRAMES &&
                lastDrop != null && incomingDrop != null &&
                (event.timestampMs - lastDrop.lastUpdateMs) <= DROPPED_FRAMES_DEDUP_WINDOW_MS

            when {
                canMerge -> {
                    // canMerge guarantees last/lastDrop/incomingDrop are non-null
                    val totalFrames = lastDrop.totalFrames + incomingDrop.totalFrames
                    val newBurstCount = lastDrop.burstCount + 1
                    val merged = last.copy(
                        // timestampMs deliberately preserved — DiffUtil identity stays stable.
                        message = "$totalFrames frames dropped ($newBurstCount bursts)",
                        categoryDetail = ErrorDetail.DroppedFrames(
                            totalFrames = totalFrames,
                            burstCount = newBurstCount,
                            lastUpdateMs = event.timestampMs,
                        ),
                    )
                    current.dropLast(1) + merged
                }
                current.size >= MAX_PLAYBACK_ERRORS -> current.drop(1) + event
                else -> current + event
            }
        }
    }

    fun clearPlaybackErrors() {
        _playbackErrors.value = emptyList()
    }

    fun clear() {
        _manifestInfo.value = null
        _activeTrack.value = null
        _segmentMetrics.value = emptyList()
        _latestSegmentMetric.value = null
        _abrSwitchEvents.value = emptyList()
        _playbackErrors.value = emptyList()
    }

    companion object {
        private const val MAX_SEGMENT_METRICS = 500
        private const val MAX_ABR_EVENTS = 200
        private const val MAX_PLAYBACK_ERRORS = 200
        @VisibleForTesting
        internal const val DROPPED_FRAMES_DEDUP_WINDOW_MS = 5_000L
    }
}

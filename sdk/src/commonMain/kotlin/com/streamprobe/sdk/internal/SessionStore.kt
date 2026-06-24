package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackListInfo
import com.streamprobe.sdk.model.TrackSwitchEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe in-memory store for the current debug session — the central hub both platform
 * adapters write to and the overlay reads from via the exposed [StateFlow]s. On Android,
 * [PlayerInterceptor] writes directly; on iOS, the Swift `AVPlayerProbe` writes via
 * `DiagnosticsSink` → `ProbeCore` → this store.
 *
 * Bounded to keep memory flat: 500 segment metrics, 200 each of switch events / errors / DRM events.
 * Consecutive [ErrorCategory.DROPPED_FRAMES] within [DROPPED_FRAMES_DEDUP_WINDOW_MS] (5 s) merge into
 * one entry with a stable [PlaybackErrorEvent.timestampMs] so DiffUtil keeps row identity.
 */
internal class SessionStore {
    private val _trackListInfo = MutableStateFlow<TrackListInfo?>(null)
    val trackListInfo: StateFlow<TrackListInfo?> = _trackListInfo.asStateFlow()

    private val _activeTrack = MutableStateFlow<ActiveTrackInfo?>(null)
    val activeTrack: StateFlow<ActiveTrackInfo?> = _activeTrack.asStateFlow()

    private val _activeAudioTrack = MutableStateFlow<AudioTrackInfo?>(null)
    val activeAudioTrack: StateFlow<AudioTrackInfo?> = _activeAudioTrack.asStateFlow()

    private val _activeSubtitleTrack = MutableStateFlow<SubtitleTrackInfo?>(null)
    val activeSubtitleTrack: StateFlow<SubtitleTrackInfo?> = _activeSubtitleTrack.asStateFlow()

    private val _segmentMetrics = MutableStateFlow<List<SegmentMetric>>(emptyList())
    val segmentMetrics: StateFlow<List<SegmentMetric>> = _segmentMetrics.asStateFlow()

    private val _latestSegmentMetric = MutableStateFlow<SegmentMetric?>(null)
    val latestSegmentMetric: StateFlow<SegmentMetric?> = _latestSegmentMetric.asStateFlow()

    private val _trackSwitchEvents = MutableStateFlow<List<TrackSwitchEvent>>(emptyList())
    val trackSwitchEvents: StateFlow<List<TrackSwitchEvent>> = _trackSwitchEvents.asStateFlow()

    private val _playbackErrors = MutableStateFlow<List<PlaybackErrorEvent>>(emptyList())
    val playbackErrors: StateFlow<List<PlaybackErrorEvent>> = _playbackErrors.asStateFlow()

    private val _drmSessionEvents = MutableStateFlow<List<DrmSessionEvent>>(emptyList())
    val drmSessionEvents: StateFlow<List<DrmSessionEvent>> = _drmSessionEvents.asStateFlow()

    private val _currentDrmState = MutableStateFlow<DrmStatusInfo?>(null)
    val currentDrmState: StateFlow<DrmStatusInfo?> = _currentDrmState.asStateFlow()

    // Monotonic id generator for DRM events. Single serialized writer (analytics thread); the id is
    // computed before update {} so a CAS retry of the loop never double-increments it.
    private var drmEventIdCounter = DrmSessionEvent.UNASSIGNED_ID

    fun updateTrackList(info: TrackListInfo) {
        _trackListInfo.value = info
    }

    fun updateActiveTrack(info: ActiveTrackInfo) {
        _activeTrack.value = info
    }

    fun updateActiveAudioTrack(info: AudioTrackInfo?) {
        _activeAudioTrack.value = info
    }

    fun updateActiveSubtitleTrack(info: SubtitleTrackInfo?) {
        _activeSubtitleTrack.value = info
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

    fun addTrackSwitchEvent(event: TrackSwitchEvent) {
        _trackSwitchEvents.update { current ->
            if (current.size >= MAX_TRACK_SWITCH_EVENTS) {
                current.drop(1) + event
            } else {
                current + event
            }
        }
    }

    fun addPlaybackError(event: PlaybackErrorEvent) {
        _playbackErrors.update { current ->
            val last = current.lastOrNull()
            val lastDrop = last?.categoryDetail as? ErrorDetail.DroppedFrames
            val incomingDrop = event.categoryDetail as? ErrorDetail.DroppedFrames
            if (event.category == ErrorCategory.DROPPED_FRAMES && last != null) {
                if (last.category == ErrorCategory.DROPPED_FRAMES && lastDrop != null && incomingDrop != null) {
                    val diff = event.timestampMs - lastDrop.lastUpdateMs
                    if (diff in 0..DROPPED_FRAMES_DEDUP_WINDOW_MS) {
                        val totalFrames = lastDrop.totalFrames + incomingDrop.totalFrames
                        val newBurstCount = lastDrop.burstCount + 1
                        val merged =
                            last.copy(
                                // timestampMs deliberately preserved — DiffUtil identity stays stable.
                                message = "$totalFrames frames dropped ($newBurstCount bursts)",
                                categoryDetail =
                                    ErrorDetail.DroppedFrames(
                                        totalFrames = totalFrames,
                                        burstCount = newBurstCount,
                                        lastUpdateMs = event.timestampMs,
                                    ),
                            )
                        return@update current.dropLast(1) + merged
                    }
                }
            }

            if (current.size >= MAX_PLAYBACK_ERRORS) current.drop(1) + event else current + event
        }
    }

    fun clearPlaybackErrors() {
        _playbackErrors.value = emptyList()
    }

    fun addDrmSessionEvent(event: DrmSessionEvent) {
        val stamped = event.withId(++drmEventIdCounter)
        _drmSessionEvents.update { current ->
            if (current.size >= MAX_DRM_EVENTS) current.drop(1) + stamped else current + stamped
        }
    }

    fun updateDrmState(info: DrmStatusInfo?) {
        _currentDrmState.value = info
    }

    fun clear() {
        _trackListInfo.value = null
        _activeTrack.value = null
        _activeAudioTrack.value = null
        _activeSubtitleTrack.value = null
        _segmentMetrics.value = emptyList()
        _latestSegmentMetric.value = null
        _trackSwitchEvents.value = emptyList()
        _playbackErrors.value = emptyList()
        _drmSessionEvents.value = emptyList()
        _currentDrmState.value = null
        drmEventIdCounter = DrmSessionEvent.UNASSIGNED_ID
    }

    companion object {
        private const val MAX_SEGMENT_METRICS = 500
        internal const val MAX_TRACK_SWITCH_EVENTS = 200
        private const val MAX_PLAYBACK_ERRORS = 200
        internal const val MAX_DRM_EVENTS = 200
        internal const val DROPPED_FRAMES_DEDUP_WINDOW_MS = 5_000L
    }
}

package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackListInfo
import com.streamprobe.sdk.model.TrackSwitchEvent

/**
 * Narrow, write-only surface the Swift `StreamProbeIOS` probe uses to feed diagnostics into the
 * Core. Implemented by [ProbeCore], which delegates each call to the internal [SessionStore].
 * Read access and the rendered view-state are exposed separately via `ProbeCore.presenter`.
 */
public interface DiagnosticsSink {
    public fun updateTrackList(info: TrackListInfo)

    public fun updateActiveTrack(info: ActiveTrackInfo)

    public fun updateActiveAudioTrack(info: AudioTrackInfo?)

    public fun updateActiveSubtitleTrack(info: SubtitleTrackInfo?)

    public fun addSegmentMetric(metric: SegmentMetric)

    public fun addTrackSwitchEvent(event: TrackSwitchEvent)

    public fun addPlaybackError(event: PlaybackErrorEvent)

    public fun addDrmSessionEvent(event: DrmSessionEvent)

    public fun updateDrmState(info: DrmStatusInfo?)

    public fun clearPlaybackErrors()

    public fun clear()
}

package com.streamprobe.sdk.internal.presenter

import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackSwitchEvent
import com.streamprobe.sdk.model.VariantInfo

/** The currently-selected overlay tab. Owned by [OverlayPresenter], preserved across rebuilds. */
public enum class ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }

/**
 * A single row in the unified Tracks list (video variants, audio tracks, subtitles).
 * Carries the raw model (not a pre-formatted string) so the platform renderer can apply selection
 * styling and exact row formatting; the presenter owns only the section *assembly* and ordering.
 * Raw is also required because [SubtitleTrackInfo.isSameRenditionAs] DiffUtil identity is
 * non-transitive (nullable-label rule) and cannot be reduced to a single string key.
 */
public sealed interface OverlayRow {
    public data class SectionHeader(
        val title: String,
    ) : OverlayRow

    public data class Video(
        val info: VariantInfo,
    ) : OverlayRow

    public data class Audio(
        val info: AudioTrackInfo,
    ) : OverlayRow

    public data class Subtitle(
        val info: SubtitleTrackInfo,
    ) : OverlayRow
}

/** Pre-formatted state for the header error-indicator pill. Null when there are no errors. */
public data class ErrorIndicatorState(
    val text: String,
    val contentDescription: String,
)

/** Pre-formatted strings for the always-visible stat sections + DRM summary visibility. */
public data class OverlayStatsState(
    val activeTrackText: String,
    val activeAudioText: String,
    val activeSubtitleText: String,
    val latestSegmentText: String,
    val cdnStatusText: String,
    val drmVisible: Boolean,
    val drmStatusText: String,
)

/** Raw model lists backing the five list tabs; the renderer binds them to per-tab adapters. */
public data class OverlayListsState(
    val renditionRows: List<OverlayRow>,
    val segments: List<SegmentMetric>,
    val switches: List<TrackSwitchEvent>,
    val drmEvents: List<DrmSessionEvent>,
    val errors: List<PlaybackErrorEvent>,
)

/**
 * Render-ready snapshot of the entire overlay. [OverlayPresenter] emits one of these on every
 * [com.streamprobe.sdk.internal.SessionStore] change or UI intent; the platform renderer maps it
 * to views with a single `collect`.
 *
 * Stat/header fields are grouped into [OverlayStatsState] / [OverlayListsState] sub-structs to keep
 * this top-level constructor under detekt's `LongParameterList` threshold of 8. Header/stat fields
 * are pre-formatted; timeline lists + Tracks rows stay raw and are rendered per-platform.
 */
public data class OverlayViewState(
    val mode: ViewMode,
    val isCollapsed: Boolean,
    val stats: OverlayStatsState,
    val lists: OverlayListsState,
    val errorIndicator: ErrorIndicatorState?,
    val isErrorsMode: Boolean,
    val errorsTitle: String,
)

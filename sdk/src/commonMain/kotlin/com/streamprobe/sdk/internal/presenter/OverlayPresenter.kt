package com.streamprobe.sdk.internal.presenter

import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.internal.overlay.CdnFormatters
import com.streamprobe.sdk.internal.overlay.DrmFormatters
import com.streamprobe.sdk.internal.overlay.SegmentFormatters
import com.streamprobe.sdk.internal.overlay.TrackFormatters
import com.streamprobe.sdk.model.TrackListInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Platform-independent overlay logic: owns the [ViewMode] state machine, collapse state, DRM
 * auto-fallback, the error counter and all header-string formatting, and assembles the Tracks
 * rendition rows. It consumes the [SessionStore] [StateFlow]s plus UI-intent calls and emits one
 * render-ready [OverlayViewState] on every change.
 *
 * The mutable UI-state vars ([viewMode], [previousViewMode], [isCollapsed], [renditionRows]) are
 * confined to a single dispatcher: [start]'s collectors and the intent methods are all invoked on
 * the renderer's Main thread (Android) or the single test dispatcher (commonTest), so no
 * synchronization is required — mirroring the original `OverlayManager`.
 */
public class OverlayPresenter internal constructor(
    private val sessionStore: SessionStore,
) {
    private var viewMode = ViewMode.TRACKS
    private var previousViewMode = ViewMode.TRACKS
    private var isCollapsed = false

    // Cached Tracks rows. Updated only when trackListInfo is non-null, so a null-yielding clear()
    // leaves the last assembled list on screen (matches the original observeTrackListInfo).
    private var renditionRows: List<OverlayRow> = emptyList()

    private val _viewState = MutableStateFlow(computeState())
    public val viewState: StateFlow<OverlayViewState> = _viewState.asStateFlow()

    /** Launches collectors that fold [SessionStore] changes into [viewState] on [scope]. */
    internal fun start(scope: CoroutineScope) {
        scope.launch { sessionStore.activeTrack.collect { emit() } }
        scope.launch { sessionStore.activeAudioTrack.collect { emit() } }
        scope.launch { sessionStore.activeSubtitleTrack.collect { emit() } }
        scope.launch { sessionStore.latestSegmentMetric.collect { emit() } }
        scope.launch { sessionStore.segmentMetrics.collect { emit() } }
        scope.launch { sessionStore.trackSwitchEvents.collect { emit() } }
        scope.launch { sessionStore.currentDrmState.collect { emit() } }
        scope.launch { sessionStore.playbackErrors.collect { emit() } }
        scope.launch {
            sessionStore.trackListInfo.collect { info ->
                if (info != null) renditionRows = assembleRows(info)
                emit()
            }
        }
        scope.launch {
            sessionStore.drmSessionEvents.collect { events ->
                if (events.isEmpty()) {
                    if (viewMode == ViewMode.DRM) viewMode = ViewMode.TRACKS
                    if (previousViewMode == ViewMode.DRM) previousViewMode = ViewMode.TRACKS
                }
                emit()
            }
        }
    }

    public fun onChipSelected(mode: ViewMode) {
        viewMode = mode
        emit()
    }

    public fun onCollapseToggled() {
        isCollapsed = !isCollapsed
        emit()
    }

    public fun onErrorIndicatorTapped() {
        if (isCollapsed) isCollapsed = false
        if (viewMode != ViewMode.ERRORS) {
            previousViewMode = viewMode
            viewMode = ViewMode.ERRORS
        }
        emit()
    }

    public fun onBackPressed() {
        viewMode = previousViewMode
        emit()
    }

    public fun onClearErrorsClicked() {
        sessionStore.clearPlaybackErrors()
    }

    private fun emit() {
        _viewState.value = computeState()
    }

    private fun assembleRows(info: TrackListInfo): List<OverlayRow> =
        buildList {
            if (info.variants.isNotEmpty()) {
                add(OverlayRow.SectionHeader("VIDEO"))
                info.variants.forEach { add(OverlayRow.Video(it)) }
            }
            if (info.audioTracks.isNotEmpty()) {
                add(OverlayRow.SectionHeader("AUDIO"))
                info.audioTracks.forEach { add(OverlayRow.Audio(it)) }
            }
            if (info.subtitleTracks.isNotEmpty()) {
                add(OverlayRow.SectionHeader("SUBTITLES"))
                info.subtitleTracks.forEach { add(OverlayRow.Subtitle(it)) }
            }
        }

    private fun computeState(): OverlayViewState {
        val errors = sessionStore.playbackErrors.value
        val drmEvents = sessionStore.drmSessionEvents.value
        val latestSegment = sessionStore.latestSegmentMetric.value
        return OverlayViewState(
            mode = viewMode,
            isCollapsed = isCollapsed,
            stats =
                OverlayStatsState(
                    activeTrackText = TrackFormatters.formatActiveTrack(sessionStore.activeTrack.value),
                    activeAudioText = TrackFormatters.formatActiveAudio(sessionStore.activeAudioTrack.value),
                    activeSubtitleText = TrackFormatters.formatActiveSubtitle(sessionStore.activeSubtitleTrack.value),
                    latestSegmentText = SegmentFormatters.formatSegmentMetric(latestSegment),
                    cdnStatusText = CdnFormatters.formatCdnStatus(latestSegment?.cdnInfo),
                    drmVisible = drmEvents.isNotEmpty(),
                    drmStatusText = DrmFormatters.formatDrmStatus(sessionStore.currentDrmState.value),
                ),
            lists =
                OverlayListsState(
                    renditionRows = renditionRows,
                    segments = sessionStore.segmentMetrics.value,
                    switches = sessionStore.trackSwitchEvents.value,
                    drmEvents = drmEvents,
                    errors = errors,
                ),
            errorIndicator =
                if (errors.isEmpty()) {
                    null
                } else {
                    ErrorIndicatorState(
                        text = "⚠ ${errors.size}",
                        contentDescription = "${errors.size} background errors. Tap to view.",
                    )
                },
            isErrorsMode = viewMode == ViewMode.ERRORS,
            errorsTitle = "Errors (${errors.size})",
        )
    }
}

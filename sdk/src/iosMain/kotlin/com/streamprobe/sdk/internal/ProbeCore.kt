package com.streamprobe.sdk.internal

import com.streamprobe.sdk.internal.presenter.OverlayPresenter
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmStatusInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.TrackListInfo
import com.streamprobe.sdk.model.TrackSwitchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * iOS-only Core facade consumed by the Swift `StreamProbeIOS` layer.
 *
 * Bundles the (internal) [SessionStore], the common [OverlayPresenter], and the presenter
 * collector lifecycle behind a narrow [DiagnosticsSink] write surface. The Swift probe writes
 * diagnostics through the sink methods; the Swift overlay observes [presenter] (via SKIE) and
 * drives [start]/[stop].
 *
 * Lives in `iosMain` (not `commonMain`): all source sets share one module, so this reaches
 * [SessionStore]'s `internal` writes directly while keeping the store off the public surface.
 *
 * **Thread-safety:** not thread-safe; all methods (including [DiagnosticsSink] writes, [start],
 * and [stop]) must be called from the main thread (consistent with [Dispatchers.Main] scope).
 */
public class ProbeCore : DiagnosticsSink {
    private val store = SessionStore()

    /** Exposed internally so `iosTest` can assert store contents. */
    internal val sessionStore: SessionStore get() = store

    /** Common presenter; observe [OverlayPresenter.viewState] via SKIE to drive the overlay. */
    public val presenter: OverlayPresenter = OverlayPresenter(store)

    private var presenterScope: CoroutineScope? = null

    /** Starts the presenter collectors on the main dispatcher. Idempotent. */
    public fun start() {
        if (presenterScope != null) return
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        presenterScope = scope
        presenter.start(scope)
    }

    /** Cancels the presenter collectors so live updates freeze. Idempotent. */
    public fun stop() {
        presenterScope?.cancel()
        presenterScope = null
    }

    override fun updateTrackList(info: TrackListInfo) {
        store.updateTrackList(info)
    }

    override fun updateActiveTrack(info: ActiveTrackInfo) {
        store.updateActiveTrack(info)
    }

    override fun updateActiveAudioTrack(info: AudioTrackInfo?) {
        store.updateActiveAudioTrack(info)
    }

    override fun updateActiveSubtitleTrack(info: SubtitleTrackInfo?) {
        store.updateActiveSubtitleTrack(info)
    }

    override fun addSegmentMetric(metric: SegmentMetric) {
        store.addSegmentMetric(metric)
    }

    override fun addTrackSwitchEvent(event: TrackSwitchEvent) {
        store.addTrackSwitchEvent(event)
    }

    override fun addPlaybackError(event: PlaybackErrorEvent) {
        store.addPlaybackError(event)
    }

    override fun addDrmSessionEvent(event: DrmSessionEvent) {
        store.addDrmSessionEvent(event)
    }

    override fun updateDrmState(info: DrmStatusInfo?) {
        store.updateDrmState(info)
    }

    override fun clearPlaybackErrors() {
        store.clearPlaybackErrors()
    }

    override fun clear() {
        store.clear()
    }
}

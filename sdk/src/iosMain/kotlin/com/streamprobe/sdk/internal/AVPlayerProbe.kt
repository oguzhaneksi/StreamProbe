package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.TrackSwitchEvent
import com.streamprobe.sdk.model.TracksSnapshot
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetVariant
import platform.AVFoundation.AVKeyValueStatusLoaded
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemNewAccessLogEntryNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.accessLog
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.errorLog
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.variants
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs

/**
 * iOS analogue of `PlayerInterceptor`: observes an `AVPlayer` and writes the existing SDK models
 * into the shared [SessionStore]. Headless — no UI.
 *
 * - Track listing (3.3): async `variants`-key load → `updateTrackList` (parsing the HLS master is
 *   enough, so it works before `readyToPlay` and without playback progressing — there is no
 *   KVO/time-observer because playback does not progress when headless). Stale-closure guard: the
 *   load callback only publishes when `statusOfValueForKey(...) == Loaded` **and** the live
 *   `this.player?.currentItem?.asset === asset` (the live player, not a captured local), so a stale
 *   or re-attached closure is rejected.
 * - Segment metrics / bitrate switches / dropped frames (3.4–3.6): each new `AVPlayerItemAccessLog`
 *   entry is mapped to a coarse `SegmentMetric`, an adaptive `VideoSwitch` (on `indicatedBitrate`
 *   change across entries), and a `DROPPED_FRAMES` error. The *current* (still-mutating) log entry is
 *   processed one notification later (`accessLog()?.events?.dropLast(1)`), once it is finalized, so
 *   only complete (non-mutating) stats are read; the last entry is picked up by the next notification.
 * - Errors (3.6): each new `AVPlayerItemErrorLog` entry → `LOAD_ERROR`.
 * - Active track resolution: `emitBitrateSwitch` looks up the closest variant in the known list to
 *   provide resolution/codecs in `ActiveTrackInfo` (access log alone only carries bitrate). It also
 *   marks `isSelected` on the matching `VariantInfo` in the stored snapshot.
 * - Audio selection: `publishTracks` reads `currentMediaSelection` to detect the initially-selected
 *   audio rendition. `refreshAudioSelection` is called from `onNewAccessLogEntry` as a fallback for
 *   streams where the selection is made after the variants key loads.
 *
 * Observers are registered via `NSNotificationCenter` scoped to [AVPlayer.currentItem] (the object
 * filter) so sibling-player / `AVQueuePlayer` callbacks cannot fire this probe's handlers. All
 * callbacks are delivered on the main queue. [nowMs] uses `CACurrentMediaTime()` plus an epoch offset
 * computed once at [attach] for monotonic timestamps (avoids wall-clock non-monotonicity).
 * [attach] calls `store.clear()`; the [NSObjectProtocol] observer tokens are removed in [detach].
 *
 * **Known iOS limitation:** Observers and [finalizedAccessEntries] / [processedErrorEntries] counters
 * are scoped to the [AVPlayer.currentItem] at [attach] time. `AVQueuePlayer` item changes are not
 * detected — metrics from subsequent items continue to accumulate but the counters may skip entries.
 * Full `AVQueuePlayer` support requires re-attaching on `AVPlayerItem` replacement (Phase 4+).
 */
internal class AVPlayerProbe(
    private val store: SessionStore,
) {
    private var player: AVPlayer? = null
    private var active = false
    private val observers = mutableListOf<NSObjectProtocol>()

    private var finalizedAccessEntries = 0
    private var processedErrorEntries = 0
    private var lastIndicatedBitrate = -1.0

    // Monotonic clock anchor: computed once per attach() so nowMs() is wall-clock but never goes backward.
    private var epochOffsetMs: Long = 0L

    @OptIn(ExperimentalForeignApi::class)
    fun attach(player: AVPlayer) {
        detach()
        this.player = player
        active = true
        epochOffsetMs = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong() -
            (CACurrentMediaTime() * MILLIS_PER_SECOND).toLong()
        registerLogObservers()
        val asset = player.currentItem?.asset as? AVURLAsset ?: return
        asset.loadValuesAsynchronouslyForKeys(listOf(VARIANTS_KEY)) {
            dispatch_async(dispatch_get_main_queue()) {
                // Guard: probe still active AND this is still the current asset (item may have changed
                // or a re-attach may have installed a different player entirely).
                if (active && this.player?.currentItem?.asset == asset) {
                    val status = asset.statusOfValueForKey(VARIANTS_KEY, null)
                    if (status == AVKeyValueStatusLoaded) publishTracks(asset)
                }
            }
        }
    }

    fun detach() {
        // Flush any unprocessed entries before tearing down (covers the manual-stop case; the
        // natural-end case is already handled by the AVPlayerItemDidPlayToEndTimeNotification observer).
        onNewAccessLogEntry(flushAll = true)
        active = false
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        player = null
        finalizedAccessEntries = 0
        processedErrorEntries = 0
        lastIndicatedBitrate = -1.0
        epochOffsetMs = 0L
    }

    private fun registerLogObservers() {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        // Scope observers to the current item so multi-player or AVQueuePlayer siblings don't
        // trigger this probe's callbacks. If currentItem is null, falls back to null (fires for all).
        val currentItem = player?.currentItem
        observers +=
            center.addObserverForName(AVPlayerItemNewAccessLogEntryNotification, currentItem, main) { _ ->
                // AVPlayerItemNewAccessLogEntryNotification fires when a new entry is STARTED, meaning
                // the last entry in events[] is still accumulating. Only process entries before it.
                if (active) onNewAccessLogEntry(flushAll = false)
            }
        observers +=
            center.addObserverForName(AVPlayerItemNewErrorLogEntryNotification, currentItem, main) { _ ->
                if (active) onNewErrorLogEntry()
            }
        observers +=
            center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, currentItem, main) { _ ->
                // Playback ended naturally — the last access-log entry is now final; flush it.
                if (active) onNewAccessLogEntry(flushAll = true)
            }
    }

    private fun publishTracks(asset: AVURLAsset) {
        val variants = asset.variants.mapNotNull { (it as? AVAssetVariant)?.let(::mapVariant) }
        if (variants.isEmpty()) return
        val mediaSelection = player?.currentItem?.currentMediaSelection
        val audioGroup = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
        val selectedAudio = audioGroup?.let { mediaSelection?.selectedMediaOptionInMediaSelectionGroup(it) }
        val audioTracks =
            audioGroup
                ?.options
                ?.filterIsInstance<AVMediaSelectionOption>()
                .orEmpty()
                .map { option -> mapAudioOption(option).copy(isSelected = option == selectedAudio) }
        val subtitleGroup = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
        val selectedSubtitle = subtitleGroup?.let { mediaSelection?.selectedMediaOptionInMediaSelectionGroup(it) }
        val subtitleTracks =
            subtitleGroup
                ?.options
                ?.filterIsInstance<AVMediaSelectionOption>()
                .orEmpty()
                .map { option -> mapLegibleOption(option).copy(isSelected = option == selectedSubtitle) }
        store.updateTrackList(TracksSnapshot(variants, audioTracks, subtitleTracks))
        store.updateActiveAudioTrack(audioTracks.find { it.isSelected })
        store.updateActiveSubtitleTrack(subtitleTracks.find { it.isSelected })
    }

    private fun onNewAccessLogEntry(flushAll: Boolean = false) {
        val events = player?.currentItem?.accessLog()?.events ?: return
        // When flushAll=false (new-entry notification): the just-added last entry is still
        // accumulating, so only entries before it are final. When flushAll=true (end-of-playback
        // or detach): no further entries will arrive, so the last entry is also final.
        val finalized = if (flushAll) events.size else events.size - 1
        while (finalizedAccessEntries < finalized) {
            (events[finalizedAccessEntries] as? AVPlayerItemAccessLogEvent)?.let(::processAccessEntry)
            finalizedAccessEntries++
        }
        // Access log firing means the player is actively playing — a reliable point to detect the
        // selected audio rendition if publishTracks fired before the player made its initial selection.
        if (store.activeAudioTrack.value == null) refreshAudioSelection()
    }

    private fun processAccessEntry(event: AVPlayerItemAccessLogEvent) {
        // Use the entry's own start date as requestTimestampMs — equivalent to Android's
        // System.currentTimeMillis() - loadEventInfo.loadDurationMs. Falls back to nowMs() when
        // playbackStartDate is nil (which AVFoundation documents as possible for the first entry).
        val requestTimestampMs =
            event.playbackStartDate
                ?.let { (it.timeIntervalSince1970 * MILLIS_PER_SECOND).toLong() }
                ?: nowMs()
        store.addSegmentMetric(
            accessLogSegmentMetric(
                nowMs = requestTimestampMs,
                uri = event.URI,
                sizeBytes = event.numberOfBytesTransferred,
                observedBitrate = event.observedBitrate,
                transferDurationSeconds = event.transferDuration,
            ),
        )
        emitBitrateSwitch(requestTimestampMs, event.indicatedBitrate)
        if (event.numberOfDroppedVideoFrames >= DROPPED_FRAME_THRESHOLD) {
            store.addPlaybackError(droppedFramesError(requestTimestampMs, event.numberOfDroppedVideoFrames))
        }
    }

    private fun emitBitrateSwitch(
        nowMs: Long,
        indicatedBitrate: Double,
    ) {
        if (!isBitrateSwitch(lastIndicatedBitrate, indicatedBitrate)) return
        val newTrack = resolveActiveTrack(indicatedBitrate)
        val previousTrack = store.activeTrack.value
        val reason = if (previousTrack == null) SwitchReason.INITIAL else SwitchReason.ADAPTIVE
        store.addTrackSwitchEvent(
            TrackSwitchEvent.VideoSwitch(
                timestampMs = nowMs,
                bufferDurationMs = 0,
                reason = reason,
                previousTrack = previousTrack,
                newTrack = newTrack,
            ),
        )
        store.updateActiveTrack(newTrack)
        updateVariantSelection(newTrack.bitrate)
        lastIndicatedBitrate = indicatedBitrate
    }

    /**
     * Looks up the closest-bitrate variant in the known track list, providing resolution and codecs
     * that the access log's `indicatedBitrate` alone cannot supply. Falls back to a bitrate-only
     * [ActiveTrackInfo] when no track list has been published yet.
     */
    private fun resolveActiveTrack(indicatedBitrate: Double): ActiveTrackInfo {
        val bitrate = indicatedBitrate.toInt()
        val variant =
            store.trackListInfo.value
                ?.variants
                ?.minByOrNull { abs(it.bitrate - bitrate) }
        return if (variant != null) {
            ActiveTrackInfo(
                bitrate = variant.bitrate,
                width = variant.width,
                height = variant.height,
                codecs = variant.codecs,
                id = variant.id,
            )
        } else {
            activeTrackFromIndicatedBitrate(indicatedBitrate)
        }
    }

    /** Marks the variant closest in bitrate to [selectedBitrate] as selected in the stored snapshot. */
    private fun updateVariantSelection(selectedBitrate: Int) {
        val snapshot = store.trackListInfo.value ?: return
        val idx =
            snapshot.variants.indices
                .minByOrNull { i -> abs(snapshot.variants[i].bitrate - selectedBitrate) } ?: return
        val updated = snapshot.variants.mapIndexed { i, v -> v.copy(isSelected = i == idx) }
        store.updateTrackList(TracksSnapshot(updated, snapshot.audioTracks, snapshot.subtitleTracks))
    }

    /**
     * Queries [AVPlayerItem.currentMediaSelection] to detect which audio rendition is playing and
     * updates [SessionStore] with the result. Called lazily from [onNewAccessLogEntry] for streams
     * where the player makes its audio selection after the variants key has loaded.
     */
    private fun refreshAudioSelection() {
        val currentItem = player?.currentItem ?: return
        val asset = currentItem.asset as? AVURLAsset ?: return
        val snapshot = store.trackListInfo.value ?: return
        val group = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) ?: return
        val selected = currentItem.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(group) ?: return
        val options = group.options.filterIsInstance<AVMediaSelectionOption>()
        val idx = options.indexOfFirst { it == selected }
        if (idx < 0 || snapshot.audioTracks.getOrNull(idx)?.isSelected == true) return
        val updatedAudio = snapshot.audioTracks.mapIndexed { i, t -> t.copy(isSelected = i == idx) }
        store.updateTrackList(TracksSnapshot(snapshot.variants, updatedAudio, snapshot.subtitleTracks))
        store.updateActiveAudioTrack(updatedAudio.find { it.isSelected })
    }

    private fun onNewErrorLogEntry() {
        val events = player?.currentItem?.errorLog()?.events ?: return
        while (processedErrorEntries < events.size) {
            (events[processedErrorEntries] as? AVPlayerItemErrorLogEvent)?.let { event ->
                store.addPlaybackError(
                    loadError(
                        nowMs = nowMs(),
                        errorDomain = event.errorDomain,
                        statusCode = event.errorStatusCode,
                        uri = event.URI,
                        comment = event.errorComment,
                    ),
                )
            }
            processedErrorEntries++
        }
    }

    // Monotonic wall-clock: CACurrentMediaTime() is mach_absolute_time-based and never goes backward.
    // epochOffsetMs bridges it to epoch time (set once per attach so NTP adjustments don't affect
    // relative timestamps within a session).
    private fun nowMs(): Long = (CACurrentMediaTime() * MILLIS_PER_SECOND).toLong() + epochOffsetMs

    private companion object {
        const val VARIANTS_KEY = "variants"
        const val DROPPED_FRAME_THRESHOLD = 3
    }
}

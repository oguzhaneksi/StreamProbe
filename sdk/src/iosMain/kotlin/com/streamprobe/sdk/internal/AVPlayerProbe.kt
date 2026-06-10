package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.TrackSwitchEvent
import com.streamprobe.sdk.model.TracksSnapshot
import platform.AVFoundation.AVAssetVariant
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemNewAccessLogEntryNotification
import platform.AVFoundation.AVPlayerItemNewErrorLogEntryNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.accessLog
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.errorLog
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.variants
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS analogue of `PlayerInterceptor`: observes an `AVPlayer` and writes the existing SDK models
 * into the shared [SessionStore]. Headless — no UI.
 *
 * - Track listing (3.3): async `variants`-key load → `updateTrackList` (parsing the HLS master is
 *   enough, so it works before `readyToPlay` and without playback progressing).
 * - Segment metrics / bitrate switches / dropped frames (3.4–3.6): each new `AVPlayerItemAccessLog`
 *   entry is mapped to a coarse `SegmentMetric`, an adaptive `VideoSwitch` (on indicated-bitrate
 *   change), and a `DROPPED_FRAMES` error. The *current* (still-mutating) log entry is processed one
 *   notification later, once it is finalized, so its stats are complete.
 * - Errors (3.6): each new `AVPlayerItemErrorLog` entry → `LOAD_ERROR`.
 *
 * All callbacks are delivered on the main queue.
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
    private var lastActiveTrack: ActiveTrackInfo? = null

    fun attach(player: AVPlayer) {
        detach()
        this.player = player
        active = true
        registerLogObservers()
        val asset = player.currentItem?.asset as? AVURLAsset ?: return
        asset.loadValuesAsynchronouslyForKeys(listOf(VARIANTS_KEY)) {
            dispatch_async(dispatch_get_main_queue()) {
                if (active) publishTracks(asset)
            }
        }
    }

    fun detach() {
        active = false
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        player = null
        finalizedAccessEntries = 0
        processedErrorEntries = 0
        lastIndicatedBitrate = -1.0
        lastActiveTrack = null
    }

    private fun registerLogObservers() {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers +=
            center.addObserverForName(AVPlayerItemNewAccessLogEntryNotification, null, main) { _ ->
                if (active) onNewAccessLogEntry()
            }
        observers +=
            center.addObserverForName(AVPlayerItemNewErrorLogEntryNotification, null, main) { _ ->
                if (active) onNewErrorLogEntry()
            }
    }

    private fun publishTracks(asset: AVURLAsset) {
        val variants = asset.variants.mapNotNull { (it as? AVAssetVariant)?.let(::mapVariant) }
        if (variants.isEmpty()) return
        store.updateTrackList(
            TracksSnapshot(
                variants = variants,
                audioTracks = optionsFor(asset, AVMediaCharacteristicAudible).map(::mapAudioOption),
                subtitleTracks = optionsFor(asset, AVMediaCharacteristicLegible).map(::mapLegibleOption),
            ),
        )
    }

    private fun optionsFor(
        asset: AVURLAsset,
        characteristic: String?,
    ): List<AVMediaSelectionOption> {
        val mediaCharacteristic = characteristic ?: return emptyList()
        val group = asset.mediaSelectionGroupForMediaCharacteristic(mediaCharacteristic)
        return group?.options?.mapNotNull { it as? AVMediaSelectionOption }.orEmpty()
    }

    private fun onNewAccessLogEntry() {
        val events = player?.currentItem?.accessLog()?.events ?: return
        // Process only finalized entries (all but the last, which is still accumulating stats).
        val finalized = events.size - 1
        while (finalizedAccessEntries < finalized) {
            (events[finalizedAccessEntries] as? AVPlayerItemAccessLogEvent)?.let(::processAccessEntry)
            finalizedAccessEntries++
        }
    }

    private fun processAccessEntry(event: AVPlayerItemAccessLogEvent) {
        val now = nowMs()
        store.addSegmentMetric(
            accessLogSegmentMetric(
                nowMs = now,
                uri = event.URI,
                sizeBytes = event.numberOfBytesTransferred,
                observedBitrate = event.observedBitrate,
                transferDurationSeconds = event.transferDuration,
            ),
        )
        emitBitrateSwitch(now, event.indicatedBitrate)
        if (event.numberOfDroppedVideoFrames >= DROPPED_FRAME_THRESHOLD) {
            store.addPlaybackError(droppedFramesError(now, event.numberOfDroppedVideoFrames))
        }
    }

    private fun emitBitrateSwitch(
        nowMs: Long,
        indicatedBitrate: Double,
    ) {
        if (!isBitrateSwitch(lastIndicatedBitrate, indicatedBitrate)) return
        val newTrack = activeTrackFromIndicatedBitrate(indicatedBitrate)
        val reason = if (lastActiveTrack == null) SwitchReason.INITIAL else SwitchReason.ADAPTIVE
        store.addTrackSwitchEvent(
            TrackSwitchEvent.VideoSwitch(
                timestampMs = nowMs,
                bufferDurationMs = 0,
                reason = reason,
                previousTrack = lastActiveTrack,
                newTrack = newTrack,
            ),
        )
        store.updateActiveTrack(newTrack)
        lastActiveTrack = newTrack
        lastIndicatedBitrate = indicatedBitrate
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

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

    private companion object {
        const val VARIANTS_KEY = "variants"
        const val DROPPED_FRAME_THRESHOLD = 3
        const val MILLIS_PER_SECOND = 1000.0
    }
}

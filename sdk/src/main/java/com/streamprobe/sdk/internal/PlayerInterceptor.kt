package com.streamprobe.sdk.internal

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.TrackSwitchEvent
import com.streamprobe.sdk.model.TracksSnapshot
import com.streamprobe.sdk.model.VariantInfo
import java.io.IOException

/**
 * Listens to a [Player] for track selection changes,
 * mapping Media3 types into SDK-owned models and pushing them into [SessionStore].
 */
@UnstableApi
internal class PlayerInterceptor(
    private val sessionStore: SessionStore,
) : Player.Listener,
    AnalyticsListener {
    private var player: ExoPlayer? = null
    private val drmTracker = DrmSessionTracker(sessionStore)
    private var lastVideoTrack: ActiveTrackInfo? = null
    private var lastAudioTrack: AudioTrackInfo? = null
    private var lastSubtitleTrack: SubtitleTrackInfo? = null

    // Caches the selection reason reported by onDownstreamFormatChanged for use in onVideoInputFormatChanged.
    private var pendingVideoSwitchReason: SwitchReason = SwitchReason.INITIAL

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(this)
        player.addAnalyticsListener(this)
        player.addAnalyticsListener(drmTracker)

        // Probe immediately in case the player is already prepared.
        probeTracks(player)
    }

    fun detach() {
        player?.removeAnalyticsListener(drmTracker)
        player?.removeAnalyticsListener(this)
        player?.removeListener(this)
        player = null
        lastVideoTrack = null
        lastAudioTrack = null
        lastSubtitleTrack = null
        pendingVideoSwitchReason = SwitchReason.INITIAL
        drmTracker.reset()
    }

    // ── Player.Listener callbacks ───────────────────────────────────────────

    override fun onTracksChanged(tracks: Tracks) {
        probeTracks(player ?: return)
    }

    // AnalyticsListener — fires when the decoder receives a new format; used as the authoritative
    // source for active video track state. The selection reason is cached from onDownstreamFormatChanged.
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        val newTrack = format.toActiveTrackInfo().takeIf { it != lastVideoTrack } ?: return
        val timestamp = System.currentTimeMillis()
        val buffer = player?.totalBufferedDuration ?: 0L
        val switchReason = pendingVideoSwitchReason
        sessionStore.addTrackSwitchEvent(
            TrackSwitchEvent.VideoSwitch(timestamp, buffer, switchReason, lastVideoTrack, newTrack),
        )
        pendingVideoSwitchReason = SwitchReason.INITIAL
        sessionStore.updateActiveTrack(newTrack)
        val switchMsg =
            "${lastVideoTrack?.width}x${lastVideoTrack?.height}" +
                " \u2192 ${newTrack.width}x${newTrack.height} reason=$switchReason"
        lastVideoTrack = newTrack
        Log.d(TAG, "Video input format changed: $switchMsg")
    }

    // AnalyticsListener — fires when the downstream selected format/track changes.
    // For video/default: only caches the selection reason for use in onVideoInputFormatChanged.
    // For audio/text: emits switch events directly.
    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData,
    ) {
        val format = mediaLoadData.trackFormat ?: return
        val timestamp = System.currentTimeMillis()
        val buffer = player?.totalBufferedDuration ?: 0L
        val reason = mapSelectionReason(mediaLoadData.trackSelectionReason)

        when (mediaLoadData.trackType) {
            C.TRACK_TYPE_VIDEO -> {
                // Cache the reason; the VideoSwitch event is emitted in onVideoInputFormatChanged.
                pendingVideoSwitchReason = reason
            }
            C.TRACK_TYPE_DEFAULT -> {
                // Only cache the reason when the format has valid video dimensions, to avoid
                // overwriting the pending reason for a non-video DEFAULT event (e.g., audio muxed
                // in the default track with NO_VALUE width/height).
                if (format.width > 0 || format.height > 0) {
                    pendingVideoSwitchReason = reason
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                val newTrack = format.toAudioTrackInfoDetecting().takeIf { it != lastAudioTrack }
                newTrack?.let {
                    sessionStore.addTrackSwitchEvent(
                        TrackSwitchEvent.AudioSwitch(timestamp, buffer, reason, lastAudioTrack, it),
                    )
                    lastAudioTrack = it
                    Log.d(TAG, "Audio switch: ${it.language} ${it.codecs}")
                }
            }
            C.TRACK_TYPE_TEXT -> {
                val newTrack = format.toSubtitleTrackInfoDetecting().takeIf { it != lastSubtitleTrack }
                newTrack?.let {
                    sessionStore.addTrackSwitchEvent(
                        TrackSwitchEvent.SubtitleSwitch(timestamp, buffer, reason, lastSubtitleTrack, it),
                    )
                    lastSubtitleTrack = it
                    Log.d(TAG, "Subtitle switch: ${it.language} ${it.mimeType}")
                }
            }
        }
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (wasCanceled) return // Filtered: cancellations from seeks / track switches are not errors.

        // DRM data type is deferred to M8; ignore here to avoid scope overlap.
        val dataTypePrefix =
            when (mediaLoadData.dataType) {
                C.DATA_TYPE_MEDIA -> ""
                C.DATA_TYPE_MANIFEST -> "MANIFEST "
                else -> return
            }

        val httpStatus = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val uriPath = loadEventInfo.uri.lastPathSegment ?: loadEventInfo.uri.toString()
        val errorPrefix = if (httpStatus != null) "HTTP $httpStatus" else error::class.simpleName ?: "Error"

        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = System.currentTimeMillis(),
                category = ErrorCategory.LOAD_ERROR,
                message = "$dataTypePrefix$errorPrefix: $uriPath",
                detail = error.message ?: error::class.qualifiedName,
            ),
        )
        Log.d(TAG, "Load error: $dataTypePrefix$errorPrefix — $uriPath")
    }

    override fun onVideoCodecError(
        eventTime: AnalyticsListener.EventTime,
        videoCodecError: Exception,
    ) {
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = System.currentTimeMillis(),
                category = ErrorCategory.VIDEO_CODEC_ERROR,
                message = videoCodecError.message ?: videoCodecError::class.simpleName ?: "Codec error",
                detail = videoCodecError.toString(),
            ),
        )
        Log.d(TAG, "Video codec error: ${videoCodecError.message}")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (droppedFrames < DROPPED_FRAME_THRESHOLD) return
        val now = System.currentTimeMillis()
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = now,
                category = ErrorCategory.DROPPED_FRAMES,
                message = "$droppedFrames frames in ${elapsedMs}ms",
                categoryDetail =
                    ErrorDetail.DroppedFrames(
                        totalFrames = droppedFrames,
                        burstCount = 1,
                        lastUpdateMs = now,
                    ),
            ),
        )
        Log.d(TAG, "Dropped $droppedFrames frames in ${elapsedMs}ms")
    }

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception,
    ) {
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = System.currentTimeMillis(),
                category = ErrorCategory.AUDIO_CODEC_ERROR,
                message = audioCodecError.message ?: audioCodecError::class.simpleName ?: "Audio codec error",
                detail = audioCodecError.toString(),
            ),
        )
        Log.d(TAG, "Audio codec error: ${audioCodecError.message}")
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        sessionStore.addPlaybackError(
            PlaybackErrorEvent(
                timestampMs = System.currentTimeMillis(),
                category = ErrorCategory.AUDIO_SINK_ERROR,
                message = audioSinkError.message ?: audioSinkError::class.simpleName ?: "Audio sink error",
                detail = audioSinkError.toString(),
            ),
        )
        Log.d(TAG, "Audio sink error: ${audioSinkError.message}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return

        val cdnInfo = CdnHeaderParser.parse(headers = loadEventInfo.responseHeaders)
        val metric =
            SegmentMetric(
                requestTimestampMs = System.currentTimeMillis() - loadEventInfo.loadDurationMs,
                totalDurationMs = loadEventInfo.loadDurationMs,
                sizeBytes = loadEventInfo.bytesLoaded,
                throughputBytesPerSec =
                    if (loadEventInfo.loadDurationMs > 0) {
                        loadEventInfo.bytesLoaded * 1000 / loadEventInfo.loadDurationMs
                    } else {
                        0
                    },
                uri = loadEventInfo.uri.toString(),
                cdnInfo = cdnInfo,
            )
        sessionStore.addSegmentMetric(metric)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Builds the full track list from [player.currentTracks], capturing all renditions
     * (selected or not) with [isSelected] set via [Tracks.Group.isTrackSelected].
     * The result is pushed into [SessionStore] so the rendition adapter always reflects
     * the player's current state without needing secondary comparisons.
     */
    private fun probeTracks(player: Player) {
        val variants = mutableListOf<VariantInfo>()
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val subtitleTracks = mutableListOf<SubtitleTrackInfo>()

        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> collectVideoTracks(group, variants)
                C.TRACK_TYPE_AUDIO -> collectAudioTracks(group, audioTracks)
                C.TRACK_TYPE_TEXT -> collectSubtitleTracks(group, subtitleTracks)
            }
        }

        sessionStore.updateTrackList(TracksSnapshot(variants, audioTracks, subtitleTracks))
        if (variants.isNotEmpty() || audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
            Log.d(TAG, "Tracks updated: ${variants.size} video, ${audioTracks.size} audio, ${subtitleTracks.size} subtitle")
        }

        val foundAudio = audioTracks.find { it.isSelected }
        val foundSubtitle = subtitleTracks.find { it.isSelected }

        sessionStore.updateActiveAudioTrack(foundAudio)
        sessionStore.updateActiveSubtitleTrack(foundSubtitle)

        if (lastSubtitleTrack != null && foundSubtitle == null) {
            sessionStore.addTrackSwitchEvent(
                TrackSwitchEvent.SubtitleSwitch(
                    timestampMs = System.currentTimeMillis(),
                    bufferDurationMs = player.totalBufferedDuration,
                    reason = SwitchReason.MANUAL,
                    previousTrack = lastSubtitleTrack,
                    newTrack = null,
                ),
            )
            lastSubtitleTrack = null
        }
    }

    private fun collectVideoTracks(
        group: Tracks.Group,
        out: MutableList<VariantInfo>,
    ) {
        for (i in 0 until group.length) {
            val fmt = group.getTrackFormat(i)
            if (fmt.width <= 0 && fmt.height <= 0) continue
            out.add(
                VariantInfo(
                    bitrate = fmt.bitrate,
                    width = fmt.width,
                    height = fmt.height,
                    codecs = fmt.codecs,
                    frameRate = fmt.frameRate,
                    id = fmt.id,
                    isSelected = group.isTrackSelected(i),
                ),
            )
        }
    }

    private fun collectAudioTracks(
        group: Tracks.Group,
        out: MutableList<AudioTrackInfo>,
    ) {
        for (i in 0 until group.length) {
            val isSelected = group.isTrackSelected(i)
            val info = group.getTrackFormat(i).toAudioTrackInfoDetecting(isSelected = isSelected)
            out.add(info)
        }
    }

    private fun collectSubtitleTracks(
        group: Tracks.Group,
        out: MutableList<SubtitleTrackInfo>,
    ) {
        for (i in 0 until group.length) {
            val isSelected = group.isTrackSelected(i)
            val info = group.getTrackFormat(i).toSubtitleTrackInfoDetecting(isSelected = isSelected)
            out.add(info)
        }
    }

    // ── Format → model extension functions (see FormatExtensions.kt) ───────

    @VisibleForTesting
    internal fun mapSelectionReason(reason: Int): SwitchReason =
        when (reason) {
            C.SELECTION_REASON_INITIAL -> SwitchReason.INITIAL
            C.SELECTION_REASON_ADAPTIVE -> SwitchReason.ADAPTIVE
            C.SELECTION_REASON_MANUAL -> SwitchReason.MANUAL
            C.SELECTION_REASON_TRICK_PLAY -> SwitchReason.TRICKPLAY
            else -> SwitchReason.UNKNOWN
        }

    companion object {
        private const val TAG = "[StreamProbe] PlayerInterceptor"

        @VisibleForTesting
        internal const val DROPPED_FRAME_THRESHOLD = 3
    }
}

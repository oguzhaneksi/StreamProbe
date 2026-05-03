package com.streamprobe.sdk.internal

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.DashManifestInfo
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.HlsManifestInfo
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.model.TrackSwitchEvent
import com.streamprobe.sdk.model.VariantInfo
import java.io.IOException

/**
 * Listens to a [Player] for manifest availability and track selection changes,
 * mapping Media3 types into SDK-owned models and pushing them into [SessionStore].
 */
@UnstableApi
internal class PlayerInterceptor(
    private val sessionStore: SessionStore,
) : Player.Listener,
    AnalyticsListener {
    private var player: ExoPlayer? = null
    private var lastVideoTrack: ActiveTrackInfo? = null
    private var lastAudioTrack: AudioTrackInfo? = null
    private var lastSubtitleTrack: SubtitleTrackInfo? = null

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(this)
        player.addAnalyticsListener(this)

        // Probe immediately — manifest may already be available if the player is prepared.
        probeManifest(player)
        probeTracks(player)
    }

    fun detach() {
        player?.removeAnalyticsListener(this)
        player?.removeListener(this)
        player = null
        lastVideoTrack = null
        lastAudioTrack = null
        lastSubtitleTrack = null
    }

    // ── Player.Listener callbacks ───────────────────────────────────────────

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        player?.let { probeManifest(it) }
    }

    override fun onTracksChanged(tracks: Tracks) {
        probeTracks(player ?: return)
    }

    // AnalyticsListener — fires on every input-format change, including bitrate-only ABR switches.
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        updateActiveTrack(format)
    }

    // AnalyticsListener — fires when the downstream selected format/track changes; used to track switches.
    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData,
    ) {
        val format = mediaLoadData.trackFormat ?: return
        val timestamp = System.currentTimeMillis()
        val buffer = player?.totalBufferedDuration ?: 0L
        val reason = mapSelectionReason(mediaLoadData.trackSelectionReason)

        when (mediaLoadData.trackType) {
            C.TRACK_TYPE_VIDEO,
            C.TRACK_TYPE_DEFAULT,
            -> {
                // DEFAULT only counts as video if dimensions are present.
                val isDefaultWithNoDimensions =
                    mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT &&
                        format.width <= 0 &&
                        format.height <= 0
                val newTrack = format.toActiveTrackInfo().takeIf { !isDefaultWithNoDimensions && it != lastVideoTrack }
                newTrack?.let {
                    sessionStore.addTrackSwitchEvent(
                        TrackSwitchEvent.VideoSwitch(timestamp, buffer, reason, lastVideoTrack, it),
                    )
                    lastVideoTrack = it
                    val switchMsg =
                        "Video switch: ${lastVideoTrack?.width}x${lastVideoTrack?.height}" +
                            " \u2192 ${it.width}x${it.height} reason=${mediaLoadData.trackSelectionReason}"
                    Log.d(TAG, switchMsg)
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                val newTrack = format.toAudioTrackInfoDetecting().takeIf { it != lastAudioTrack }
                newTrack?.let {
                    sessionStore.addTrackSwitchEvent(
                        TrackSwitchEvent.AudioSwitch(timestamp, buffer, reason, lastAudioTrack, it),
                    )
                    sessionStore.updateActiveAudioTrack(it)
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
                    sessionStore.updateActiveSubtitleTrack(it)
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

    private fun probeManifest(player: ExoPlayer) {
        when (val manifest = player.currentManifest) {
            is HlsManifest -> probeHlsManifest(manifest)
            is DashManifest -> probeDashManifest(manifest)
            else -> Log.d(TAG, "Unknown manifest type: ${manifest?.javaClass?.simpleName}")
        }
    }

    private fun probeHlsManifest(manifest: HlsManifest) {
        val playlist = manifest.multivariantPlaylist
        val variants =
            playlist.variants.map { variant ->
                val fmt = variant.format
                VariantInfo(
                    bitrate = fmt.bitrate,
                    width = fmt.width,
                    height = fmt.height,
                    codecs = fmt.codecs,
                    frameRate = fmt.frameRate,
                )
            }
        val audioTracks =
            buildList {
                playlist.audios.forEach { rendition ->
                    add(rendition.format.toAudioTrackInfo(isMuxed = false))
                }
                playlist.muxedAudioFormat?.let { add(it.toAudioTrackInfo(isMuxed = true)) }
            }
        val subtitleTracks =
            buildList {
                playlist.subtitles.forEach { rendition ->
                    add(rendition.format.toSubtitleTrackInfo(SubtitleKind.SIDECAR))
                }
                playlist.closedCaptions.forEach { rendition ->
                    add(rendition.format.toSubtitleTrackInfo(SubtitleKind.CC))
                }
                playlist.muxedCaptionFormats?.forEach { fmt ->
                    add(fmt.toSubtitleTrackInfo(SubtitleKind.CC))
                }
            }
        sessionStore.updateManifest(HlsManifestInfo(variants, audioTracks, subtitleTracks))
        Log.d(TAG, "HLS manifest captured: ${variants.size} variants, ${audioTracks.size} audio, ${subtitleTracks.size} subtitle")
    }

    private fun probeDashManifest(manifest: DashManifest) {
        val variants = mutableListOf<VariantInfo>()
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val subtitleTracks = mutableListOf<SubtitleTrackInfo>()
        val allAdaptationSets = (0 until manifest.periodCount).flatMap { manifest.getPeriod(it).adaptationSets }
        for (adaptationSet in allAdaptationSets) {
            processDashAdaptationSet(adaptationSet, variants, audioTracks, subtitleTracks)
        }
        sessionStore.updateManifest(DashManifestInfo(variants, audioTracks, subtitleTracks))
        Log.d(
            TAG,
            "DASH manifest captured: ${variants.size} representations, ${audioTracks.size} audio, ${subtitleTracks.size} subtitle",
        )
    }

    private fun processDashAdaptationSet(
        adaptationSet: AdaptationSet,
        variants: MutableList<VariantInfo>,
        audioTracks: MutableList<AudioTrackInfo>,
        subtitleTracks: MutableList<SubtitleTrackInfo>,
    ) {
        when (adaptationSet.type) {
            C.TRACK_TYPE_VIDEO -> {
                for (representation in adaptationSet.representations) {
                    val fmt = representation.format
                    variants.add(
                        VariantInfo(
                            bitrate = fmt.bitrate,
                            width = fmt.width,
                            height = fmt.height,
                            codecs = fmt.codecs,
                            frameRate = fmt.frameRate,
                        ),
                    )
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (representation in adaptationSet.representations) {
                    audioTracks.add(representation.format.toAudioTrackInfo(isMuxed = false))
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (representation in adaptationSet.representations) {
                    subtitleTracks.add(representation.format.toSubtitleTrackInfo(SubtitleKind.SIDECAR))
                }
            }
        }
    }

    private fun probeTracks(player: Player) {
        var foundVideo: Format? = null
        var foundAudio: AudioTrackInfo? = null
        var foundSubtitle: SubtitleTrackInfo? = null

        player.currentTracks.groups
            .filter { it.isSelected }
            .forEach { group ->
                val format =
                    (0 until group.length)
                        .firstOrNull { group.isTrackSelected(it) }
                        ?.let { group.getTrackFormat(it) } ?: return@forEach

                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> foundVideo = format
                    C.TRACK_TYPE_AUDIO -> foundAudio = format.toAudioTrackInfoDetecting()
                    C.TRACK_TYPE_TEXT -> foundSubtitle = format.toSubtitleTrackInfoDetecting()
                }
            }

        foundVideo?.let { updateActiveTrack(it) }
        sessionStore.updateActiveAudioTrack(foundAudio)
        sessionStore.updateActiveSubtitleTrack(foundSubtitle)

        // Subtitle disabled: emit a SubtitleSwitch with null newTrack.
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

    private fun updateActiveTrack(format: Format) {
        sessionStore.updateActiveTrack(format.toActiveTrackInfo())
        Log.d(TAG, "Active track: ${format.width}x${format.height} @ ${format.bitrate} bps")
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
        private const val TAG = "StreamProbe"

        @VisibleForTesting
        internal const val DROPPED_FRAME_THRESHOLD = 3
    }
}

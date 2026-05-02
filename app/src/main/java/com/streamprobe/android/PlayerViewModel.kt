package com.streamprobe.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamprobe.android.data.DebugSettingsRepository
import com.streamprobe.sdk.StreamProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val appContext: Context,
    private val repo: DebugSettingsRepository,
) : ViewModel() {

    private val streamProbe = StreamProbe()

    var player: ExoPlayer? by mutableStateOf(null)
        private set

    private val _selectedStream = MutableStateFlow<Stream?>(null)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var initJob: Job? = null
    private var updateJob: Job? = null

    /** Listens for track availability changes to update the selection UI. */
    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            player?.let { enumerateTracks(it) }
        }
    }

    fun selectStream(stream: Stream) {
        _selectedStream.value = stream
    }

    fun clearStream() {
        _selectedStream.value = null
    }

    @OptIn(UnstableApi::class)
    fun initializePlayer(activity: ComponentActivity) {
        streamProbe.show(activity)
        val stream = _selectedStream.value ?: return
        if (player != null) return
        initJob = viewModelScope.launch {
            val injectErrors = repo.injectErrorsFlow.first()
            buildPlayer(stream, injectErrors)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(stream: Stream, injectErrors: Boolean) {
        val dataSourceFactory: DataSource.Factory = if (injectErrors) {
            DebugDataSourceFactory(DefaultHttpDataSource.Factory(), errorRate = 0.2f)
        } else {
            DefaultHttpDataSource.Factory()
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                streamProbe.attach(this)
                addListener(playerListener)
                val mediaItem = if (stream.mimeType != null) {
                    MediaItem.Builder()
                        .setUri(stream.url)
                        .setMimeType(stream.mimeType)
                        .build()
                } else {
                    MediaItem.fromUri(stream.url)
                }
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        updateJob = viewModelScope.launch {
            while (isActive) {
                player?.let { p ->
                    _uiState.update { current ->
                        current.copy(
                            positionMs = if (current.isScrubbing) current.positionMs else p.currentPosition,
                            durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
                            bufferedPositionMs = p.bufferedPosition,
                            isPlaying = p.isPlaying,
                            isBuffering = p.playbackState == Player.STATE_BUFFERING,
                        )
                    }
                }
                delay(500)
            }
        }
    }

    fun releasePlayer() {
        initJob?.cancel()
        initJob = null
        updateJob?.cancel()
        updateJob = null
        player?.removeListener(playerListener)
        streamProbe.detach()
        player?.release()
        player = null
        _uiState.update {
            it.copy(
                videoTrackOptions = emptyList(),
                audioTrackOptions = emptyList(),
                subtitleTrackOptions = emptyList(),
                selectedVideoTrack = VideoTrackOption.Auto,
                selectedAudioTrack = null,
                selectedSubtitleTrack = SubtitleTrackOption.Off,
                hasMultipleTracks = false,
            )
        }
    }

    // ── Track enumeration ────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun enumerateTracks(player: ExoPlayer) {
        val tracks = player.currentTracks
        val rawVideoOptions = mutableListOf<VideoTrackOption.Fixed>()
        val audioOptions = mutableListOf<AudioTrackOption>()
        val subtitleOptions = mutableListOf<SubtitleTrackOption>(SubtitleTrackOption.Off)

        for ((groupIndex, group) in tracks.groups.withIndex()) {
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> rawVideoOptions.add(
                        VideoTrackOption.Fixed(
                            width = format.width,
                            height = format.height,
                            bitrate = format.bitrate,
                            codecs = format.codecs,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        )
                    )
                    C.TRACK_TYPE_AUDIO -> audioOptions.add(
                        AudioTrackOption(
                            language = format.language,
                            label = format.labels.firstOrNull()?.value,
                            codecs = format.codecs,
                            channelCount = format.channelCount,
                            bitrate = format.bitrate,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        )
                    )
                    C.TRACK_TYPE_TEXT -> subtitleOptions.add(
                        SubtitleTrackOption.Fixed(
                            language = format.language,
                            label = format.labels.firstOrNull()?.value,
                            mimeType = format.sampleMimeType,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        )
                    )
                }
            }
        }

        // Preserve every enumerated video track so the picker can expose all
        // selectable renditions. Auto is always first in the list.
        val videoOptions = mutableListOf<VideoTrackOption>(VideoTrackOption.Auto).apply {
            addAll(rawVideoOptions)
        }

        val hasMultiple = videoOptions.size > 1 || audioOptions.size > 1 || subtitleOptions.size > 1

        val currentVideo = resolveCurrentVideo(player, videoOptions)
        val currentAudio = resolveCurrentAudio(player, audioOptions)
        val currentSubtitle = resolveCurrentSubtitle(player, subtitleOptions)

        _uiState.update { current ->
            current.copy(
                videoTrackOptions = videoOptions,
                audioTrackOptions = audioOptions,
                subtitleTrackOptions = subtitleOptions,
                selectedVideoTrack = currentVideo,
                selectedAudioTrack = currentAudio,
                selectedSubtitleTrack = currentSubtitle,
                hasMultipleTracks = hasMultiple,
            )
        }
    }

    @OptIn(UnstableApi::class)
    private fun resolveCurrentVideo(player: ExoPlayer, options: List<VideoTrackOption>): VideoTrackOption {
        val override = player.trackSelectionParameters.overrides.values
            .firstOrNull { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }
            ?: return VideoTrackOption.Auto
        val overriddenTrackIndex = override.trackIndices.firstOrNull() ?: return VideoTrackOption.Auto
        val overriddenGroupIndex = player.currentTracks.groups.indexOfFirst { group ->
            group.type == C.TRACK_TYPE_VIDEO && group.mediaTrackGroup == override.mediaTrackGroup
        }
        if (overriddenGroupIndex == -1) return VideoTrackOption.Auto
        return options.filterIsInstance<VideoTrackOption.Fixed>()
            .find { it.groupIndex == overriddenGroupIndex && it.trackIndex == overriddenTrackIndex }
            ?: VideoTrackOption.Auto
    }

    private fun resolveCurrentAudio(player: ExoPlayer, options: List<AudioTrackOption>): AudioTrackOption? {
        for ((groupIndex, group) in player.currentTracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            val trackIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: continue
            return options.find { it.groupIndex == groupIndex && it.trackIndex == trackIndex }
        }
        return options.firstOrNull()
    }

    private fun resolveCurrentSubtitle(player: ExoPlayer, options: List<SubtitleTrackOption>): SubtitleTrackOption {
        if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) {
            return SubtitleTrackOption.Off
        }
        for ((groupIndex, group) in player.currentTracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
            val trackIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: continue
            return options.filterIsInstance<SubtitleTrackOption.Fixed>()
                .find { it.groupIndex == groupIndex && it.trackIndex == trackIndex }
                ?: SubtitleTrackOption.Off
        }
        return SubtitleTrackOption.Off
    }

    // ── Track selection ──────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    fun selectVideoTrack(option: VideoTrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = when (option) {
            is VideoTrackOption.Auto -> p.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
            is VideoTrackOption.Fixed -> {
                val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
                p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                    ).build()
            }
        }
        _uiState.update { it.copy(selectedVideoTrack = option) }
    }

    @OptIn(UnstableApi::class)
    fun selectAudioTrack(option: AudioTrackOption) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
            ).build()
        _uiState.update { it.copy(selectedAudioTrack = option) }
    }

    @OptIn(UnstableApi::class)
    fun selectSubtitleTrack(option: SubtitleTrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = when (option) {
            is SubtitleTrackOption.Off -> p.trackSelectionParameters.buildUpon()
                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT))
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            is SubtitleTrackOption.Fixed -> {
                val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
                p.trackSelectionParameters.buildUpon()
                    .setDisabledTrackTypes(emptySet())
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                    ).build()
            }
        }
        _uiState.update { it.copy(selectedSubtitleTrack = option) }
    }

    // ── Playback controls ────────────────────────────────────────────────────

    fun seekBack10s() {
        player?.let {
            val target = (it.currentPosition - 10_000L).coerceAtLeast(0L)
            it.seekTo(target)
        }
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekForward10s() {
        player?.let {
            val durationMs = _uiState.value.durationMs
            val max = if (durationMs > 0L) durationMs else Long.MAX_VALUE
            val target = (it.currentPosition + 10_000L).coerceAtMost(max)
            it.seekTo(target)
        }
    }

    fun onScrubPositionChanged(newPositionMs: Long) {
        _uiState.update {
            it.copy(
                isScrubbing = true,
                scrubPositionMs = newPositionMs,
            )
        }
    }

    fun onScrubFinished() {
        val state = _uiState.value
        if (state.durationMs > 0L) {
            player?.seekTo(state.scrubPositionMs)
        }
        _uiState.update {
            it.copy(
                isScrubbing = false,
                positionMs = state.scrubPositionMs
            )
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    companion object {
        fun factory(app: StreamProbeApplication) = viewModelFactory {
            initializer { PlayerViewModel(app.applicationContext, app.debugSettings) }
        }
    }
}

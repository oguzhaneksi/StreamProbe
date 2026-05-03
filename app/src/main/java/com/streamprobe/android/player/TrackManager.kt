package com.streamprobe.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.streamprobe.android.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TrackManager(
    private val playerFlow: StateFlow<ExoPlayer?>,
    scope: CoroutineScope,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    private val trackListener =
        object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                playerFlow.value?.let { enumerateTracks(it) }
            }
        }

    init {
        scope.launch {
            var previousPlayer: ExoPlayer? = null
            playerFlow.collect { player ->
                previousPlayer?.removeListener(trackListener)
                if (player == null) {
                    resetTrackState()
                } else {
                    player.addListener(trackListener)
                }
                previousPlayer = player
            }
        }
    }

    private fun resetTrackState() {
        uiState.update {
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

    @OptIn(UnstableApi::class)
    private fun enumerateTracks(player: ExoPlayer) {
        val (rawVideoOptions, audioOptions, subtitleOptions) = collectRawTracks(player)
        val videoOptions =
            mutableListOf<VideoTrackOption>(VideoTrackOption.Auto).apply {
                addAll(rawVideoOptions)
            }
        val hasMultiple =
            videoOptions.size > 1 || audioOptions.size > 1 || subtitleOptions.size > 1
        val currentVideo = resolveCurrentVideo(player, videoOptions)
        val currentAudio = resolveCurrentAudio(player, audioOptions)
        val currentSubtitle = resolveCurrentSubtitle(player, subtitleOptions)
        uiState.update { current ->
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
    private fun resolveCurrentVideo(
        player: ExoPlayer,
        options: List<VideoTrackOption>,
    ): VideoTrackOption {
        val override =
            player.trackSelectionParameters.overrides.values
                .firstOrNull { it.mediaTrackGroup.type == C.TRACK_TYPE_VIDEO }
        val overriddenTrackIndex = override?.trackIndices?.firstOrNull()
        val overriddenGroupIndex =
            override?.let { ov ->
                player.currentTracks.groups.indexOfFirst { group ->
                    group.type == C.TRACK_TYPE_VIDEO && group.mediaTrackGroup == ov.mediaTrackGroup
                }
            }
        return if (overriddenTrackIndex == null || overriddenGroupIndex == null || overriddenGroupIndex == -1) {
            VideoTrackOption.Auto
        } else {
            options
                .filterIsInstance<VideoTrackOption.Fixed>()
                .find { it.groupIndex == overriddenGroupIndex && it.trackIndex == overriddenTrackIndex }
                ?: VideoTrackOption.Auto
        }
    }

    private fun resolveCurrentAudio(
        player: ExoPlayer,
        options: List<AudioTrackOption>,
    ): AudioTrackOption? =
        player.currentTracks.groups
            .withIndex()
            .filter { (_, group) -> group.type == C.TRACK_TYPE_AUDIO && group.isSelected }
            .firstNotNullOfOrNull { (groupIndex, group) ->
                val trackIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                trackIndex?.let { options.find { opt -> opt.groupIndex == groupIndex && opt.trackIndex == it } }
            } ?: options.firstOrNull()

    private fun resolveCurrentSubtitle(
        player: ExoPlayer,
        options: List<SubtitleTrackOption>,
    ): SubtitleTrackOption {
        if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) {
            return SubtitleTrackOption.Off
        }
        return player.currentTracks.groups
            .withIndex()
            .filter { (_, group) -> group.type == C.TRACK_TYPE_TEXT && group.isSelected }
            .firstNotNullOfOrNull { (groupIndex, group) ->
                val trackIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                trackIndex?.let {
                    options
                        .filterIsInstance<SubtitleTrackOption.Fixed>()
                        .find { opt -> opt.groupIndex == groupIndex && opt.trackIndex == it }
                }
            } ?: SubtitleTrackOption.Off
    }

    @OptIn(UnstableApi::class)
    fun selectVideoTrack(option: VideoTrackOption) {
        val p = playerFlow.value ?: return
        p.trackSelectionParameters =
            when (option) {
                is VideoTrackOption.Auto ->
                    p.trackSelectionParameters
                        .buildUpon()
                        .clearVideoSizeConstraints()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                is VideoTrackOption.Fixed -> {
                    val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
                    p.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
                        ).build()
                }
            }
        uiState.update { it.copy(selectedVideoTrack = option) }
    }

    @OptIn(UnstableApi::class)
    fun selectAudioTrack(option: AudioTrackOption) {
        val p = playerFlow.value ?: return
        val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        p.trackSelectionParameters =
            p.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
                ).build()
        uiState.update { it.copy(selectedAudioTrack = option) }
    }

    @OptIn(UnstableApi::class)
    fun selectSubtitleTrack(option: SubtitleTrackOption) {
        val p = playerFlow.value ?: return
        p.trackSelectionParameters =
            when (option) {
                is SubtitleTrackOption.Off ->
                    p.trackSelectionParameters
                        .buildUpon()
                        .setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT))
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                is SubtitleTrackOption.Fixed -> {
                    val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
                    p.trackSelectionParameters
                        .buildUpon()
                        .setDisabledTrackTypes(emptySet())
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
                        ).build()
                }
            }
        uiState.update { it.copy(selectedSubtitleTrack = option) }
    }
}

private data class RawTracks(
    val videoOptions: List<VideoTrackOption.Fixed>,
    val audioOptions: List<AudioTrackOption>,
    val subtitleOptions: List<SubtitleTrackOption>,
)

@OptIn(UnstableApi::class)
private fun collectRawTracks(player: ExoPlayer): RawTracks {
    val tracks = player.currentTracks
    val rawVideoOptions = mutableListOf<VideoTrackOption.Fixed>()
    val audioOptions = mutableListOf<AudioTrackOption>()
    val subtitleOptions = mutableListOf<SubtitleTrackOption>(SubtitleTrackOption.Off)

    for ((groupIndex, group) in tracks.groups.withIndex()) {
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            when (group.type) {
                C.TRACK_TYPE_VIDEO ->
                    rawVideoOptions.add(
                        VideoTrackOption.Fixed(
                            width = format.width,
                            height = format.height,
                            bitrate = format.bitrate,
                            codecs = format.codecs,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        ),
                    )
                C.TRACK_TYPE_AUDIO ->
                    audioOptions.add(
                        AudioTrackOption(
                            language = format.language,
                            label = format.labels.firstOrNull()?.value,
                            codecs = format.codecs,
                            channelCount = format.channelCount,
                            bitrate = format.bitrate,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        ),
                    )
                C.TRACK_TYPE_TEXT ->
                    subtitleOptions.add(
                        SubtitleTrackOption.Fixed(
                            language = format.language,
                            label = format.labels.firstOrNull()?.value,
                            mimeType = format.sampleMimeType,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                        ),
                    )
            }
        }
    }
    return RawTracks(rawVideoOptions, audioOptions, subtitleOptions)
}

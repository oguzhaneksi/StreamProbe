package com.streamprobe.android

data class PlayerUiState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isScrubbing: Boolean = false,
    val scrubPositionMs: Long = 0L,
    // Track selection
    val videoTrackOptions: List<VideoTrackOption> = emptyList(),
    val audioTrackOptions: List<AudioTrackOption> = emptyList(),
    val subtitleTrackOptions: List<SubtitleTrackOption> = emptyList(),
    val selectedVideoTrack: VideoTrackOption = VideoTrackOption.Auto,
    val selectedAudioTrack: AudioTrackOption? = null,
    val selectedSubtitleTrack: SubtitleTrackOption = SubtitleTrackOption.Off,
    /** True when there is at least one non-Auto video option or a second audio/subtitle option — shows the Tracks button. */
    val hasMultipleTracks: Boolean = false,
) {
    val sliderValueMs: Long
        get() = if (isScrubbing) scrubPositionMs else positionMs
}
package com.streamprobe.sdk.model

/**
 * A single track switch event recorded during playback.
 * Video, audio and subtitle switches are unified in this sealed hierarchy
 * so they can be shown in a single chronological timeline.
 */
sealed interface TrackSwitchEvent {
    val timestampMs: Long
    val bufferDurationMs: Long
    val reason: SwitchReason

    data class VideoSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        /** Track active before the switch, or null for the initial selection. */
        val previousTrack: ActiveTrackInfo?,
        val newTrack: ActiveTrackInfo,
    ) : TrackSwitchEvent

    data class AudioSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        val previousTrack: AudioTrackInfo?,
        val newTrack: AudioTrackInfo,
    ) : TrackSwitchEvent

    data class SubtitleSwitch(
        override val timestampMs: Long,
        override val bufferDurationMs: Long,
        override val reason: SwitchReason,
        val previousTrack: SubtitleTrackInfo?,
        /** null = subtitle was disabled by the user. */
        val newTrack: SubtitleTrackInfo?,
    ) : TrackSwitchEvent
}

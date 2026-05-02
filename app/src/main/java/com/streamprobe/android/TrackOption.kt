package com.streamprobe.android

/**
 * UI-facing representation of a video quality track that can be selected by the user.
 */
sealed interface VideoTrackOption {
    /** Let ExoPlayer choose adaptively (ABR). */
    data object Auto : VideoTrackOption

    /** A specific video rendition pinned by the user. */
    data class Fixed(
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val codecs: String?,
        /** Index of the [androidx.media3.common.TrackGroup] in [androidx.media3.common.Tracks.groups]. */
        val groupIndex: Int,
        /** Index of the track within the [androidx.media3.common.TrackGroup]. */
        val trackIndex: Int,
    ) : VideoTrackOption
}

/**
 * UI-facing representation of an audio track that can be selected by the user.
 */
data class AudioTrackOption(
    val language: String?,
    val label: String?,
    val codecs: String?,
    val channelCount: Int,
    val bitrate: Int,
    /** Index of the [androidx.media3.common.TrackGroup] in [androidx.media3.common.Tracks.groups]. */
    val groupIndex: Int,
    /** Index of the track within the [androidx.media3.common.TrackGroup]. */
    val trackIndex: Int,
)

/**
 * UI-facing representation of a subtitle/CC track that can be selected by the user.
 */
sealed interface SubtitleTrackOption {
    /** Disable subtitles entirely. */
    data object Off : SubtitleTrackOption

    /** A specific subtitle rendition selected by the user. */
    data class Fixed(
        val language: String?,
        val label: String?,
        val mimeType: String?,
        /** Index of the [androidx.media3.common.TrackGroup] in [androidx.media3.common.Tracks.groups]. */
        val groupIndex: Int,
        /** Index of the track within the [androidx.media3.common.TrackGroup]. */
        val trackIndex: Int,
    ) : SubtitleTrackOption
}

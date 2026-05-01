package com.streamprobe.sdk.model

/**
 * A single audio rendition from the manifest or the currently active audio track.
 */
data class AudioTrackInfo(
    /** BCP 47 language tag, e.g. "en", "tr". Null if unspecified. */
    val language: String?,
    /** Human-readable display label from the manifest, e.g. "English (Descriptive)". Null if absent. */
    val label: String?,
    val codecs: String?,
    val bitrate: Int,
    val channelCount: Int,
    val sampleRate: Int,
    /** True if this audio is muxed inside a video variant (HLS muxedAudioFormat). */
    val isMuxed: Boolean = false,
)

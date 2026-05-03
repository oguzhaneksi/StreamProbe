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
    /** Format id from the manifest; used for reliable active-track matching. Null if unavailable. */
    val id: String? = null,
)

/** Returns true if [other] refers to the same audio rendition. Prefers [id] when both are non-null. */
internal fun AudioTrackInfo.isSameRenditionAs(other: AudioTrackInfo): Boolean =
    if (id != null && other.id != null) {
        id == other.id
    } else {
        language == other.language && codecs == other.codecs && sampleRate == other.sampleRate
    }

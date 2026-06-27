package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import com.streamprobe.sdk.util.oneDecimal

/**
 * Active-track, audio, subtitle and ABR-switch formatting for the overlay.
 * Split out of the former monolithic `OverlayFormatters`.
 */
internal object TrackFormatters {
    fun formatResolution(
        width: Int,
        height: Int,
    ): String = if (width > 0 && height > 0) "$width×$height" else "Audio only"

    fun formatActiveTrack(track: ActiveTrackInfo?): String {
        if (track == null) return "Loading…"
        return "${formatResolution(track.width, track.height)}  ·  ${ByteFormatters.formatBitrate(track.bitrate)}"
    }

    fun formatActiveAudio(audio: AudioTrackInfo?): String {
        if (audio == null) return "Loading…"
        val parts = mutableListOf<String>()
        val lang =
            audio.label
                ?: audio.language?.let { displayLanguage(it) }
        if (!lang.isNullOrBlank()) parts += lang
        val codec =
            audio.codecs
                ?.split(".")
                ?.firstOrNull()
                ?.uppercase()
        val channels =
            when (audio.channelCount) {
                1 -> "mono"
                2 -> "stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> if (audio.channelCount > 0) "${audio.channelCount}ch" else null
            }
        listOfNotNull(codec, channels)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
            ?.let { parts += it }
        if (audio.bitrate > 0) parts += ByteFormatters.formatBitrate(audio.bitrate)
        if (audio.sampleRate > 0) parts += "${oneDecimal(audio.sampleRate / 1000.0)} kHz"
        return parts.joinToString("  ·  ").ifBlank { "Unknown" }
    }

    fun formatActiveSubtitle(subtitle: SubtitleTrackInfo?): String {
        if (subtitle == null) return "Off"
        val parts = mutableListOf<String>()
        val lang =
            subtitle.label
                ?: subtitle.language?.let { displayLanguage(it) }
        if (!lang.isNullOrBlank()) parts += lang
        if (subtitle.kind == SubtitleKind.CC) parts += "(CC)"
        val mimeShort =
            when (subtitle.mimeType) {
                "text/vtt", "application/x-media3-webvtt" -> "WebVTT"
                "application/ttml+xml" -> "TTML"
                "application/x-subrip" -> "SRT"
                "text/x-ssa" -> "SSA"
                "application/cea-608", "application/cea-708" -> null // implied by (CC)
                else -> subtitle.mimeType?.substringAfterLast("/")
            }
        if (!mimeShort.isNullOrBlank()) parts += mimeShort
        return parts.joinToString("  ·  ").ifBlank { "Unknown" }
    }

    /** "720p → 1080p" or "1.5 Mbps → 5.0 Mbps" when resolution is identical. */
    fun formatAbrSwitch(
        from: ActiveTrackInfo?,
        to: ActiveTrackInfo,
    ): String {
        val toLabel = if (to.height > 0) "${to.height}p" else ByteFormatters.formatBitrate(to.bitrate)
        if (from == null) return "— → $toLabel"
        val fromLabel = if (from.height > 0) "${from.height}p" else ByteFormatters.formatBitrate(from.bitrate)
        return if (fromLabel == toLabel) {
            "${ByteFormatters.formatBitrate(from.bitrate)} → ${ByteFormatters.formatBitrate(to.bitrate)}"
        } else {
            "$fromLabel → $toLabel"
        }
    }

    /** "ADAPTIVE", "MANUAL", etc. */
    fun formatSwitchReason(reason: SwitchReason): String = reason.name
}

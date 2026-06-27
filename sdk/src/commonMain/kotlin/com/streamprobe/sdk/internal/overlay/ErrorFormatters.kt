package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.PlaybackErrorEvent

/**
 * Error-category badge and error-export formatting for the overlay.
 * Split out of the former monolithic `OverlayFormatters`.
 */
internal object ErrorFormatters {
    fun formatErrorCategory(category: ErrorCategory): String =
        when (category) {
            ErrorCategory.LOAD_ERROR -> "LOAD"
            ErrorCategory.VIDEO_CODEC_ERROR -> "CODEC"
            ErrorCategory.DROPPED_FRAMES -> "FRAMES"
            ErrorCategory.AUDIO_SINK_ERROR -> "AUDIO"
            ErrorCategory.AUDIO_CODEC_ERROR -> "ACODEC"
            ErrorCategory.DRM_ERROR -> "DRM"
        }

    fun formatErrorsForExport(
        errors: List<PlaybackErrorEvent>,
        baseTimestampMs: Long,
    ): String {
        val header = "[StreamProbe] ${errors.size} errors"
        val rows =
            errors.mapIndexed { i, e ->
                val rel = TimeFormatters.formatRelativeTimestamp(e.timestampMs, baseTimestampMs)
                val cat = formatErrorCategory(e.category)
                val abs = TimeFormatters.formatAbsoluteTimestamp(e.timestampMs)
                buildString {
                    append("#${i + 1} $rel $cat ${e.message} [$abs]")
                    if (!e.detail.isNullOrBlank()) {
                        append("\n    ${e.detail}")
                    }
                }
            }
        return (listOf(header) + rows).joinToString("\n")
    }
}

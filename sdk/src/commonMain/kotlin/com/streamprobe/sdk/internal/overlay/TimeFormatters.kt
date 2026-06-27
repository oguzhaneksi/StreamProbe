package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.NetworkTiming
import com.streamprobe.sdk.util.oneDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Buffer-duration, relative/absolute timestamp and TTFB formatting for the overlay.
 * Split out of the former monolithic `OverlayFormatters`.
 */
internal object TimeFormatters {
    // "HH:mm:ss.SSS" — the kotlinx-datetime equivalent of SimpleDateFormat("HH:mm:ss.SSS").
    private val TIME_FORMAT =
        LocalDateTime.Format {
            hour()
            char(':')
            minute()
            char(':')
            second()
            char('.')
            secondFraction(3)
        }

    /** "buf: 12.4s" */
    fun formatBufferDuration(bufferMs: Long): String = "buf: ${oneDecimal(bufferMs / 1000.0)}s"

    /** "+0:42" relative to a base timestamp. */
    fun formatRelativeTimestamp(
        timestampMs: Long,
        baseTimestampMs: Long,
    ): String {
        val diffMs = (timestampMs - baseTimestampMs).coerceAtLeast(0L)
        val totalSec = diffMs / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "+$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** "HH:mm:ss.SSS" absolute wall-clock timestamp in the system-default time zone. */
    fun formatAbsoluteTimestamp(timestampMs: Long): String {
        val local = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return TIME_FORMAT.format(local)
    }

    fun formatTtfb(networkTiming: NetworkTiming?): String {
        if (networkTiming == null) return "—"
        return "${if (networkTiming.isEstimated) "~" else ""}${networkTiming.ttfbMs}ms"
    }
}

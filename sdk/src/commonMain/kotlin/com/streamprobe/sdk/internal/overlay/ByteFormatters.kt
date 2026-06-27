package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.util.oneDecimal

/**
 * Byte-size, throughput and bitrate formatting for the overlay.
 * Split out of the former monolithic `OverlayFormatters`.
 */
internal object ByteFormatters {
    fun formatBytes(
        value: Long,
        suffix: String = "",
    ): String =
        when {
            value >= 1_000_000 -> "${oneDecimal(value / 1_000_000.0)} MB$suffix"
            value >= 1_000 -> "${oneDecimal(value / 1_000.0)} KB$suffix"
            else -> "$value B$suffix"
        }

    fun formatThroughput(bytesPerSec: Long): String = formatBytes(bytesPerSec, "/s")

    fun formatBitrate(bps: Int): String =
        when {
            bps >= 1_000_000 -> "${oneDecimal(bps / 1_000_000.0)} Mbps"
            bps >= 1_000 -> "${bps / 1_000} kbps"
            bps > 0 -> "$bps bps"
            else -> "? bps"
        }
}

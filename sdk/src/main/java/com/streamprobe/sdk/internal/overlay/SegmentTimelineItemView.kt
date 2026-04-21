package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.streamprobe.sdk.model.SegmentMetric
import java.util.Locale

/**
 * Programmatic view for a single row in the segment timeline list.
 *
 * Displays: segment index, download duration, size, throughput, and a cache status dot.
 * Follows the same no-XML, no-R pattern as [VariantItemView].
 */
internal class SegmentTimelineItemView(context: Context) : LinearLayout(context) {

    private val indexView: TextView
    private val durationView: TextView
    private val sizeView: TextView
    private val throughputView: TextView
    private val cacheDot: View

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(5f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        // Segment index ("#1", "#2", …)
        indexView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(28f).toInt()
        }
        addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Duration ("200ms")
        durationView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(durationView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(4f).toInt()
        })

        // Size ("1.2 MB")
        sizeView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(sizeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        // Throughput ("5.0 MB/s")
        throughputView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(throughputView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        // Cache status dot (8×8 dp oval)
        cacheDot = View(context)
        addView(cacheDot, LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
            it.marginStart = dp(6f).toInt()
        })
    }

    fun bind(index: Int, metric: SegmentMetric) {
        indexView.text = "#${index + 1}"
        durationView.text = "${metric.totalDurationMs}ms"
        sizeView.text = formatBytes(metric.sizeBytes)
        throughputView.text = formatThroughput(metric.throughputBytesPerSec)
        cacheDot.background = OverlayDrawables.cacheDot(metric.cdnInfo.cacheStatus)
    }

    private fun dp(value: Float) = context.dp(value)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun formatThroughput(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1_000.0)
        else -> "$bytesPerSec B/s"
    }
}

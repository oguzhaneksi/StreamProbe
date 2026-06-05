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

/**
 * Programmatic view for a single row in the segment timeline list.
 *
 * Two-row layout:
 * - Row 1: segment index, download duration, cache status dot
 * - Row 2 (dimmed): size, throughput, TTFB (when available)
 */
internal class SegmentTimelineItemView(
    context: Context,
) : LinearLayout(context) {
    private val indexView: TextView
    private val durationView: TextView
    private val cacheDot: View
    private val secondaryView: TextView

    init {
        orientation = VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(5f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        // ── Row 1: index · duration · cache dot ──────────────────────────────

        val row1 =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        indexView =
            TextView(context).apply {
                setTextColor("#99FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minWidth = dp(28f).toInt()
            }
        row1.addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        durationView =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        row1.addView(
            durationView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        cacheDot = View(context)
        row1.addView(
            cacheDot,
            LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
                it.marginStart = dp(6f).toInt()
            },
        )

        addView(row1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: size · throughput · TTFB ──────────────────────────────────

        secondaryView =
            TextView(context).apply {
                setTextColor("#99FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
        addView(
            secondaryView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(32f).toInt()
                it.topMargin = dp(2f).toInt()
            },
        )
    }

    fun bind(
        index: Int,
        metric: SegmentMetric,
    ) {
        indexView.text = "#${index + 1}"
        durationView.text = "DL: ${metric.totalDurationMs}ms"
        cacheDot.background = OverlayDrawables.cacheDot(metric.cdnInfo.cacheStatus)
        secondaryView.text = buildSecondaryLine(metric)
    }

    private fun buildSecondaryLine(metric: SegmentMetric): String {
        val parts = mutableListOf<String>()
        parts += "Size: ${OverlayFormatters.formatBytes(metric.sizeBytes)}"
        parts += "TP: ${OverlayFormatters.formatThroughput(metric.throughputBytesPerSec)}"
        metric.networkTiming?.let { parts += "TTFB: ${OverlayFormatters.formatTtfb(it)}" }
        return parts.joinToString("  ·  ")
    }

    private fun dp(value: Float) = context.dp(value)
}

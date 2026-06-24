package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.streamprobe.sdk.model.DrmSessionEvent

/**
 * Single row in the DRM timeline tab.
 * Layout: event dot → scheme badge → event label → latency (if any) → relative timestamp.
 */
internal class DrmTimelineItemView(
    context: Context,
) : LinearLayout(context) {
    private val indexView: TextView
    private val dotView: View
    private val schemeView: TextView
    private val labelView: TextView
    private val latencyView: TextView
    private val timestampView: TextView

    init {
        orientation = VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(5f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        val row =
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
        row.addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        dotView = View(context)
        row.addView(
            dotView,
            LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        schemeView =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor("#CCFFFFFF".toColorInt())
                minWidth = dp(24f).toInt()
            }
        row.addView(
            schemeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        labelView =
            TextView(context).apply {
                setTextColor("#FFFFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        row.addView(
            labelView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        latencyView =
            TextView(context).apply {
                setTextColor("#99FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                visibility = GONE
            }
        row.addView(
            latencyView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        timestampView =
            TextView(context).apply {
                setTextColor("#66FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
        row.addView(
            timestampView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(6f).toInt()
            },
        )

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        index: Int,
        event: DrmSessionEvent,
        baseTimestampMs: Long,
    ) {
        indexView.text = "#${index + 1}"
        dotView.background = OverlayDrawables.drmEventDot(event)
        schemeView.text = DrmFormatters.formatDrmSchemeBadge(event.scheme)
        labelView.text = DrmFormatters.formatDrmEventLabel(event)
        timestampView.text = OverlayFormatters.formatRelativeTimestamp(event.timestampMs, baseTimestampMs)

        if (event is DrmSessionEvent.KeysLoaded) {
            latencyView.text = "${event.licenseLatencyMs}ms"
            latencyView.visibility = VISIBLE
        } else {
            latencyView.visibility = GONE
        }
    }

    private fun dp(value: Float) = context.dp(value)
}

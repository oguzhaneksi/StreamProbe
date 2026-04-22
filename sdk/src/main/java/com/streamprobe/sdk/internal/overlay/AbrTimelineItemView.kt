package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.streamprobe.sdk.model.AbrSwitchEvent

internal class AbrTimelineItemView(context: Context) : LinearLayout(context) {

    private val indexView: TextView
    private val switchView: TextView
    private val bufferView: TextView
    private val reasonView: TextView
    private val timestampView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(5f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        indexView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(28f).toInt()
        }
        addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        switchView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(switchView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(4f).toInt()
        })

        bufferView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(bufferView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        reasonView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(reasonView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        timestampView = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(timestampView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })
    }

    fun bind(index: Int, event: AbrSwitchEvent, baseTimestampMs: Long) {
        indexView.text = "#${index + 1}"
        switchView.text = OverlayFormatters.formatAbrSwitch(event.previousTrack, event.newTrack)
        bufferView.text = OverlayFormatters.formatBufferDuration(event.bufferDurationMs)
        reasonView.text = OverlayFormatters.formatSwitchReason(event.reason)
        timestampView.text = OverlayFormatters.formatRelativeTimestamp(event.timestampMs, baseTimestampMs)
    }

    private fun dp(value: Float) = context.dp(value)
}

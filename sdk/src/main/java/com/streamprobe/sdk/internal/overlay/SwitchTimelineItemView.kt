package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.streamprobe.sdk.model.TrackSwitchEvent

/**
 * A single row in the Switches timeline.
 * Handles [TrackSwitchEvent.VideoSwitch], [TrackSwitchEvent.AudioSwitch], and
 * [TrackSwitchEvent.SubtitleSwitch] with distinct type labels.
 */
internal class SwitchTimelineItemView(context: Context) : LinearLayout(context) {

    private val indexView: TextView
    private val typeView: TextView
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

        typeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            minWidth = dp(22f).toInt()
        }
        addView(typeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        switchView = TextView(context).apply {
            setTextColor("#FFFFFFFF".toColorInt())
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

    fun bind(index: Int, event: TrackSwitchEvent, baseTimestampMs: Long) {
        indexView.text = "#${index + 1}"
        bufferView.text = OverlayFormatters.formatBufferDuration(event.bufferDurationMs)
        reasonView.text = OverlayFormatters.formatSwitchReason(event.reason)
        timestampView.text = OverlayFormatters.formatRelativeTimestamp(event.timestampMs, baseTimestampMs)

        when (event) {
            is TrackSwitchEvent.VideoSwitch -> {
                typeView.text = "VID"
                typeView.setTextColor("#4FC3F7".toColorInt())  // light blue
                switchView.text = OverlayFormatters.formatAbrSwitch(event.previousTrack, event.newTrack)
            }
            is TrackSwitchEvent.AudioSwitch -> {
                typeView.text = "AUD"
                typeView.setTextColor("#A5D6A7".toColorInt())  // light green
                val prev = event.previousTrack?.let { it.label ?: it.language ?: "?" }
                val next = event.newTrack.label ?: event.newTrack.language ?: "?"
                switchView.text = if (prev != null) "$prev \u2192 $next" else "\u2014 \u2192 $next"
            }
            is TrackSwitchEvent.SubtitleSwitch -> {
                typeView.text = "SUB"
                typeView.setTextColor("#CE93D8".toColorInt())  // light purple
                val prev = event.previousTrack?.let { it.label ?: it.language ?: "?" }
                val next = event.newTrack?.let { it.label ?: it.language ?: "?" } ?: "Off"
                switchView.text = if (prev != null) "$prev \u2192 $next" else "\u2014 \u2192 $next"
            }
        }
    }

    private fun dp(value: Float) = context.dp(value)
}

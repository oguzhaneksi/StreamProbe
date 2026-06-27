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
internal class SwitchTimelineItemView(
    context: Context,
) : LinearLayout(context) {
    private val indexView: TextView
    private val typeView: TextView
    private val switchView: TextView
    private val bufferView: TextView
    private val reasonView: TextView
    private val timestampView: TextView

    init {
        orientation = VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(5f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        // ── Row 1: index · type badge · switch text ───────────────────────────
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

        typeView =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                minWidth = dp(22f).toInt()
            }
        row1.addView(
            typeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        switchView =
            TextView(context).apply {
                setTextColor("#FFFFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        row1.addView(
            switchView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = dp(4f).toInt()
            },
        )

        addView(row1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: buffer · reason · timestamp (indented under switch text) ───
        // indent = indexView.minWidth(28) + typeView.marginStart(4) = 32dp
        val indent = (dp(28f) + dp(4f)).toInt()

        val row2 =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        bufferView =
            TextView(context).apply {
                setTextColor("#99FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
        row2.addView(
            bufferView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = indent
            },
        )

        reasonView =
            TextView(context).apply {
                setTextColor("#99FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
        row2.addView(
            reasonView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(6f).toInt()
            },
        )

        timestampView =
            TextView(context).apply {
                setTextColor("#66FFFFFF".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
        row2.addView(
            timestampView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(6f).toInt()
            },
        )

        addView(
            row2,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(2f).toInt()
            },
        )
    }

    fun bind(
        index: Int,
        event: TrackSwitchEvent,
        baseTimestampMs: Long,
    ) {
        indexView.text = "#${index + 1}"
        bufferView.text = TimeFormatters.formatBufferDuration(event.bufferDurationMs)
        reasonView.text = TrackFormatters.formatSwitchReason(event.reason)
        timestampView.text = TimeFormatters.formatRelativeTimestamp(event.timestampMs, baseTimestampMs)

        when (event) {
            is TrackSwitchEvent.VideoSwitch -> bindVideoSwitch(event)
            is TrackSwitchEvent.AudioSwitch -> bindAudioSwitch(event)
            is TrackSwitchEvent.SubtitleSwitch -> bindSubtitleSwitch(event)
        }
    }

    private fun bindVideoSwitch(event: TrackSwitchEvent.VideoSwitch) {
        typeView.text = "VID"
        typeView.setTextColor(TrackColors.VIDEO)
        switchView.text = TrackFormatters.formatAbrSwitch(event.previousTrack, event.newTrack)
    }

    private fun bindAudioSwitch(event: TrackSwitchEvent.AudioSwitch) {
        typeView.text = "AUD"
        typeView.setTextColor(TrackColors.AUDIO)
        val prev = event.previousTrack?.let { it.label ?: it.language ?: "?" }
        val next = event.newTrack.label ?: event.newTrack.language ?: "?"
        switchView.text = if (prev != null) "$prev \u2192 $next" else "\u2014 \u2192 $next"
    }

    private fun bindSubtitleSwitch(event: TrackSwitchEvent.SubtitleSwitch) {
        typeView.text = "SUB"
        typeView.setTextColor(TrackColors.TEXT)
        val prev = event.previousTrack?.let { it.label ?: it.language ?: "?" }
        val next = event.newTrack?.let { it.label ?: it.language ?: "?" } ?: "Off"
        switchView.text = if (prev != null) "$prev \u2192 $next" else "\u2014 \u2192 $next"
    }

    private fun dp(value: Float) = context.dp(value)
}

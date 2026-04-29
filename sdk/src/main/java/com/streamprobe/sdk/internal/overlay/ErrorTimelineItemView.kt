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
import com.streamprobe.sdk.model.PlaybackErrorEvent

internal class ErrorTimelineItemView(context: Context) : LinearLayout(context) {

    private val indexView: TextView
    private val dotView: View
    private val categoryView: TextView
    private val messageView: TextView
    private val timestampView: TextView

    val detailContainer: LinearLayout
    private val fullMessageView: TextView
    private val detailView: TextView
    private val absoluteTimestampView: TextView

    init {
        orientation = VERTICAL

        // ── Summary row ───────────────────────────────────────────────────────

        val summaryRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = dp(10f).toInt()
            val vPad = dp(5f).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }

        indexView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(28f).toInt()
        }
        summaryRow.addView(indexView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        dotView = View(context).apply {
            val size = dp(8f).toInt()
            layoutParams = LayoutParams(size, size).also {
                it.marginStart = dp(4f).toInt()
            }
        }
        summaryRow.addView(dotView, LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
            it.marginStart = dp(4f).toInt()
        })

        categoryView = TextView(context).apply {
            setTextColor("#CCFFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        summaryRow.addView(categoryView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        messageView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        summaryRow.addView(messageView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(4f).toInt()
        })

        timestampView = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        summaryRow.addView(timestampView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(4f).toInt()
        })

        addView(summaryRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Detail container (collapsible) ────────────────────────────────────

        detailContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            val hPad = dp(10f).toInt()
            val vPad = dp(6f).toInt()
            setPadding(hPad, 0, hPad, vPad)
        }

        fullMessageView = TextView(context).apply {
            setTextColor("#CCFFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        detailContainer.addView(fullMessageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(2f).toInt()
        })

        detailView = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        detailContainer.addView(detailView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(2f).toInt()
        })

        absoluteTimestampView = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        detailContainer.addView(absoluteTimestampView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(detailContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        index: Int,
        event: PlaybackErrorEvent,
        baseTimestampMs: Long,
        expanded: Boolean,
        onToggle: () -> Unit,
    ) {
        indexView.text = "#${index + 1}"
        dotView.background = OverlayDrawables.errorCategoryDot(event.category)
        categoryView.text = OverlayFormatters.formatErrorCategory(event.category)
        messageView.text = event.message
        timestampView.text = OverlayFormatters.formatRelativeTimestamp(event.timestampMs, baseTimestampMs)
        detailContainer.visibility = if (expanded) VISIBLE else GONE
        if (expanded) {
            fullMessageView.text = event.message
            detailView.text = event.detail.orEmpty()
            absoluteTimestampView.text = OverlayFormatters.formatAbsoluteTimestamp(event.timestampMs)
        }
        setOnClickListener { onToggle() }
    }

    private fun dp(value: Float) = context.dp(value)
}

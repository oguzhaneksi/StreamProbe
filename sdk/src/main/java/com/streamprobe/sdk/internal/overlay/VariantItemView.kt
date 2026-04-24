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
import androidx.core.view.isVisible
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.VariantInfo

/**
 * Programmatic view representing a single row in the variant list.
 *
 * Replaces `streamprobe_variant_item.xml`. All styling is applied in [init] so that no
 * layout inflation or [com.streamprobe.sdk.R] references are needed.
 */
internal class VariantItemView(context: Context) : LinearLayout(context) {

    private val dot: View
    private val resolution: TextView
    private val bitrate: TextView
    private val codecs: TextView

    init {
        orientation = VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(6f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        // First row: dot + resolution + bitrate
        val firstRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Active indicator dot (8×8 dp oval)
        dot = View(context).apply {
            background = OverlayDrawables.dotInactive()
        }
        firstRow.addView(dot, LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
            it.marginEnd = dp(8f).toInt()
        })

        // Resolution ("1920×1080" or "Audio only")
        resolution = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        firstRow.addView(resolution, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Bitrate ("5.8 Mbps")
        bitrate = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        firstRow.addView(bitrate, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(6f).toInt()
        })

        addView(firstRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Second row: codec string (full width, no truncation)
        codecs = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(codecs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(16f).toInt()  // dot(8dp) + dot marginEnd(8dp)
            it.topMargin = dp(2f).toInt()
        })
    }

    fun bind(variant: VariantInfo, active: ActiveTrackInfo?) {
        val isActive = active != null
                && variant.width == active.width
                && variant.height == active.height
                && variant.bitrate == active.bitrate

        dot.background = if (isActive) OverlayDrawables.dotActive() else OverlayDrawables.dotInactive()

        resolution.text = OverlayFormatters.formatResolution(variant.width, variant.height)
        bitrate.text = OverlayFormatters.formatBitrate(variant.bitrate)
        codecs.text = variant.codecs ?: ""
        codecs.isVisible = !variant.codecs.isNullOrEmpty()
    }

    private fun dp(value: Float) = context.dp(value)
}

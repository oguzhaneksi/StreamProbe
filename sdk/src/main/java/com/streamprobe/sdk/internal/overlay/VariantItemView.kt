package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.VariantInfo
import androidx.core.graphics.toColorInt

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
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(6f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        // Active indicator dot (8×8 dp oval)
        dot = View(context).apply {
            background = OverlayDrawables.dotInactive()
        }
        addView(dot, LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
            it.marginEnd = dp(8f).toInt()
        })

        // Resolution ("1920×1080" or "Audio only")
        resolution = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        addView(resolution, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Bitrate ("5.8 Mbps")
        bitrate = TextView(context).apply {
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(bitrate, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(6f).toInt()
        })

        // Codecs (truncated)
        codecs = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxWidth = dp(80f).toInt()
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
        }
        addView(codecs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(6f).toInt()
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
    }

    private fun dp(value: Float) = context.dp(value)
}

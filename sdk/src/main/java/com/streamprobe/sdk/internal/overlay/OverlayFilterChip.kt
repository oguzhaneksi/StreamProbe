package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * Custom chip-like widget built from [TextView] — no Material Components dependency.
 *
 * Exposes [isChecked] which swaps between a filled accent background (checked) and a
 * transparent fill with 1dp accent outline (unchecked). Mirrors the existing
 * programmatic-view + [OverlayDrawables] pattern used throughout the overlay.
 */
internal class OverlayFilterChip(context: Context) : TextView(context) {

    var isChecked: Boolean = false
        set(value) {
            field = value
            background = if (value) {
                OverlayDrawables.filterChipCheckedBackground(context)
            } else {
                OverlayDrawables.filterChipUncheckedBackground(context)
            }
            setTextColor(if (value) Color.WHITE else "#66B2FF".toColorInt())
        }

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        val hPad = context.dp(10f).toInt()
        val vPad = context.dp(4f).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        isClickable = true
        isFocusable = true
        // Initialise background and text colour
        isChecked = false
    }
}

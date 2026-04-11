package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.toColorInt

/**
 * Factory functions for all drawables used by the debug overlay.
 * Replacing the XML drawable resources keeps the SDK free of any generated R references
 * and avoids resource-merging conflicts with host apps.
 */
internal object OverlayDrawables {

    /** Semi-transparent dark panel background with fully-rounded corners. */
    fun overlayBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor("#E6101024".toColorInt())
        cornerRadius = context.dp(14f)
    }

    /** Slightly lighter header background with only the top corners rounded. */
    fun headerBackground(context: Context): GradientDrawable {
        val r = context.dp(14f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor("#331A1A3A".toColorInt())
            // cornerRadii order: topLeft, topRight, bottomRight, bottomLeft (each as x/y pair)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    /** Green dot indicating the currently active variant. */
    fun dotActive(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor("#30D158".toColorInt())
    }

    /** Gray dot for inactive variants. */
    fun dotInactive(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor("#555555".toColorInt())
    }
}

/** Converts a dp value to pixels using the display density of this [Context]. */
internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

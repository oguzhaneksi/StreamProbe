package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * A compact section header row ("VIDEO", "AUDIO", "SUBTITLES") used in the rendition list.
 */
internal class RenditionSectionHeaderView(context: Context) : TextView(context) {

    init {
        setTextColor("#80FFFFFF".toColorInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        letterSpacing = 0.1f
        val hPad = context.dp(10f).toInt()
        val vPad = context.dp(4f).toInt()
        setPadding(hPad, vPad, hPad, vPad)
    }

    fun bind(title: String) {
        text = title
    }
}

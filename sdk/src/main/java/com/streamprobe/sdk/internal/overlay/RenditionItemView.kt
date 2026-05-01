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
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo

/**
 * A single row in the rendition list — handles video, audio and subtitle items.
 * The architecture mirrors [VariantItemView] (dot + top row + bottom row) but binds
 * polymorphically via the [RenditionListItem] sealed type.
 */
internal class RenditionItemView(context: Context) : LinearLayout(context) {

    private val dot: View
    private val topLine: TextView
    private val bottomLine: TextView

    init {
        orientation = VERTICAL
        val hPad = dp(10f).toInt()
        val vPad = dp(6f).toInt()
        setPadding(hPad, vPad, hPad, vPad)

        val firstRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        dot = View(context).apply {
            background = OverlayDrawables.dotInactive()
        }
        firstRow.addView(dot, LayoutParams(dp(8f).toInt(), dp(8f).toInt()).also {
            it.marginEnd = dp(8f).toInt()
        })

        topLine = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        firstRow.addView(topLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(firstRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bottomLine = TextView(context).apply {
            setTextColor("#66FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        addView(bottomLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(16f).toInt()  // dot(8dp) + dot marginEnd(8dp)
            it.topMargin = dp(2f).toInt()
        })
    }

    fun bind(
        item: RenditionListItem,
        activeVideo: ActiveTrackInfo? = null,
        activeAudio: AudioTrackInfo? = null,
        activeSubtitle: SubtitleTrackInfo? = null,
    ) {
        when (item) {
            is RenditionListItem.Video -> bindVideo(item, activeVideo)
            is RenditionListItem.Audio -> bindAudio(item, activeAudio)
            is RenditionListItem.Subtitle -> bindSubtitle(item, activeSubtitle)
            is RenditionListItem.SectionHeader -> { /* handled by separate view holder */ }
        }
    }

    private fun bindVideo(item: RenditionListItem.Video, active: ActiveTrackInfo?) {
        val info = item.info
        val isActive = active != null &&
            info.width == active.width &&
            info.height == active.height &&
            info.bitrate == active.bitrate

        dot.background = if (isActive) OverlayDrawables.dotActive() else OverlayDrawables.dotInactive()
        topLine.text = buildString {
            append(OverlayFormatters.formatResolution(info.width, info.height))
            append("  \u00b7  ")
            append(OverlayFormatters.formatBitrate(info.bitrate))
        }
        bottomLine.text = info.codecs ?: ""
        bottomLine.isVisible = !info.codecs.isNullOrEmpty()
    }

    private fun bindAudio(item: RenditionListItem.Audio, active: AudioTrackInfo?) {
        val info = item.info
        val isActive = active != null &&
            info.language == active.language &&
            info.codecs == active.codecs &&
            info.isMuxed == active.isMuxed

        dot.background = if (isActive) OverlayDrawables.dotActive() else OverlayDrawables.dotInactive()

        val topParts = mutableListOf<String>()
        val displayName = info.label
            ?: info.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } }
        if (!displayName.isNullOrBlank()) topParts += displayName
        val channels = when (info.channelCount) {
            1    -> "mono"
            2    -> "stereo"
            6    -> "5.1"
            8    -> "7.1"
            else -> if (info.channelCount > 0) "${info.channelCount}ch" else null
        }
        if (channels != null) topParts += channels
        if (info.bitrate > 0) topParts += OverlayFormatters.formatBitrate(info.bitrate)
        topLine.text = topParts.joinToString("  \u00b7  ").ifBlank { "Audio" }

        val bottomParts = mutableListOf<String>()
        info.codecs?.let { bottomParts += it }
        if (info.isMuxed) bottomParts += "muxed"
        bottomLine.text = bottomParts.joinToString("  \u00b7  ")
        bottomLine.isVisible = bottomParts.isNotEmpty()
    }

    private fun bindSubtitle(item: RenditionListItem.Subtitle, active: SubtitleTrackInfo?) {
        val info = item.info
        val isActive = active != null &&
            info.language == active.language &&
            info.kind == active.kind &&
            info.mimeType == active.mimeType

        dot.background = if (isActive) OverlayDrawables.dotActive() else OverlayDrawables.dotInactive()

        val topParts = mutableListOf<String>()
        val displayName = info.label
            ?: info.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } }
        if (!displayName.isNullOrBlank()) topParts += displayName
        if (info.kind != SubtitleKind.SIDECAR) topParts += "(CC)"
        topLine.text = topParts.joinToString("  ").ifBlank { "Subtitle" }

        val mimeShort = when (info.mimeType) {
            "text/vtt", "application/x-media3-webvtt" -> "WebVTT"
            "application/ttml+xml"                    -> "TTML"
            "application/x-subrip"                    -> "SRT"
            "text/x-ssa"                              -> "SSA"
            else -> info.mimeType?.substringAfterLast("/")
        }
        bottomLine.text = mimeShort ?: ""
        bottomLine.isVisible = !mimeShort.isNullOrBlank()
    }

    private fun dp(value: Float) = context.dp(value)
}

package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.toColorInt
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.SegmentTrackType
import kotlin.math.roundToInt

/**
 * Factory functions for all drawables used by the debug overlay.
 * Replacing the XML drawable resources keeps the SDK free of any generated R references
 * and avoids resource-merging conflicts with host apps.
 */
internal object OverlayDrawables {
    /** Semi-transparent dark panel background with fully-rounded corners. */
    fun overlayBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
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
    fun dotActive(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#30D158".toColorInt())
        }

    /** Gray dot for inactive variants. */
    fun dotInactive(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#555555".toColorInt())
        }

    /** Filled accent background for a checked filter chip. */
    fun filterChipCheckedBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor("#66B2FF".toColorInt())
            cornerRadius = context.dp(12f)
        }

    /** Transparent background with accent outline for an unchecked filter chip. */
    fun filterChipUncheckedBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = context.dp(12f)
            setStroke(context.dp(1f).roundToInt().coerceAtLeast(1), "#66B2FF".toColorInt())
        }

    /** Colored dot reflecting a segment's CDN cache status. */
    fun cacheDot(status: CacheStatus): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when (status) {
                    CacheStatus.HIT -> "#30D158".toColorInt()
                    CacheStatus.MISS -> "#FF453A".toColorInt()
                    CacheStatus.STALE -> "#FF9F0A".toColorInt()
                    CacheStatus.BYPASS -> "#BF5AF2".toColorInt()
                    CacheStatus.UNKNOWN -> "#555555".toColorInt()
                },
            )
        }

    /** Oval dot color-coded by [ErrorCategory]. */
    fun errorCategoryDot(category: ErrorCategory): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when (category) {
                    ErrorCategory.LOAD_ERROR -> "#FF453A".toColorInt() // Red
                    ErrorCategory.VIDEO_CODEC_ERROR -> "#FF9F0A".toColorInt() // Orange
                    ErrorCategory.DROPPED_FRAMES -> "#FFD60A".toColorInt() // Yellow
                    ErrorCategory.AUDIO_SINK_ERROR -> "#BF5AF2".toColorInt() // Purple
                    ErrorCategory.AUDIO_CODEC_ERROR -> "#30D158".toColorInt() // Green
                    ErrorCategory.DRM_ERROR -> "#64D2FF".toColorInt() // Cyan
                },
            )
        }

    /** Oval dot color-coded by [DrmSessionEvent] subtype. */
    fun drmEventDot(event: DrmSessionEvent): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when (event) {
                    is DrmSessionEvent.SessionAcquired -> "#0A84FF".toColorInt() // Blue
                    is DrmSessionEvent.KeysLoaded -> "#30D158".toColorInt() // Green
                    is DrmSessionEvent.SessionReleased -> "#8E8E93".toColorInt() // Gray
                    is DrmSessionEvent.SessionError -> "#64D2FF".toColorInt() // Cyan (matches errors-tab)
                },
            )
        }

    /**
     * Solid red pill for the header error indicator. Caller must set the corner radius via
     * [GradientDrawable.setCornerRadius] after measuring (consistent with how the rest of the
     * file avoids Context-bound dp conversion in factory functions).
     */
    fun errorIndicatorBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor("#FF453A".toColorInt())
        }

    /** Rounded color-coded pill for a segment's track-type badge (V / A / T). */
    fun trackBadge(trackType: SegmentTrackType): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(
                when (trackType) {
                    SegmentTrackType.VIDEO -> "#4FC3F7".toColorInt()
                    SegmentTrackType.AUDIO -> "#A5D6A7".toColorInt()
                    SegmentTrackType.TEXT -> "#CE93D8".toColorInt()
                    SegmentTrackType.UNKNOWN -> "#555555".toColorInt()
                },
            )
        }
}

/** Converts a dp value to pixels using the display density of this [Context]. */
internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

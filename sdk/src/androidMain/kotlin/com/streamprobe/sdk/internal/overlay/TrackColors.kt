package com.streamprobe.sdk.internal.overlay

import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt

/**
 * Shared track-type accent palette: video = light blue, audio = light green,
 * text = light purple, unknown = gray.
 *
 * Defined once here so the segment-badge pill ([OverlayDrawables.trackBadge]) and the
 * switch-timeline type labels ([SwitchTimelineItemView]) can't drift apart.
 */
internal object TrackColors {
    @ColorInt val VIDEO = "#4FC3F7".toColorInt()

    @ColorInt val AUDIO = "#A5D6A7".toColorInt()

    @ColorInt val TEXT = "#CE93D8".toColorInt()

    @ColorInt val UNKNOWN = "#555555".toColorInt()
}

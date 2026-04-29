package com.streamprobe.sdk.internal.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView

/**
 * Programmatic full overlay panel view.
 *
 * Replaces `streamprobe_overlay.xml`. The layout hierarchy is constructed entirely in [init]
 * so no inflation or [com.streamprobe.sdk.R] references are required. All child views that
 * [OverlayManager] needs to interact with are exposed as public properties.
 *
 * Supports two orientations:
 * - **Portrait** (`isLandscape = false`): vertical stack — stats, chip row, then the list.
 * - **Landscape** (`isLandscape = true`): horizontal split — left column for stats, right column
 *   for the chip row and list.
 *
 * When the host Activity declares `android:configChanges` that covers orientation, the system
 * calls [onConfigurationChanged] on all attached views. [OverlayPanelView] forwards only
 * orientation changes to [onOrientationChanged] so [OverlayManager] can rebuild the panel
 * in place.
 */
@SuppressLint("ViewConstructor")
internal class OverlayPanelView(
    context: Context,
    isLandscape: Boolean,
    bodyMaxHeightPx: Int,
) : LinearLayout(context) {

    val header: LinearLayout
    val collapseBtn: TextView
    val errorIndicator: TextView
    val errorsViewHeader: LinearLayout
    val backButton: TextView
    val errorsTitle: TextView
    val clearButton: TextView
    val shareButton: TextView
    val body: LinearLayout
    val activeTrackView: TextView
    val latestSegmentView: TextView
    val cdnStatusView: TextView
    val variantList: RecyclerView
    val variantsChip: OverlayFilterChip
    val segmentsChip: OverlayFilterChip
    val abrChip: OverlayFilterChip

    /** Set by [OverlayManager] to be notified when the device orientation changes. */
    var onOrientationChanged: (() -> Unit)? = null
    private var lastKnownOrientation: Int = resources.configuration.orientation

    init {
        orientation = VERTICAL
        background = OverlayDrawables.overlayBackground(context)
        elevation = dp(8f)

        // ── Header bar ────────────────────────────────────────────────────────

        header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OverlayDrawables.headerBackground(context)
            val hStartPad = dp(14f).toInt()
            val hEndPad = dp(6f).toInt()
            setPadding(hStartPad, 0, hEndPad, 0)
        }

        val titleView = TextView(context).apply {
            text = "StreamProbe"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.04f
        }
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // ── Error indicator pill ──────────────────────────────────────────────

        errorIndicator = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            visibility = View.GONE
            minWidth = dp(48f).toInt()
            minHeight = dp(44f).toInt()
            gravity = Gravity.CENTER
            val hPad = dp(6f).toInt()
            setPadding(hPad, 0, hPad, 0)
            val bg = OverlayDrawables.errorIndicatorBackground()
            bg.cornerRadius = dp(10f)
            background = bg
        }
        header.addView(errorIndicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = dp(4f).toInt()
        })

        collapseBtn = TextView(context).apply {
            text = "▾"
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            contentDescription = "Collapse"
            val rippleValue = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleValue, true)
                && rippleValue.resourceId != 0
            ) {
                setBackgroundResource(rippleValue.resourceId)
            }
        }
        val btnSize = dp(32f).toInt()
        header.addView(collapseBtn, LayoutParams(btnSize, btnSize))

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f).toInt()))

        // ── Shared data views ─────────────────────────────────────────────────

        activeTrackView = TextView(context).apply {
            text = "Loading\u2026"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        latestSegmentView = TextView(context).apply {
            text = "\u2014"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        cdnStatusView = TextView(context).apply {
            text = "\u2014"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        // ── Filter chip row ───────────────────────────────────────────────────

        variantsChip = OverlayFilterChip(context).apply {
            text = "Variants"
            isChecked = true
        }
        segmentsChip = OverlayFilterChip(context).apply {
            text = "Segments"
            isChecked = false
        }
        abrChip = OverlayFilterChip(context).apply {
            text = "ABR"
            isChecked = false
        }
        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(variantsChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = dp(6f).toInt()
            })
            addView(segmentsChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(abrChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(6f).toInt()
            })
        }

        // ── Errors view header (replaces chip row when in ERRORS mode) ─────────

        backButton = TextView(context).apply {
            text = "← Back"
            setTextColor("#66B2FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            contentDescription = "Back to previous view"
            minWidth = dp(48f).toInt()
            minHeight = dp(32f).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }

        errorsTitle = TextView(context).apply {
            text = "Errors"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        clearButton = TextView(context).apply {
            text = "Clear"
            setTextColor("#66B2FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            contentDescription = "Clear errors"
            minWidth = dp(48f).toInt()
            minHeight = dp(32f).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }

        shareButton = TextView(context).apply {
            text = "↗"
            setTextColor("#66B2FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentDescription = "Share errors"
            minWidth = dp(48f).toInt()
            minHeight = dp(32f).toInt()
            gravity = Gravity.CENTER
        }

        errorsViewHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(backButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(errorsTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(clearButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(shareButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        // ── Bounded RecyclerView ──────────────────────────────────────────────

        variantList = BoundedRecyclerView(context, bodyMaxHeightPx)
        variantList.isNestedScrollingEnabled = true
        variantList.isVerticalScrollBarEnabled = true
        variantList.isVerticalFadingEdgeEnabled = true
        variantList.setFadingEdgeLength(context.dp(12f).toInt())

        // ── Body (collapsible) ────────────────────────────────────────────────

        body = LinearLayout(context).apply {
            orientation = if (isLandscape) HORIZONTAL else VERTICAL
            val pad = dp(14f).toInt()
            val topPad = dp(10f).toInt()
            setPadding(pad, topPad, pad, pad)
        }

        if (isLandscape) {
            buildLandscapeBody(body, chipRow)
        } else {
            buildPortraitBody(body, chipRow)
        }

        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ── Layout builders ───────────────────────────────────────────────────────

    private fun buildPortraitBody(body: LinearLayout, chipRow: LinearLayout) {
        body.addView(sectionLabel(context, "ACTIVE TRACK"), marginBottom = dp(4f).toInt())
        body.addView(activeTrackView, marginBottom = dp(12f).toInt())
        body.addView(sectionLabel(context, "LATEST SEGMENT"), marginBottom = dp(4f).toInt())
        body.addView(latestSegmentView, marginBottom = dp(8f).toInt())
        body.addView(sectionLabel(context, "CDN STATUS"), marginBottom = dp(4f).toInt())
        body.addView(cdnStatusView, marginBottom = dp(12f).toInt())
        body.addView(chipRow, marginBottom = dp(6f).toInt())
        body.addView(errorsViewHeader, marginBottom = dp(6f).toInt())
        body.addView(variantList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildLandscapeBody(body: LinearLayout, chipRow: LinearLayout) {
        // Left column — stat sections
        val leftCol = LinearLayout(context).apply { orientation = VERTICAL }
        leftCol.addView(sectionLabel(context, "ACTIVE TRACK"), marginBottom = dp(4f).toInt())
        leftCol.addView(activeTrackView, marginBottom = dp(12f).toInt())
        leftCol.addView(sectionLabel(context, "LATEST SEGMENT"), marginBottom = dp(4f).toInt())
        leftCol.addView(latestSegmentView, marginBottom = dp(8f).toInt())
        leftCol.addView(sectionLabel(context, "CDN STATUS"), marginBottom = dp(4f).toInt())
        leftCol.addView(cdnStatusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Right column — chip row + list
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(chipRow, marginBottom = dp(6f).toInt())
        rightCol.addView(errorsViewHeader, marginBottom = dp(6f).toInt())
        rightCol.addView(variantList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body.addView(leftCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginEnd = dp(12f).toInt()
        })
        body.addView(rightCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    // ── Configuration change ──────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newOrientation = newConfig.orientation
        if (newOrientation != lastKnownOrientation) {
            lastKnownOrientation = newOrientation
            onOrientationChanged?.invoke()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(value: Float) = context.dp(value)

    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor("#80FFFFFF".toColorInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private fun LinearLayout.addView(view: View, marginBottom: Int) {
        addView(view, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = marginBottom })
    }

    // ── Bounded subclass ──────────────────────────────────────────────────────

    /**
     * A [RecyclerView] that caps its measured height at [maxHeightPx].
     * Standard RecyclerView does not respond to `android:maxHeight`, so this is required
     * when constructing the view programmatically.
     */
    private class BoundedRecyclerView(context: Context, private val maxHeightPx: Int) :
        RecyclerView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }
}

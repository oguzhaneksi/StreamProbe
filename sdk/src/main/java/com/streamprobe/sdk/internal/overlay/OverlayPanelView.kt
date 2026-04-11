package com.streamprobe.sdk.internal.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * The panel should be added to the host Activity via `addContentView()` with a fixed 320dp
 * width. [OverlayManager] owns that responsibility.
 */
internal class OverlayPanelView(context: Context) : LinearLayout(context) {

    val header: LinearLayout
    val collapseBtn: TextView
    val body: LinearLayout
    val activeTrackView: TextView
    val variantList: RecyclerView
    val manifestToggle: TextView
    val manifestScroll: ScrollView
    val manifestText: TextView

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

        collapseBtn = TextView(context).apply {
            text = "▾"
            setTextColor("#99FFFFFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            contentDescription = "Collapse"
            // Apply ripple from the host's theme if available
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

        // ── Body (collapsible) ────────────────────────────────────────────────

        body = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = dp(14f).toInt()
            val topPad = dp(10f).toInt()
            setPadding(pad, topPad, pad, pad)
        }

        // "ACTIVE TRACK" section label
        val activeLabel = sectionLabel(context, "ACTIVE TRACK")
        body.addView(activeLabel, marginBottom = dp(4f).toInt())

        // Active track value
        activeTrackView = TextView(context).apply {
            text = "Loading\u2026"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        body.addView(activeTrackView, marginBottom = dp(12f).toInt())

        // "VARIANTS" section label
        val variantsLabel = sectionLabel(context, "VARIANTS")
        body.addView(variantsLabel, marginBottom = dp(4f).toInt())

        // Variant RecyclerView (bounded to max 180dp height)
        variantList = BoundedRecyclerView(context, dp(180f).toInt())
        variantList.isNestedScrollingEnabled = true
        body.addView(variantList, marginBottom = dp(10f).toInt())

        // Manifest summary toggle link
        manifestToggle = TextView(context).apply {
            text = "Show Parsed Summary \u25b8"
            setTextColor("#66B2FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        body.addView(manifestToggle, marginBottom = dp(4f).toInt())

        // Manifest text inside a bounded ScrollView (initially hidden)
        manifestText = TextView(context).apply {
            setTextColor("#B0B0B0".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor("#1A1A2E".toColorInt())
            val p = dp(8f).toInt()
            setPadding(p, p, p, p)
        }

        manifestScroll = BoundedScrollView(context, dp(160f).toInt()).apply {
            visibility = GONE
            addView(manifestText)
        }
        body.addView(manifestScroll, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        )

        addView(body, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(value: Float) = context.dp(value)

    /** Creates the styled uppercase section-label TextView ("ACTIVE TRACK", "VARIANTS"). */
    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor("#80FFFFFF".toColorInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        letterSpacing = 0.1f
    }

    /**
     * Convenience overload that wraps [view] in [LinearLayout.LayoutParams] with a bottom margin
     * and calls [LinearLayout.addView].
     */
    private fun LinearLayout.addView(view: View, marginBottom: Int) {
        addView(view, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = marginBottom })
    }

    // ── Bounded subclasses ────────────────────────────────────────────────────

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

    /**
     * A [ScrollView] that caps its measured height at [maxHeightPx].
     */
    private class BoundedScrollView(context: Context, private val maxHeightPx: Int) :
        ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }
}

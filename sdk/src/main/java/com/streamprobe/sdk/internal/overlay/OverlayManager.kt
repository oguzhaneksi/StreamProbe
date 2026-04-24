package com.streamprobe.sdk.internal.overlay

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.internal.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages the debug overlay lifecycle:
 * - [show] creates an [OverlayPanelView] sized and oriented for the current screen configuration,
 *   adds it via [ComponentActivity.addContentView], sets up drag, collapse/expand, chip
 *   switcher, and starts observing [SessionStore]. A [DefaultLifecycleObserver] auto-hides
 *   the overlay when the Activity is destroyed, preventing leaks.
 * - [hide] removes the overlay, cancels observation, and removes the lifecycle observer.
 *
 * [viewMode] is preserved across orientation rebuilds so that if the user switched to Segments,
 * the rebuilt overlay shows Segments too.
 */
internal class OverlayManager(
    private val sessionStore: SessionStore,
) {

    private enum class ViewMode { VARIANTS, SEGMENTS, ABR }

    private var overlayView: OverlayPanelView? = null
    private var scope: CoroutineScope? = null
    private var variantAdapter: VariantListAdapter? = null
    private var segmentAdapter: SegmentTimelineAdapter? = null
    private var abrAdapter: AbrTimelineAdapter? = null
    private var isCollapsed = false
    private var viewMode = ViewMode.VARIANTS

    private var currentActivity: ComponentActivity? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    fun show(activity: ComponentActivity) {
        // Idempotent replace: if already shown, remove the old panel first.
        if (overlayView != null) hide()

        currentActivity = activity

        val isLandscape =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bodyMaxHeightPx = computeBodyMaxHeightPx(activity, isLandscape)
        val widthPx = computePanelWidthPx(activity, isLandscape)

        val overlay = OverlayPanelView(activity, isLandscape, bodyMaxHeightPx)
        activity.addContentView(overlay, FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT))
        overlayView = overlay

        overlay.onOrientationChanged = { currentActivity?.let { show(it) } }

        variantAdapter = VariantListAdapter()
        segmentAdapter = SegmentTimelineAdapter()
        abrAdapter = AbrTimelineAdapter()
        overlay.variantList.layoutManager = LinearLayoutManager(overlay.context)

        attachAutoScrollToEnd(overlay.variantList, segmentAdapter!!)
        attachAutoScrollToEnd(overlay.variantList, abrAdapter!!)

        setupDrag(overlay)
        setupCollapseToggle(overlay)
        setupChips(overlay)
        startObserving(overlay)
        attachLifecycle(activity)
    }

    fun hide() {
        scope?.cancel()
        scope = null
        variantAdapter = null
        segmentAdapter = null
        abrAdapter = null

        overlayView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlayView = null

        lifecycleObserver?.let { observer ->
            currentActivity?.lifecycle?.removeObserver(observer)
        }
        lifecycleObserver = null
        currentActivity = null
        isCollapsed = false
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private fun attachLifecycle(activity: ComponentActivity) {
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                hide()
            }
        }
        activity.lifecycle.addObserver(observer)
        lifecycleObserver = observer
    }

    // ── Sizing helpers ───────────────────────────────────────────────────────

    private fun computeBodyMaxHeightPx(activity: ComponentActivity, isLandscape: Boolean): Int {
        val dm = activity.resources.displayMetrics
        return if (isLandscape) {
            val targetPx = (dm.heightPixels * 0.55f).toInt()
            val minPx = (200 * dm.density).toInt()
            val maxPx = (360 * dm.density).toInt()
            targetPx.coerceIn(minPx, maxPx)
        } else {
            (180 * dm.density).toInt()
        }
    }

    private fun computePanelWidthPx(activity: ComponentActivity, isLandscape: Boolean): Int {
        val density = activity.resources.displayMetrics.density
        return ((if (isLandscape) 440 else 310) * density).toInt()
    }

    // ── Drag ────────────────────────────────────────────────────────────────

    private fun clampToParent(overlay: OverlayPanelView) {
        val parent = overlay.parent as? ViewGroup ?: return
        if (parent.width == 0 || parent.height == 0 || overlay.width == 0 || overlay.height == 0) return
        val insets: Insets = ViewCompat.getRootWindowInsets(overlay)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: Insets.NONE
        overlay.x = overlay.x.coerceIn(
            insets.left.toFloat(),
            (parent.width - overlay.width - insets.right).toFloat()
        )
        overlay.y = overlay.y.coerceIn(
            insets.top.toFloat(),
            (parent.height - overlay.height - insets.bottom).toFloat()
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(overlay: OverlayPanelView) {
        var dX = 0f
        var dY = 0f

        overlay.header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = overlay.x - event.rawX
                    dY = overlay.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlay.x = event.rawX + dX
                    overlay.y = event.rawY + dY
                    clampToParent(overlay)
                    true
                }
                else -> false
            }
        }
    }

    // ── Collapse / Expand ───────────────────────────────────────────────────

    private fun setupCollapseToggle(overlay: OverlayPanelView) {
        fun updateCollapseUi() {
            overlay.body.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            overlay.collapseBtn.rotation = if (isCollapsed) 180f else 0f
            overlay.collapseBtn.contentDescription = if (isCollapsed) "Expand" else "Collapse"
        }

        updateCollapseUi()

        overlay.collapseBtn.setOnClickListener {
            isCollapsed = !isCollapsed
            updateCollapseUi()
        }

        overlay.body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampToParent(overlay)
        }
    }

    // ── Chip switcher ────────────────────────────────────────────────────────

    private fun setupChips(overlay: OverlayPanelView) {
        applyViewMode(overlay, viewMode)

        overlay.variantsChip.setOnClickListener {
            viewMode = ViewMode.VARIANTS
            applyViewMode(overlay, viewMode)
        }
        overlay.segmentsChip.setOnClickListener {
            viewMode = ViewMode.SEGMENTS
            applyViewMode(overlay, viewMode)
        }
        overlay.abrChip.setOnClickListener {
            viewMode = ViewMode.ABR
            applyViewMode(overlay, viewMode)
        }
    }

    private fun applyViewMode(overlay: OverlayPanelView, mode: ViewMode) {
        overlay.variantsChip.isChecked = mode == ViewMode.VARIANTS
        overlay.segmentsChip.isChecked = mode == ViewMode.SEGMENTS
        overlay.abrChip.isChecked = mode == ViewMode.ABR
        overlay.variantList.adapter = when (mode) {
            ViewMode.VARIANTS -> variantAdapter
            ViewMode.SEGMENTS -> segmentAdapter
            ViewMode.ABR -> abrAdapter
        }
        if (mode == ViewMode.VARIANTS) {
            val pos = variantAdapter?.findPositionForTrack(variantAdapter?.activeTrack)
                ?: RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION) overlay.variantList.scrollToPosition(pos)
        }
    }

    private fun attachAutoScrollToEnd(list: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (list.adapter !== adapter) return
                val lm = list.layoutManager as? LinearLayoutManager ?: return
                val total = adapter.itemCount
                if (total == 0) return
                val last = lm.findLastCompletelyVisibleItemPosition()
                val threshold = total - itemCount - 2
                if (last !in 0..<threshold) {
                    list.scrollToPosition(total - 1)
                }
            }
        })
    }

    // ── Observation ─────────────────────────────────────────────────────────

    private fun startObserving(overlay: OverlayPanelView) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        scope?.launch {
            sessionStore.manifestInfo.collect { info ->
                if (info != null) {
                    variantAdapter?.submitList(info.variants)
                    overlay.variantList.post {
                        val pos = variantAdapter?.findPositionForTrack(variantAdapter?.activeTrack)
                            ?: RecyclerView.NO_POSITION
                        if (viewMode == ViewMode.VARIANTS && pos != RecyclerView.NO_POSITION) {
                            overlay.variantList.scrollToPosition(pos)
                        }
                    }
                }
            }
        }

        scope?.launch {
            sessionStore.activeTrack.collect { track ->
                overlay.activeTrackView.text = OverlayFormatters.formatActiveTrack(track)
                variantAdapter?.activeTrack = track
                if (viewMode == ViewMode.VARIANTS) {
                    val pos = variantAdapter?.findPositionForTrack(track) ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION) overlay.variantList.scrollToPosition(pos)
                }
            }
        }

        scope?.launch {
            sessionStore.latestSegmentMetric.collect { metric ->
                overlay.latestSegmentView.text = OverlayFormatters.formatSegmentMetric(metric)
                overlay.cdnStatusView.text = OverlayFormatters.formatCdnStatus(metric?.cdnInfo)
            }
        }

        scope?.launch {
            sessionStore.segmentMetrics.collect { metrics ->
                segmentAdapter?.submitList(metrics)
            }
        }

        scope?.launch {
            sessionStore.abrSwitchEvents.collect { events ->
                abrAdapter?.submitList(events)
            }
        }
    }

}

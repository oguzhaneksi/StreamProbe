package com.streamprobe.sdk.internal.overlay

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
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
    private enum class ViewMode { TRACKS, SEGMENTS, SWITCHES, DRM, ERRORS }

    private var overlayView: OverlayPanelView? = null
    private var scope: CoroutineScope? = null
    private var renditionAdapter: RenditionListAdapter? = null
    private var segmentAdapter: SegmentTimelineAdapter? = null
    private var switchAdapter: SwitchTimelineAdapter? = null
    private var drmAdapter: DrmTimelineAdapter? = null
    private var errorAdapter: ErrorTimelineAdapter? = null
    private var isCollapsed = false
    private var viewMode = ViewMode.TRACKS
    private var previousViewMode = ViewMode.TRACKS

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

        renditionAdapter = RenditionListAdapter()
        segmentAdapter = SegmentTimelineAdapter()
        switchAdapter = SwitchTimelineAdapter()
        drmAdapter = DrmTimelineAdapter()
        errorAdapter = ErrorTimelineAdapter()
        overlay.trackList.layoutManager = LinearLayoutManager(overlay.context)

        attachAutoScrollToEnd(overlay.trackList, segmentAdapter!!)
        attachAutoScrollToEnd(overlay.trackList, switchAdapter!!)
        attachAutoScrollToEnd(overlay.trackList, drmAdapter!!)
        attachAutoScrollToEnd(overlay.trackList, errorAdapter!!)

        setupDrag(overlay)
        setupCollapseToggle(overlay)
        setupChips(overlay)
        startObserving(overlay)
        attachLifecycle(activity)
    }

    fun hide() {
        scope?.cancel()
        scope = null
        renditionAdapter = null
        segmentAdapter = null
        switchAdapter = null
        drmAdapter = null
        errorAdapter = null

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

    @VisibleForTesting
    internal fun overlayViewForTest(): OverlayPanelView =
        overlayView
            ?: error("overlay not shown")

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private fun attachLifecycle(activity: ComponentActivity) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    hide()
                }
            }
        activity.lifecycle.addObserver(observer)
        lifecycleObserver = observer
    }

    // ── Sizing helpers ───────────────────────────────────────────────────────

    private fun computeBodyMaxHeightPx(
        activity: ComponentActivity,
        isLandscape: Boolean,
    ): Int {
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

    private fun computePanelWidthPx(
        activity: ComponentActivity,
        isLandscape: Boolean,
    ): Int {
        val density = activity.resources.displayMetrics.density
        return ((if (isLandscape) 540 else 310) * density).toInt()
    }

    // ── Drag ────────────────────────────────────────────────────────────────

    private fun clampToParent(overlay: OverlayPanelView) {
        val parent = overlay.parent as? ViewGroup ?: return
        val hasMissingDimensions =
            parent.width == 0 ||
                parent.height == 0 ||
                overlay.width == 0 ||
                overlay.height == 0
        if (hasMissingDimensions) return
        val insets: Insets =
            ViewCompat
                .getRootWindowInsets(overlay)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                ?: Insets.NONE
        val minX = insets.left.toFloat()
        val maxX = (parent.width - overlay.width - insets.right).toFloat().coerceAtLeast(minX)
        val minY = insets.top.toFloat()
        val maxY = (parent.height - overlay.height - insets.bottom).toFloat().coerceAtLeast(minY)

        overlay.x = overlay.x.coerceIn(minX, maxX)
        overlay.y = overlay.y.coerceIn(minY, maxY)
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

    private fun updateCollapseUi(overlay: OverlayPanelView) {
        overlay.body.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        overlay.collapseBtn.rotation = if (isCollapsed) 0f else 180f
        overlay.collapseBtn.contentDescription = if (isCollapsed) "Expand" else "Collapse"
    }

    private fun setupCollapseToggle(overlay: OverlayPanelView) {
        updateCollapseUi(overlay)

        overlay.collapseBtn.setOnClickListener {
            isCollapsed = !isCollapsed
            updateCollapseUi(overlay)
        }

        overlay.body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampToParent(overlay)
        }
    }

    // ── Chip switcher ────────────────────────────────────────────────────────

    private fun setupChips(overlay: OverlayPanelView) {
        applyViewMode(overlay, viewMode)

        overlay.tracksChip.setOnClickListener {
            viewMode = ViewMode.TRACKS
            applyViewMode(overlay, viewMode)
        }
        overlay.segmentsChip.setOnClickListener {
            viewMode = ViewMode.SEGMENTS
            applyViewMode(overlay, viewMode)
        }
        overlay.switchesChip.setOnClickListener {
            viewMode = ViewMode.SWITCHES
            applyViewMode(overlay, viewMode)
        }

        overlay.drmChip.setOnClickListener {
            viewMode = ViewMode.DRM
            applyViewMode(overlay, viewMode)
        }

        setupErrorIndicator(overlay)
    }

    private fun setupErrorIndicator(overlay: OverlayPanelView) {
        overlay.errorIndicator.setOnClickListener {
            if (isCollapsed) {
                isCollapsed = false
                updateCollapseUi(overlay)
            }
            if (viewMode != ViewMode.ERRORS) {
                previousViewMode = viewMode
                viewMode = ViewMode.ERRORS
                applyViewMode(overlay, viewMode)
            }
        }

        overlay.backButton.setOnClickListener {
            viewMode = previousViewMode
            applyViewMode(overlay, viewMode)
        }

        overlay.clearButton.setOnClickListener {
            sessionStore.clearPlaybackErrors()
        }

        overlay.shareButton.setOnClickListener {
            val activity = currentActivity ?: return@setOnClickListener
            val errors = sessionStore.playbackErrors.value
            val text =
                OverlayFormatters.formatErrorsForExport(
                    errors = errors,
                    baseTimestampMs = errors.firstOrNull()?.timestampMs ?: 0L,
                )
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            activity.startActivity(Intent.createChooser(intent, "Share errors"))
        }
    }

    private fun applyViewMode(
        overlay: OverlayPanelView,
        mode: ViewMode,
    ) {
        val isErrors = mode == ViewMode.ERRORS
        overlay.tracksChip.isChecked = mode == ViewMode.TRACKS
        overlay.segmentsChip.isChecked = mode == ViewMode.SEGMENTS
        overlay.switchesChip.isChecked = mode == ViewMode.SWITCHES
        overlay.drmChip.isChecked = mode == ViewMode.DRM

        // Show chip row or errors header
        val chipRowVisibility = if (isErrors) View.GONE else View.VISIBLE
        val chipRow = overlay.tracksChip.parent as? View
        if (chipRow != null) {
            chipRow.visibility = chipRowVisibility
        } else {
            overlay.tracksChip.visibility = chipRowVisibility
            overlay.segmentsChip.visibility = chipRowVisibility
            overlay.switchesChip.visibility = chipRowVisibility
            overlay.drmChip.visibility = chipRowVisibility
        }
        overlay.errorsViewHeader.visibility = if (isErrors) View.VISIBLE else View.GONE

        overlay.trackList.adapter =
            when (mode) {
                ViewMode.TRACKS -> renditionAdapter
                ViewMode.SEGMENTS -> segmentAdapter
                ViewMode.SWITCHES -> switchAdapter
                ViewMode.DRM -> drmAdapter
                ViewMode.ERRORS -> errorAdapter
            }
        if (mode == ViewMode.ERRORS) {
            val errorCount = sessionStore.playbackErrors.value.size
            overlay.errorsTitle.text = "Errors ($errorCount)"
        }
    }

    private fun attachAutoScrollToEnd(
        list: RecyclerView,
        adapter: RecyclerView.Adapter<*>,
    ) {
        adapter.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) {
                    if (list.adapter !== adapter) return
                    val lm = list.layoutManager as? LinearLayoutManager ?: return
                    val total = adapter.itemCount
                    if (total > 0) {
                        val last = lm.findLastCompletelyVisibleItemPosition()
                        val threshold = total - itemCount - 2
                        if (last !in 0..<threshold) {
                            list.scrollToPosition(total - 1)
                        }
                    }
                }
            },
        )
    }

    // ── Observation ─────────────────────────────────────────────────────────

    private fun startObserving(overlay: OverlayPanelView) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        observeTrackListInfo()

        scope?.launch {
            sessionStore.activeTrack.collect { track ->
                overlay.activeTrackView.text = OverlayFormatters.formatActiveTrack(track)
            }
        }

        scope?.launch {
            sessionStore.activeAudioTrack.collect { audio ->
                overlay.activeAudioView.text = OverlayFormatters.formatActiveAudio(audio)
            }
        }

        scope?.launch {
            sessionStore.activeSubtitleTrack.collect { subtitle ->
                overlay.activeSubtitleView.text = OverlayFormatters.formatActiveSubtitle(subtitle)
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
            sessionStore.trackSwitchEvents.collect { events ->
                switchAdapter?.submitList(events)
            }
        }

        observeDrm(overlay)
        observePlaybackErrors(overlay)
    }

    private fun observeTrackListInfo() {
        scope?.launch {
            sessionStore.trackListInfo.collect { info ->
                if (info != null) {
                    val items =
                        buildList {
                            if (info.variants.isNotEmpty()) {
                                add(RenditionListItem.SectionHeader("VIDEO"))
                                info.variants.forEach { add(RenditionListItem.Video(it)) }
                            }
                            if (info.audioTracks.isNotEmpty()) {
                                add(RenditionListItem.SectionHeader("AUDIO"))
                                info.audioTracks.forEach { add(RenditionListItem.Audio(it)) }
                            }
                            if (info.subtitleTracks.isNotEmpty()) {
                                add(RenditionListItem.SectionHeader("SUBTITLES"))
                                info.subtitleTracks.forEach { add(RenditionListItem.Subtitle(it)) }
                            }
                        }
                    renditionAdapter?.submitList(items)
                }
            }
        }
    }

    private fun observeDrm(overlay: OverlayPanelView) {
        scope?.launch {
            sessionStore.drmSessionEvents.collect { events ->
                drmAdapter?.submitList(events)
                val hasDrm = events.isNotEmpty()
                overlay.drmChip.visibility = if (hasDrm) View.VISIBLE else View.GONE
                overlay.drmSectionLabel.visibility = if (hasDrm) View.VISIBLE else View.GONE
                overlay.drmStatusView.visibility = if (hasDrm) View.VISIBLE else View.GONE
                if (!hasDrm && viewMode == ViewMode.DRM) {
                    viewMode = ViewMode.TRACKS
                    applyViewMode(overlay, viewMode)
                }
            }
        }
        scope?.launch {
            sessionStore.currentDrmState.collect { drmInfo ->
                overlay.drmStatusView.text = DrmFormatters.formatDrmStatus(drmInfo)
            }
        }
    }

    private fun observePlaybackErrors(overlay: OverlayPanelView) {
        scope?.launch {
            sessionStore.playbackErrors.collect { errors ->
                errorAdapter?.submitList(errors)

                // Update the header indicator
                if (errors.isEmpty()) {
                    overlay.errorIndicator.visibility = View.GONE
                } else {
                    overlay.errorIndicator.text = "⚠ ${errors.size}"
                    overlay.errorIndicator.contentDescription =
                        "${errors.size} background errors. Tap to view."
                    overlay.errorIndicator.visibility = View.VISIBLE
                }

                // Keep errors title in sync when in errors mode
                if (viewMode == ViewMode.ERRORS) {
                    overlay.errorsTitle.text = "Errors (${errors.size})"
                }
            }
        }
    }
}

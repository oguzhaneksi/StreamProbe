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
import com.streamprobe.sdk.internal.presenter.OverlayPresenter
import com.streamprobe.sdk.internal.presenter.OverlayViewState
import com.streamprobe.sdk.internal.presenter.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Renders the debug overlay. All platform-independent logic (the [ViewMode] state machine,
 * collapse, DRM auto-fallback, error counter, header formatting, rendition assembly) lives in the
 * common [OverlayPresenter]; this class is a thin Android renderer of its
 * [OverlayPresenter.viewState].
 *
 * - [show] creates an [OverlayPanelView] sized and oriented for the current screen configuration,
 *   adds it via [ComponentActivity.addContentView], sets up drag and the click handlers (which
 *   forward to the presenter), and starts a single `viewState.collect { render(it) }`. A
 *   [DefaultLifecycleObserver] auto-hides the overlay when the Activity is destroyed.
 * - [hide] removes the overlay, cancels observation, and removes the lifecycle observer.
 *
 * View-mode and collapse state live in the [OverlayPresenter], which is created once and reused
 * across orientation rebuilds (and across [hide]/[show] cycles). This preserves the selected
 * [ViewMode] across rebuilds — e.g. if the user switched to Segments, the rebuilt overlay shows
 * Segments too. The presenter holds no View references, so reusing it is leak-free.
 */
internal class OverlayManager(
    private val sessionStore: SessionStore,
) {
    private var overlayView: OverlayPanelView? = null
    private var scope: CoroutineScope? = null
    private var presenter: OverlayPresenter? = null
    private var renditionAdapter: RenditionListAdapter? = null
    private var segmentAdapter: SegmentTimelineAdapter? = null
    private var switchAdapter: SwitchTimelineAdapter? = null
    private var drmAdapter: DrmTimelineAdapter? = null
    private var errorAdapter: ErrorTimelineAdapter? = null
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
        val seg = SegmentTimelineAdapter().also { segmentAdapter = it }
        val sw = SwitchTimelineAdapter().also { switchAdapter = it }
        val drm = DrmTimelineAdapter().also { drmAdapter = it }
        val err = ErrorTimelineAdapter().also { errorAdapter = it }
        overlay.trackList.layoutManager = LinearLayoutManager(overlay.context)

        attachAutoScrollToEnd(overlay.trackList, seg)
        attachAutoScrollToEnd(overlay.trackList, sw)
        attachAutoScrollToEnd(overlay.trackList, drm)
        attachAutoScrollToEnd(overlay.trackList, err)

        // Reused across rebuilds so the selected ViewMode survives orientation changes.
        val overlayPresenter = presenter ?: OverlayPresenter(sessionStore).also { presenter = it }

        setupDrag(overlay)
        setupInteractions(overlay, overlayPresenter)
        overlay.body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampToParent(overlay)
        }

        // Main.immediate fires the collect synchronously when already on the Main thread,
        // so no manual renderNow() is needed after intent calls.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        overlayPresenter.start(newScope)
        newScope.launch {
            overlayPresenter.viewState.collect { render(overlay, it) }
        }

        attachLifecycle(activity)
    }

    fun hide() {
        scope?.cancel()
        scope = null
        // presenter is intentionally retained across hide()/show() to preserve ViewMode + collapse.
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

    // ── Interactions (forward to presenter, then render synchronously) ────────

    private fun setupInteractions(
        overlay: OverlayPanelView,
        presenter: OverlayPresenter,
    ) {
        overlay.collapseBtn.setOnClickListener { presenter.onCollapseToggled() }
        overlay.tracksChip.setOnClickListener { presenter.onChipSelected(ViewMode.TRACKS) }
        overlay.segmentsChip.setOnClickListener { presenter.onChipSelected(ViewMode.SEGMENTS) }
        overlay.switchesChip.setOnClickListener { presenter.onChipSelected(ViewMode.SWITCHES) }
        overlay.drmChip.setOnClickListener { presenter.onChipSelected(ViewMode.DRM) }
        overlay.errorIndicator.setOnClickListener { presenter.onErrorIndicatorTapped() }
        overlay.backButton.setOnClickListener { presenter.onBackPressed() }
        overlay.clearButton.setOnClickListener { presenter.onClearErrorsClicked() }
        overlay.shareButton.setOnClickListener { shareErrors() }
    }

    private fun shareErrors() {
        val activity = currentActivity ?: return
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

    // ── Auto-scroll ───────────────────────────────────────────────────────────

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

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun render(
        overlay: OverlayPanelView,
        state: OverlayViewState,
    ) {
        renderCollapse(overlay, state)
        renderStats(overlay, state)
        renderChrome(overlay, state)
        renderLists(overlay, state)
    }

    private fun renderCollapse(
        overlay: OverlayPanelView,
        state: OverlayViewState,
    ) {
        overlay.body.visibility = if (state.isCollapsed) View.GONE else View.VISIBLE
        overlay.collapseBtn.rotation = if (state.isCollapsed) 0f else 180f
        overlay.collapseBtn.contentDescription = if (state.isCollapsed) "Expand" else "Collapse"
    }

    private fun renderStats(
        overlay: OverlayPanelView,
        state: OverlayViewState,
    ) {
        val stats = state.stats
        overlay.activeTrackView.text = stats.activeTrackText
        overlay.activeAudioView.text = stats.activeAudioText
        overlay.activeSubtitleView.text = stats.activeSubtitleText
        overlay.latestSegmentView.text = stats.latestSegmentText
        overlay.cdnStatusView.text = stats.cdnStatusText
        overlay.drmStatusView.text = stats.drmStatusText

        val drmVisibility = if (stats.drmVisible) View.VISIBLE else View.GONE
        overlay.drmChip.visibility = drmVisibility
        overlay.drmSectionLabel.visibility = drmVisibility
        overlay.drmStatusView.visibility = drmVisibility
    }

    private fun renderChrome(
        overlay: OverlayPanelView,
        state: OverlayViewState,
    ) {
        overlay.tracksChip.isChecked = state.mode == ViewMode.TRACKS
        overlay.segmentsChip.isChecked = state.mode == ViewMode.SEGMENTS
        overlay.switchesChip.isChecked = state.mode == ViewMode.SWITCHES
        overlay.drmChip.isChecked = state.mode == ViewMode.DRM

        val chipRowVisibility = if (!state.isErrorsMode) View.VISIBLE else View.GONE
        val chipRow = overlay.tracksChip.parent as? View
        if (chipRow != null) {
            chipRow.visibility = chipRowVisibility
        } else {
            overlay.tracksChip.visibility = chipRowVisibility
            overlay.segmentsChip.visibility = chipRowVisibility
            overlay.switchesChip.visibility = chipRowVisibility
            overlay.drmChip.visibility = chipRowVisibility
        }
        overlay.errorsViewHeader.visibility = if (state.isErrorsMode) View.VISIBLE else View.GONE
        overlay.errorsTitle.text = state.errorsTitle

        val indicator = state.errorIndicator
        if (indicator == null) {
            overlay.errorIndicator.visibility = View.GONE
        } else {
            overlay.errorIndicator.text = indicator.text
            overlay.errorIndicator.contentDescription = indicator.contentDescription
            overlay.errorIndicator.visibility = View.VISIBLE
        }
    }

    private fun renderLists(
        overlay: OverlayPanelView,
        state: OverlayViewState,
    ) {
        // Attach the target adapter FIRST so the auto-scroll observer's `list.adapter === adapter`
        // guard is satisfied before submitList fires onItemRangeInserted on the initial load.
        val targetAdapter = adapterForMode(state.mode)
        if (overlay.trackList.adapter !== targetAdapter) {
            overlay.trackList.adapter = targetAdapter
        }

        val lists = state.lists
        renditionAdapter?.submitList(lists.renditionRows)
        segmentAdapter?.submitList(lists.segments)
        switchAdapter?.submitList(lists.switches)
        drmAdapter?.submitList(lists.drmEvents)
        errorAdapter?.submitList(lists.errors)
    }

    private fun adapterForMode(mode: ViewMode): RecyclerView.Adapter<*>? =
        when (mode) {
            ViewMode.TRACKS -> renditionAdapter
            ViewMode.SEGMENTS -> segmentAdapter
            ViewMode.SWITCHES -> switchAdapter
            ViewMode.DRM -> drmAdapter
            ViewMode.ERRORS -> errorAdapter
        }
}

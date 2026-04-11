package com.streamprobe.sdk.internal.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Manages the debug overlay lifecycle:
 * - [show] creates an [OverlayPanelView], adds it via [Activity.addContentView],
 *   sets up drag, collapse/expand, and starts observing [SessionStore].
 * - [hide] removes the overlay and cancels observation.
 */
internal class OverlayManager(
    private val sessionStore: SessionStore,
) {

    private var overlayView: OverlayPanelView? = null
    private var scope: CoroutineScope? = null
    private var adapter: VariantListAdapter? = null
    private var isCollapsed = false

    fun show(activity: Activity) {
        if (overlayView != null) return

        val overlay = OverlayPanelView(activity)
        val widthPx = (320 * activity.resources.displayMetrics.density).toInt()
        val params = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        activity.addContentView(overlay, params)
        overlayView = overlay

        setupDrag(overlay)
        setupCollapseToggle(overlay)
        setupManifestToggle(overlay)
        setupVariantList(overlay)
        startObserving(overlay)
    }

    fun hide() {
        scope?.cancel()
        scope = null
        adapter = null

        overlayView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlayView = null
        isCollapsed = false
    }

    // ── Drag ────────────────────────────────────────────────────────────────

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
                    true
                }
                else -> false
            }
        }
    }

    // ── Collapse / Expand ───────────────────────────────────────────────────

    private fun setupCollapseToggle(overlay: OverlayPanelView) {
        overlay.collapseBtn.setOnClickListener {
            isCollapsed = !isCollapsed
            overlay.body.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            overlay.collapseBtn.rotation = if (isCollapsed) 180f else 0f
        }
    }

    // ── Manifest summary toggle ─────────────────────────────────────────────

    private fun setupManifestToggle(overlay: OverlayPanelView) {
        overlay.manifestToggle.setOnClickListener {
            val showing = overlay.manifestScroll.isVisible
            overlay.manifestScroll.visibility = if (showing) View.GONE else View.VISIBLE
            overlay.manifestToggle.text = if (showing) "Show Parsed Summary \u25b8" else "Hide Parsed Summary \u25be"
        }
    }

    // ── Variant list ────────────────────────────────────────────────────────

    private fun setupVariantList(overlay: OverlayPanelView) {
        adapter = VariantListAdapter()
        overlay.variantList.layoutManager = LinearLayoutManager(overlay.context)
        overlay.variantList.adapter = adapter
    }

    // ── Observation ─────────────────────────────────────────────────────────

    private fun startObserving(overlay: OverlayPanelView) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        scope?.launch {
            sessionStore.manifestInfo.collect { info ->
                if (info != null) {
                    adapter?.submitList(info.variants)
                    overlay.manifestText.text = buildManifestSummary(info)
                }
            }
        }

        scope?.launch {
            sessionStore.activeTrack.collect { track ->
                overlay.activeTrackView.text = formatActiveTrack(track)
                adapter?.activeTrack = track
            }
        }
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private fun formatActiveTrack(track: ActiveTrackInfo?): String {
        if (track == null) return "Loading\u2026"

        val resolution = if (track.width > 0 && track.height > 0) {
            "${track.width}\u00d7${track.height}"
        } else {
            "Audio only"
        }
        val bitrate = when {
            track.bitrate >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mbps", track.bitrate / 1_000_000.0)
            track.bitrate >= 1_000 -> String.format(Locale.getDefault(), "%d kbps", track.bitrate / 1_000)
            track.bitrate > 0 -> "${track.bitrate} bps"
            else -> "? bps"
        }
        return "$resolution  \u00b7  $bitrate"
    }

    private fun buildManifestSummary(info: HlsManifestInfo): String {
        val sb = StringBuilder()
        sb.appendLine("# HLS Multivariant Playlist")
        sb.appendLine("Variants: ${info.variants.size}")
        sb.appendLine()

        info.variants.forEachIndexed { i, v ->
            sb.append("#${i + 1}  ")
            if (v.width > 0 && v.height > 0) sb.append("${v.width}\u00d7${v.height}  ")
            if (v.bitrate > 0) {
                val mbps = v.bitrate / 1_000_000.0
                sb.append(String.format(Locale.getDefault(), "%.1f Mbps  ", mbps))
            }
            if (v.frameRate > 0) sb.append("${v.frameRate} fps  ")
            if (!v.codecs.isNullOrBlank()) sb.append("codecs=${v.codecs}")
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }
}

package com.streamprobe.sdk.internal.overlay

import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.ErrorDetail
import com.streamprobe.sdk.model.PlaybackErrorEvent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ErrorTimelineAdapterTest {

    private lateinit var adapter: ErrorTimelineAdapter

    @Before
    fun setUp() {
        adapter = ErrorTimelineAdapter()
    }

    private fun makeErrors(count: Int, baseTime: Long = 1000L): List<PlaybackErrorEvent> =
        (0 until count).map { i ->
            PlaybackErrorEvent(
                timestampMs = baseTime + i * 1000L,
                category = ErrorCategory.LOAD_ERROR,
                message = "Error $i",
            )
        }

    private fun makeDropFrameEvent(
        timestampMs: Long,
        frames: Int = 5,
        elapsedMs: Long = 100L,
    ) = PlaybackErrorEvent(
        timestampMs = timestampMs,
        category = ErrorCategory.DROPPED_FRAMES,
        message = "$frames frames in ${elapsedMs}ms",
        categoryDetail = ErrorDetail.DroppedFrames(
            totalFrames = frames,
            burstCount = 1,
            lastUpdateMs = timestampMs,
        ),
    )

    @Test
    fun `tap row toggles expansion`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = RecyclerView(context).also { rv ->
            rv.adapter = adapter
        }

        adapter.submitList(makeErrors(3)) {
            // Create a ViewHolder and simulate toggle
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)

            // Initial state: no expansion
            val itemView = holder.itemView as ErrorTimelineItemView
            assertEquals(android.view.View.GONE, itemView.detailContainer.visibility)

            // Click to expand
            itemView.performClick()
            // Re-bind at position 0 after notifyItemChanged would have been called
            adapter.onBindViewHolder(holder, 0)
            assertEquals(android.view.View.VISIBLE, itemView.detailContainer.visibility)

            // Click again to collapse
            itemView.performClick()
            adapter.onBindViewHolder(holder, 0)
            assertEquals(android.view.View.GONE, itemView.detailContainer.visibility)
        }
    }

    @Test
    fun `tap different row collapses previous`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = RecyclerView(context).also { rv ->
            rv.adapter = adapter
        }

        adapter.submitList(makeErrors(3))

        val holderA = adapter.onCreateViewHolder(parent, 0)
        val holderB = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holderA, 0)
        adapter.onBindViewHolder(holderB, 1)

        val viewA = holderA.itemView as ErrorTimelineItemView
        val viewB = holderB.itemView as ErrorTimelineItemView

        // Expand A
        viewA.performClick()
        adapter.onBindViewHolder(holderA, 0)
        assertEquals(android.view.View.VISIBLE, viewA.detailContainer.visibility)

        // Tap B — A should collapse
        viewB.performClick()
        adapter.onBindViewHolder(holderA, 0)
        adapter.onBindViewHolder(holderB, 1)
        assertEquals(android.view.View.GONE, viewA.detailContainer.visibility)
        assertEquals(android.view.View.VISIBLE, viewB.detailContainer.visibility)
    }

    @Test
    fun `dropped-frames merge DiffUtil stability - change not remove plus insert`() {
        val t = 1000L
        val original = makeDropFrameEvent(t, frames = 5)
        val merged = original.copy(
            // timestampMs unchanged - same DiffUtil identity
            message = "8 frames dropped (2 bursts)",
            categoryDetail = ErrorDetail.DroppedFrames(
                totalFrames = 8,
                burstCount = 2,
                lastUpdateMs = t + 2000L,
            ),
        )

        // Nest submitList calls inside commit callbacks so each diff is fully
        // applied before the next one starts, avoiding async-diff race conditions.
        // Both items share the same timestampMs, so areItemsTheSame returns true
        // and areContentsTheSame returns false → DiffUtil issues a change, not
        // a remove+insert pair.
        adapter.submitList(listOf(original)) {
            adapter.submitList(listOf(merged)) {
                assertEquals(1, adapter.itemCount)
                assertEquals("8 frames dropped (2 bursts)", adapter.currentList[0].message)
            }
        }
    }
}

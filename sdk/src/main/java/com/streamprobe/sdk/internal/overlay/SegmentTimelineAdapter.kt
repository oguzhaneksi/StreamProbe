package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.SegmentMetric

/**
 * RecyclerView adapter for the segment timeline list inside the debug overlay.
 *
 * Each row is a [SegmentTimelineItemView] constructed programmatically — no XML inflation or
 * [com.streamprobe.sdk.R] references required.
 */
internal class SegmentTimelineAdapter :
    ListAdapter<SegmentMetric, SegmentTimelineAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(SegmentTimelineItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position, getItem(position))
    }

    class ViewHolder(private val view: SegmentTimelineItemView) : RecyclerView.ViewHolder(view) {
        fun bind(index: Int, metric: SegmentMetric) {
            view.bind(index, metric)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SegmentMetric>() {
            override fun areItemsTheSame(old: SegmentMetric, new: SegmentMetric) =
                old.requestTimestampMs == new.requestTimestampMs && old.uri == new.uri

            override fun areContentsTheSame(old: SegmentMetric, new: SegmentMetric) =
                old == new
        }
    }
}

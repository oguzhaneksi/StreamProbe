package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.DrmSessionEvent

/** Adapter for the DRM timeline tab. Follows the [SwitchTimelineAdapter] pattern. */
internal class DrmTimelineAdapter : ListAdapter<DrmSessionEvent, DrmTimelineAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(DrmTimelineItemView(parent.context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val baseTimestampMs = currentList.firstOrNull()?.timestampMs ?: 0L
        holder.bind(position, getItem(position), baseTimestampMs)
    }

    class ViewHolder(
        private val view: DrmTimelineItemView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(
            index: Int,
            event: DrmSessionEvent,
            baseTimestampMs: Long,
        ) {
            view.bind(index, event, baseTimestampMs)
        }
    }

    companion object {
        private val DIFF =
            object : DiffUtil.ItemCallback<DrmSessionEvent>() {
                override fun areItemsTheSame(
                    old: DrmSessionEvent,
                    new: DrmSessionEvent,
                ) = old.timestampMs == new.timestampMs && old::class == new::class

                override fun areContentsTheSame(
                    old: DrmSessionEvent,
                    new: DrmSessionEvent,
                ) = old == new
            }
    }
}

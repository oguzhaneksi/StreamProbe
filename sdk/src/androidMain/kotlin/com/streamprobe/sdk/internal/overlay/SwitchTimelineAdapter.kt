package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.TrackSwitchEvent

/**
 * Adapter for the Switches timeline tab.
 * Displays all [TrackSwitchEvent] subtypes (Video, Audio, Subtitle) in a unified list.
 */
internal class SwitchTimelineAdapter : ListAdapter<TrackSwitchEvent, SwitchTimelineAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(SwitchTimelineItemView(parent.context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val baseTimestampMs = currentList.firstOrNull()?.timestampMs ?: 0L
        holder.bind(position, getItem(position), baseTimestampMs)
    }

    class ViewHolder(
        private val view: SwitchTimelineItemView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(
            index: Int,
            event: TrackSwitchEvent,
            baseTimestampMs: Long,
        ) {
            view.bind(index, event, baseTimestampMs)
        }
    }

    companion object {
        private val DIFF =
            object : DiffUtil.ItemCallback<TrackSwitchEvent>() {
                override fun areItemsTheSame(
                    old: TrackSwitchEvent,
                    new: TrackSwitchEvent,
                ) = old.timestampMs == new.timestampMs && old::class == new::class

                override fun areContentsTheSame(
                    old: TrackSwitchEvent,
                    new: TrackSwitchEvent,
                ) = old == new
            }
    }
}

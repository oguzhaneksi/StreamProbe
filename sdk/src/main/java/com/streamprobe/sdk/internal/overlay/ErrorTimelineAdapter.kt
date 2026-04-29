package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.PlaybackErrorEvent

internal class ErrorTimelineAdapter :
    ListAdapter<PlaybackErrorEvent, ErrorTimelineAdapter.ViewHolder>(DIFF) {

    private var expandedPosition: Int = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ErrorTimelineItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val baseTimestampMs = currentList.firstOrNull()?.timestampMs ?: 0L
        holder.bind(
            index = position,
            event = getItem(position),
            baseTimestampMs = baseTimestampMs,
            expanded = position == expandedPosition,
            onToggle = { toggleExpansion(position) },
        )
    }

    private fun toggleExpansion(position: Int) {
        val previous = expandedPosition
        expandedPosition = if (previous == position) RecyclerView.NO_POSITION else position
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
        if (expandedPosition != RecyclerView.NO_POSITION) notifyItemChanged(expandedPosition)
    }

    class ViewHolder(private val view: ErrorTimelineItemView) : RecyclerView.ViewHolder(view) {
        fun bind(
            index: Int,
            event: PlaybackErrorEvent,
            baseTimestampMs: Long,
            expanded: Boolean,
            onToggle: () -> Unit,
        ) {
            view.bind(index, event, baseTimestampMs, expanded, onToggle)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaybackErrorEvent>() {
            override fun areItemsTheSame(old: PlaybackErrorEvent, new: PlaybackErrorEvent) =
                old.timestampMs == new.timestampMs

            override fun areContentsTheSame(old: PlaybackErrorEvent, new: PlaybackErrorEvent) =
                old == new
        }
    }
}

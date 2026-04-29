package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.PlaybackErrorEvent

internal class ErrorTimelineAdapter :
    ListAdapter<PlaybackErrorEvent, ErrorTimelineAdapter.ViewHolder>(DIFF) {

    private var expandedTimestampMs: Long? = null

    override fun onCurrentListChanged(
        previousList: List<PlaybackErrorEvent>,
        currentList: List<PlaybackErrorEvent>,
    ) {
        super.onCurrentListChanged(previousList, currentList)
        // If the expanded item was removed from the new list, clear the expansion.
        val ts = expandedTimestampMs ?: return
        if (currentList.none { it.timestampMs == ts }) {
            expandedTimestampMs = null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ErrorTimelineItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        val baseTimestampMs = currentList.firstOrNull()?.timestampMs ?: 0L
        holder.bind(
            index = position,
            event = event,
            baseTimestampMs = baseTimestampMs,
            expanded = event.timestampMs == expandedTimestampMs,
            onToggle = { toggleExpansion(event.timestampMs) },
        )
    }

    private fun toggleExpansion(timestampMs: Long) {
        val previousTs = expandedTimestampMs
        expandedTimestampMs = if (previousTs == timestampMs) null else timestampMs
        if (previousTs != null) {
            val prevPos = currentList.indexOfFirst { it.timestampMs == previousTs }
            if (prevPos >= 0) notifyItemChanged(prevPos)
        }
        if (expandedTimestampMs != null) {
            val newPos = currentList.indexOfFirst { it.timestampMs == expandedTimestampMs }
            if (newPos >= 0) notifyItemChanged(newPos)
        }
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

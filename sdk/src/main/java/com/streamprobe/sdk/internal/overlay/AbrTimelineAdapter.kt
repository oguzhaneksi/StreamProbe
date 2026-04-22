package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.AbrSwitchEvent

internal class AbrTimelineAdapter :
    ListAdapter<AbrSwitchEvent, AbrTimelineAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(AbrTimelineItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val baseTimestampMs = currentList.firstOrNull()?.timestampMs ?: 0L
        holder.bind(position, getItem(position), baseTimestampMs)
    }

    class ViewHolder(private val view: AbrTimelineItemView) : RecyclerView.ViewHolder(view) {
        fun bind(index: Int, event: AbrSwitchEvent, baseTimestampMs: Long) {
            view.bind(index, event, baseTimestampMs)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AbrSwitchEvent>() {
            override fun areItemsTheSame(old: AbrSwitchEvent, new: AbrSwitchEvent) =
                old.timestampMs == new.timestampMs

            override fun areContentsTheSame(old: AbrSwitchEvent, new: AbrSwitchEvent) =
                old == new
        }
    }
}

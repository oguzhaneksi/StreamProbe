package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.internal.presenter.OverlayRow
import com.streamprobe.sdk.model.isSameRenditionAs

/**
 * Adapter for the Tracks tab in the debug overlay.
 * Shows all video, audio and subtitle renditions; [isSelected] on each item's info
 * model is set by the player via [Tracks.Group.isTrackSelected], so no secondary
 * comparison is needed here. Rows are assembled in the common `OverlayPresenter`
 * and delivered as [OverlayRow]s.
 */
internal class RenditionListAdapter : ListAdapter<OverlayRow, RecyclerView.ViewHolder>(DIFF) {
    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is OverlayRow.SectionHeader -> VIEW_TYPE_HEADER
            is OverlayRow.Video -> VIEW_TYPE_VIDEO
            is OverlayRow.Audio -> VIEW_TYPE_AUDIO
            is OverlayRow.Subtitle -> VIEW_TYPE_SUBTITLE
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_HEADER -> RenditionSectionHeaderViewHolder(RenditionSectionHeaderView(parent.context))
            else -> RenditionItemViewHolder(RenditionItemView(parent.context))
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = getItem(position)) {
            is OverlayRow.SectionHeader ->
                (holder as RenditionSectionHeaderViewHolder).bind(item.title)
            is OverlayRow.Video ->
                (holder as RenditionItemViewHolder).view.bind(item)
            is OverlayRow.Audio ->
                (holder as RenditionItemViewHolder).view.bind(item)
            is OverlayRow.Subtitle ->
                (holder as RenditionItemViewHolder).view.bind(item)
        }
    }

    private class RenditionItemViewHolder(
        val view: RenditionItemView,
    ) : RecyclerView.ViewHolder(view)

    private class RenditionSectionHeaderViewHolder(
        val view: RenditionSectionHeaderView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(title: String) {
            view.bind(title)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_AUDIO = 2
        private const val VIEW_TYPE_SUBTITLE = 3

        private val DIFF =
            object : DiffUtil.ItemCallback<OverlayRow>() {
                override fun areItemsTheSame(
                    old: OverlayRow,
                    new: OverlayRow,
                ): Boolean =
                    when (old) {
                        is OverlayRow.SectionHeader if new is OverlayRow.SectionHeader ->
                            old.title == new.title

                        is OverlayRow.Video if new is OverlayRow.Video -> {
                            val o = old.info
                            val n = new.info
                            if (o.id != null && n.id != null) {
                                o.id == n.id
                            } else {
                                o.width == n.width && o.height == n.height && o.bitrate == n.bitrate
                            }
                        }

                        is OverlayRow.Audio if new is OverlayRow.Audio ->
                            old.info.isSameRenditionAs(new.info)

                        is OverlayRow.Subtitle if new is OverlayRow.Subtitle ->
                            old.info.isSameRenditionAs(new.info)

                        else -> false
                    }

                override fun areContentsTheSame(
                    old: OverlayRow,
                    new: OverlayRow,
                ) = old == new
            }
    }
}

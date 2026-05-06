package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.VariantInfo
import com.streamprobe.sdk.model.isSameRenditionAs

/**
 * Sealed item type for the unified rendition list (video variants, audio tracks, subtitles).
 */
internal sealed interface RenditionListItem {
    data class SectionHeader(
        val title: String,
    ) : RenditionListItem

    data class Video(
        val info: VariantInfo,
    ) : RenditionListItem

    data class Audio(
        val info: AudioTrackInfo,
    ) : RenditionListItem

    data class Subtitle(
        val info: SubtitleTrackInfo,
    ) : RenditionListItem
}

/**
 * Adapter for the Tracks tab in the debug overlay.
 * Shows all video, audio and subtitle renditions; [isSelected] on each item's info
 * model is set by the player via [Tracks.Group.isTrackSelected], so no secondary
 * comparison is needed here.
 */
internal class RenditionListAdapter : ListAdapter<RenditionListItem, RecyclerView.ViewHolder>(DIFF) {
    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is RenditionListItem.SectionHeader -> VIEW_TYPE_HEADER
            is RenditionListItem.Video -> VIEW_TYPE_VIDEO
            is RenditionListItem.Audio -> VIEW_TYPE_AUDIO
            is RenditionListItem.Subtitle -> VIEW_TYPE_SUBTITLE
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
            is RenditionListItem.SectionHeader ->
                (holder as RenditionSectionHeaderViewHolder).bind(item.title)
            is RenditionListItem.Video ->
                (holder as RenditionItemViewHolder).view.bind(item)
            is RenditionListItem.Audio ->
                (holder as RenditionItemViewHolder).view.bind(item)
            is RenditionListItem.Subtitle ->
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
            object : DiffUtil.ItemCallback<RenditionListItem>() {
                override fun areItemsTheSame(
                    old: RenditionListItem,
                    new: RenditionListItem,
                ): Boolean =
                    when (old) {
                        is RenditionListItem.SectionHeader if new is RenditionListItem.SectionHeader ->
                            old.title == new.title

                        is RenditionListItem.Video if new is RenditionListItem.Video -> {
                            val o = old.info
                            val n = new.info
                            if (o.id != null && n.id != null) {
                                o.id == n.id
                            } else {
                                o.width == n.width && o.height == n.height && o.bitrate == n.bitrate
                            }
                        }

                        is RenditionListItem.Audio if new is RenditionListItem.Audio ->
                            old.info.isSameRenditionAs(new.info)

                        is RenditionListItem.Subtitle if new is RenditionListItem.Subtitle ->
                            old.info.isSameRenditionAs(new.info)

                        else -> false
                    }

                override fun areContentsTheSame(
                    old: RenditionListItem,
                    new: RenditionListItem,
                ) = old == new
            }
    }
}

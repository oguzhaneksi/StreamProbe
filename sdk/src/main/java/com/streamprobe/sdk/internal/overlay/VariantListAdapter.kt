package com.streamprobe.sdk.internal.overlay

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.VariantInfo

/**
 * Renders the variant list inside the debug overlay.
 * The currently active variant is highlighted with a green dot.
 *
 * Each row is a [VariantItemView] constructed programmatically — no XML inflation or
 * [com.streamprobe.sdk.R] references required.
 */
internal class VariantListAdapter :
    ListAdapter<VariantInfo, VariantListAdapter.ViewHolder>(DIFF) {

    var activeTrack: ActiveTrackInfo? = null
        set(value) {
            val previousValue = field
            if (previousValue == value) {
                return
            }

            field = value

            val previousPosition = findPositionForTrack(previousValue)
            val newPosition = findPositionForTrack(value)

            if (previousPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(previousPosition)
            }
            if (newPosition != RecyclerView.NO_POSITION && newPosition != previousPosition) {
                notifyItemChanged(newPosition)
            }
        }

    private fun findPositionForTrack(track: ActiveTrackInfo?): Int {
        if (track == null) {
            return RecyclerView.NO_POSITION
        }

        return currentList.indexOfFirst { variant ->
            variant.bitrate == track.bitrate &&
                variant.width == track.width &&
                variant.height == track.height
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(VariantItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), activeTrack)
    }

    class ViewHolder(private val variantView: VariantItemView) : RecyclerView.ViewHolder(variantView) {
        fun bind(variant: VariantInfo, active: ActiveTrackInfo?) {
            variantView.bind(variant, active)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VariantInfo>() {
            override fun areItemsTheSame(old: VariantInfo, new: VariantInfo) =
                old.bitrate == new.bitrate && old.width == new.width && old.height == new.height

            override fun areContentsTheSame(old: VariantInfo, new: VariantInfo) =
                old == new
        }
    }
}

package com.streamprobe.sdk.internal

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
import com.streamprobe.sdk.model.VariantInfo

/**
 * Listens to a [Player] for manifest availability and track selection changes,
 * mapping Media3 types into SDK-owned models and pushing them into [SessionStore].
 */
@UnstableApi
internal class PlayerInterceptor(
    private val sessionStore: SessionStore,
) : Player.Listener {

    private var player: Player? = null

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)

        // Probe immediately — manifest may already be available if the player is prepared.
        probeManifest(player)
        probeTracks(player)
    }

    fun detach() {
        player?.removeListener(this)
        player = null
    }

    // ── Player.Listener callbacks ───────────────────────────────────────────

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        player?.let { probeManifest(it) }
    }

    override fun onTracksChanged(tracks: Tracks) {
        probeTracks(player ?: return)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun probeManifest(player: Player) {
        val manifest = player.currentManifest
        if (manifest !is HlsManifest) return

        val variants = manifest.multivariantPlaylist.variants.map { variant ->
            val fmt = variant.format
            VariantInfo(
                bitrate = fmt.bitrate,
                width = fmt.width,
                height = fmt.height,
                codecs = fmt.codecs,
                frameRate = fmt.frameRate,
            )
        }

        sessionStore.updateManifest(HlsManifestInfo(variants))
        Log.d(TAG, "Manifest captured: ${variants.size} variants")
    }

    private fun probeTracks(player: Player) {
        val tracks = player.currentTracks

        for (group in tracks.groups) {
            if (!group.isSelected) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val fmt = group.getTrackFormat(i)
                // Only capture video tracks for the active-rendition indicator.
                if (fmt.width > 0 || fmt.height > 0) {
                    sessionStore.updateActiveTrack(
                        ActiveTrackInfo(
                            bitrate = fmt.bitrate,
                            width = fmt.width,
                            height = fmt.height,
                            codecs = fmt.codecs,
                        )
                    )
                    Log.d(TAG, "Active track: ${fmt.width}x${fmt.height} @ ${fmt.bitrate} bps")
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "StreamProbe"
    }
}

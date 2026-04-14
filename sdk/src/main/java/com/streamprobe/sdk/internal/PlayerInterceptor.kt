package com.streamprobe.sdk.internal

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.HlsManifestInfo
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.VariantInfo

/**
 * Listens to a [Player] for manifest availability and track selection changes,
 * mapping Media3 types into SDK-owned models and pushing them into [SessionStore].
 */
@UnstableApi
internal class PlayerInterceptor(
    private val sessionStore: SessionStore,
) : Player.Listener, AnalyticsListener {

    private var player: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(this)
        player.addAnalyticsListener(this)

        // Probe immediately — manifest may already be available if the player is prepared.
        probeManifest(player)
        probeTracks(player)
    }

    fun detach() {
        player?.removeAnalyticsListener(this)
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

    // AnalyticsListener — fires on every input-format change, including bitrate-only ABR switches.
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        updateActiveTrack(format)
    }

    // AnalyticsListener — fires when a load completes; used for segment metrics + CDN headers.
    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return

        val cdnInfo = CdnHeaderParser.parse(headers = loadEventInfo.responseHeaders)
        val metric = SegmentMetric(
            requestTimestampMs = System.currentTimeMillis() - loadEventInfo.loadDurationMs,
            totalDurationMs = loadEventInfo.loadDurationMs,
            sizeBytes = loadEventInfo.bytesLoaded,
            throughputBytesPerSec = if (loadEventInfo.loadDurationMs > 0)
                loadEventInfo.bytesLoaded * 1000 / loadEventInfo.loadDurationMs else 0,
            uri = loadEventInfo.uri.toString(),
            cdnInfo = cdnInfo,
        )
        sessionStore.addSegmentMetric(metric)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun probeManifest(player: ExoPlayer) {
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
                    updateActiveTrack(fmt)
                    return
                }
            }
        }
    }

    private fun updateActiveTrack(format: Format) {
        sessionStore.updateActiveTrack(
            ActiveTrackInfo(
                bitrate = format.bitrate,
                width = format.width,
                height = format.height,
                codecs = format.codecs,
            )
        )
        Log.d(TAG, "Active track: ${format.width}x${format.height} @ ${format.bitrate} bps")
    }

    companion object {
        private const val TAG = "StreamProbe"
    }
}

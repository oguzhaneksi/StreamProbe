package com.streamprobe.android.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.streamprobe.android.DebugDataSourceFactory
import com.streamprobe.android.PlayerUiState
import com.streamprobe.android.Stream
import com.streamprobe.android.data.DebugSettingsRepository
import com.streamprobe.sdk.StreamProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class PlayerManager(
    private val appContext: Context,
    private val repo: DebugSettingsRepository,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    private val streamProbe = StreamProbe()

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow

    private var initJob: Job? = null
    private var updateJob: Job? = null

    @OptIn(UnstableApi::class)
    fun initialize(
        activity: ComponentActivity,
        stream: Stream,
        faultMode: FaultMode = FaultMode.NORMAL,
        showOverlay: Boolean = true,
    ) {
        // Control arm of the fault-deck benchmark hides the overlay. The player
        // is still attached (passive listeners), so playback is identical; only
        // the overlay UI differs between arms.
        if (showOverlay) streamProbe.show(activity)
        if (_playerFlow.value != null) return
        initJob =
            scope.launch {
                val injectErrors = repo.injectErrorsFlow.first()
                buildPlayer(stream, injectErrors, faultMode)
            }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(
        stream: Stream,
        injectErrors: Boolean,
        faultMode: FaultMode,
    ) {
        val baseFactory: DataSource.Factory =
            if (injectErrors) {
                DebugDataSourceFactory(DefaultHttpDataSource.Factory(), errorRate = 0.2f)
            } else {
                DefaultHttpDataSource.Factory()
            }
        val mediaSourceFactory =
            DefaultMediaSourceFactory(appContext).setDataSourceFactory(streamProbe.wrapDataSourceFactory(baseFactory))

        val mediaItem = buildMediaItem(stream)

        val renderersFactory =
            DefaultRenderersFactory(appContext).setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                MediaCodecUtil
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .filterNot { it.name.contains("goldfish", ignoreCase = true) }
            }

        val exoPlayer =
            ExoPlayer
                .Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(buildTrackSelector(faultMode))
                .build()
                .apply {
                    streamProbe.attach(this)
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }

        _playerFlow.value = exoPlayer

        updateJob =
            scope.launch {
                while (isActive) {
                    _playerFlow.value?.let { p ->
                        uiState.update { current ->
                            current.copy(
                                positionMs = if (current.isScrubbing) current.positionMs else p.currentPosition,
                                durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
                                bufferedPositionMs = p.bufferedPosition,
                                isPlaying = p.isPlaying,
                                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                            )
                        }
                    }
                    delay(500.milliseconds)
                }
            }
    }

    private fun buildMediaItem(stream: Stream): MediaItem =
        MediaItem
            .Builder()
            .setUri(stream.url)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .apply {
                stream.drmConfig?.let { drm ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration
                            .Builder(drm.schemeUuid)
                            .setLicenseUri(drm.licenseUrl)
                            .build(),
                    )
                }
            }.build()

    /**
     * Builds a [DefaultTrackSelector] tuned for the given [FaultMode]. NORMAL is a
     * plain default selector; the other modes deliberately mis-tune ABR so the
     * fault-deck harness can reproduce "stuck at low quality" scenarios.
     */
    @OptIn(UnstableApi::class)
    private fun buildTrackSelector(faultMode: FaultMode): DefaultTrackSelector =
        when (faultMode) {
            FaultMode.NORMAL -> DefaultTrackSelector(appContext)

            // Hard cap to 480p regardless of available bandwidth.
            FaultMode.CONSTRAINED ->
                DefaultTrackSelector(appContext).apply {
                    setParameters(buildUponParameters().setMaxVideoSize(MAX_CONSTRAINED_WIDTH, MAX_CONSTRAINED_HEIGHT))
                }

            // ABR only trusts 10% of measured bandwidth, so it never climbs even on a fast link.
            FaultMode.BW_MISCONFIG -> {
                val cautiousAdaptive =
                    AdaptiveTrackSelection.Factory(
                        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                        MISCONFIGURED_BANDWIDTH_FRACTION,
                    )
                DefaultTrackSelector(appContext, cautiousAdaptive)
            }
        }

    fun release() {
        initJob?.cancel()
        initJob = null
        updateJob?.cancel()
        updateJob = null
        streamProbe.detach()
        val p = _playerFlow.value
        _playerFlow.value = null
        p?.release()
    }

    private companion object {
        // CONSTRAINED mode hard cap: 854x480 (480p).
        const val MAX_CONSTRAINED_WIDTH = 854
        const val MAX_CONSTRAINED_HEIGHT = 480

        // BW_MISCONFIG mode: trust only 10% of measured bandwidth (default is 0.7).
        const val MISCONFIGURED_BANDWIDTH_FRACTION = 0.1f
    }
}

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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
    ) {
        streamProbe.show(activity)
        if (_playerFlow.value != null) return
        initJob =
            scope.launch {
                val injectErrors = repo.injectErrorsFlow.first()
                buildPlayer(stream, injectErrors)
            }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(
        stream: Stream,
        injectErrors: Boolean,
    ) {
        val baseFactory: DataSource.Factory =
            if (injectErrors) {
                DebugDataSourceFactory(DefaultHttpDataSource.Factory(), errorRate = 0.2f)
            } else {
                DefaultHttpDataSource.Factory()
            }
        val mediaSourceFactory =
            DefaultMediaSourceFactory(appContext).setDataSourceFactory(streamProbe.wrapDataSourceFactory(baseFactory))

        val mediaItem =
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

        val exoPlayer =
            ExoPlayer
                .Builder(appContext)
                .setMediaSourceFactory(mediaSourceFactory)
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
}

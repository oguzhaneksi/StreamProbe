package com.streamprobe.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamprobe.android.data.DebugSettingsRepository
import com.streamprobe.sdk.StreamProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val appContext: Context,
    private val repo: DebugSettingsRepository,
) : ViewModel() {

    private val streamProbe = StreamProbe()

    var player: ExoPlayer? by mutableStateOf(null)
        private set

    private val _selectedStream = MutableStateFlow<Stream?>(null)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var updateJob: Job? = null

    fun selectStream(stream: Stream) {
        _selectedStream.value = stream
    }

    fun clearStream() {
        _selectedStream.value = null
    }

    @OptIn(UnstableApi::class)
    fun initializePlayer(activity: ComponentActivity) {
        streamProbe.show(activity)
        val stream = _selectedStream.value ?: return
        if (player != null) return
        viewModelScope.launch {
            val injectErrors = repo.injectErrorsFlow.first()
            buildPlayer(stream, injectErrors)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(stream: Stream, injectErrors: Boolean) {
        val dataSourceFactory: DataSource.Factory = if (injectErrors) {
            DebugDataSourceFactory(DefaultHttpDataSource.Factory(), errorRate = 0.2f)
        } else {
            DefaultHttpDataSource.Factory()
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                streamProbe.attach(this)
                val mediaItem = if (stream.mimeType != null) {
                    MediaItem.Builder()
                        .setUri(stream.url)
                        .setMimeType(stream.mimeType)
                        .build()
                } else {
                    MediaItem.fromUri(stream.url)
                }
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        updateJob = viewModelScope.launch {
            while (isActive) {
                player?.let { p ->
                    _uiState.update { current ->
                        current.copy(
                            positionMs = if (current.isScrubbing) current.positionMs else p.currentPosition,
                            durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
                            bufferedPositionMs = p.bufferedPosition,
                            isPlaying = p.isPlaying,
                            isBuffering = p.playbackState == Player.STATE_BUFFERING,
                        )
                    }
                }
                delay(500)
            }
        }
    }

    fun releasePlayer() {
        updateJob?.cancel()
        updateJob = null
        streamProbe.detach()
        player?.release()
        player = null
    }

    fun seekBack10s() {
        player?.let {
            val target = (it.currentPosition - 10_000L).coerceAtLeast(0L)
            it.seekTo(target)
        }
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekForward10s() {
        player?.let {
            val durationMs = _uiState.value.durationMs
            val max = if (durationMs > 0L) durationMs else Long.MAX_VALUE
            val target = (it.currentPosition + 10_000L).coerceAtMost(max)
            it.seekTo(target)
        }
    }

    fun onScrubPositionChanged(newPositionMs: Long) {
        _uiState.update {
            it.copy(
                isScrubbing = true,
                scrubPositionMs = newPositionMs,
            )
        }
    }

    fun onScrubFinished() {
        val state = _uiState.value
        if (state.durationMs > 0L) {
            player?.seekTo(state.scrubPositionMs)
        }
        _uiState.update {
            it.copy(
                isScrubbing = false,
                positionMs = state.scrubPositionMs
            )
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    companion object {
        fun factory(app: StreamProbeApplication) = viewModelFactory {
            initializer { PlayerViewModel(app.applicationContext, app.debugSettings) }
        }
    }
}

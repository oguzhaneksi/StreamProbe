package com.streamprobe.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.exoplayer.ExoPlayer
import com.streamprobe.android.data.DebugSettingsRepository
import com.streamprobe.android.player.AudioTrackOption
import com.streamprobe.android.player.FaultMode
import com.streamprobe.android.player.PlaybackController
import com.streamprobe.android.player.PlayerManager
import com.streamprobe.android.player.SubtitleTrackOption
import com.streamprobe.android.player.TrackManager
import com.streamprobe.android.player.VideoTrackOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    appContext: Context,
    repo: DebugSettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val playerManager = PlayerManager(appContext, repo, viewModelScope, _uiState)
    private val trackManager = TrackManager(playerManager.playerFlow, viewModelScope, _uiState)
    private val playbackController = PlaybackController(playerManager.playerFlow, _uiState)

    private val selectedStream = MutableStateFlow<Stream?>(null)
    private var faultMode: FaultMode = FaultMode.NORMAL
    private var showOverlay: Boolean = true
    private var enableEventLogger: Boolean = false

    var player: ExoPlayer? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            playerManager.playerFlow.collect { player = it }
        }
    }

    fun selectStream(
        stream: Stream,
        faultMode: FaultMode = FaultMode.NORMAL,
        showOverlay: Boolean = true,
        enableEventLogger: Boolean = false,
    ) {
        selectedStream.value = stream
        this.faultMode = faultMode
        this.showOverlay = showOverlay
        this.enableEventLogger = enableEventLogger
    }

    fun clearStream() {
        selectedStream.value = null
        faultMode = FaultMode.NORMAL
        showOverlay = true
        enableEventLogger = false
    }

    fun initializePlayer(activity: ComponentActivity) {
        val stream = selectedStream.value ?: return
        playerManager.initialize(activity, stream, faultMode, showOverlay, enableEventLogger)
    }

    fun releasePlayer() = playerManager.release()

    fun selectVideoTrack(option: VideoTrackOption) = trackManager.selectVideoTrack(option)

    fun selectAudioTrack(option: AudioTrackOption) = trackManager.selectAudioTrack(option)

    fun selectSubtitleTrack(option: SubtitleTrackOption) = trackManager.selectSubtitleTrack(option)

    fun seekBack10s() = playbackController.seekBack10s()

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun seekForward10s() = playbackController.seekForward10s()

    fun onScrubPositionChanged(newPositionMs: Long) = playbackController.onScrubPositionChanged(newPositionMs)

    fun onScrubFinished() = playbackController.onScrubFinished()

    override fun onCleared() {
        playerManager.release()
        super.onCleared()
    }

    companion object {
        fun factory(app: StreamProbeApplication) =
            viewModelFactory {
                initializer { PlayerViewModel(app.applicationContext, app.debugSettings) }
            }
    }
}

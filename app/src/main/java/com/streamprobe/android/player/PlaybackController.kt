package com.streamprobe.android.player

import androidx.media3.exoplayer.ExoPlayer
import com.streamprobe.android.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal class PlaybackController(
    private val playerFlow: StateFlow<ExoPlayer?>,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    private val player get() = playerFlow.value

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
            val durationMs = uiState.value.durationMs
            val max = if (durationMs > 0L) durationMs else Long.MAX_VALUE
            val target = (it.currentPosition + 10_000L).coerceAtMost(max)
            it.seekTo(target)
        }
    }

    fun onScrubPositionChanged(newPositionMs: Long) {
        uiState.update {
            it.copy(
                isScrubbing = true,
                scrubPositionMs = newPositionMs,
            )
        }
    }

    fun onScrubFinished() {
        val state = uiState.value
        if (state.durationMs > 0L) {
            player?.seekTo(state.scrubPositionMs)
        }
        uiState.update {
            it.copy(
                isScrubbing = false,
                positionMs = state.scrubPositionMs,
            )
        }
    }
}

package com.streamprobe.android.ui

data class PlayerControllerCallbacks(
    val onSeekBack: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onSeekForward: () -> Unit,
    val onScrubPositionChanged: (Long) -> Unit,
    val onScrubFinished: () -> Unit,
    val onUserInteraction: () -> Unit,
    val onTrackSelectionClick: () -> Unit,
)

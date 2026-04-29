package com.streamprobe.sdk.model

enum class ErrorCategory {
    /** Segment / manifest load failure (onLoadError). DRM is handled in M8. */
    LOAD_ERROR,
    /** Hardware/software video codec error (onVideoCodecError). */
    VIDEO_CODEC_ERROR,
    /** Dropped video frames exceeding a threshold (onDroppedVideoFrames). */
    DROPPED_FRAMES,
    /** Audio sink error (onAudioSinkError). */
    AUDIO_SINK_ERROR,
}

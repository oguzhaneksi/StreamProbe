package com.streamprobe.sdk.model

enum class ErrorCategory {
    /** Segment / manifest load failure (onLoadError). DRM errors are captured via
     onDrmSessionManagerError, not here. */
    LOAD_ERROR,

    /** Hardware/software video codec error (onVideoCodecError). */
    VIDEO_CODEC_ERROR,

    /** Dropped video frames exceeding a threshold (onDroppedVideoFrames). */
    DROPPED_FRAMES,

    /** Audio sink error (onAudioSinkError). */
    AUDIO_SINK_ERROR,

    /** Hardware/software audio codec error (onAudioCodecError). */
    AUDIO_CODEC_ERROR,

    /** DRM session manager error (onDrmSessionManagerError). */
    DRM_ERROR,
}

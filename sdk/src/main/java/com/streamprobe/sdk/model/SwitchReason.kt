package com.streamprobe.sdk.model

/**
 * Reason for an ABR track switch, as reported by Media3.
 */
enum class SwitchReason {
    /** Initial track selection at playback start. */
    INITIAL,
    /** Automatic adaptive selection (bandwidth/buffer driven). */
    ADAPTIVE,
    /** Manual selection by user or application code. */
    MANUAL,
    /** Trick-play mode (fast-forward / rewind). */
    TRICKPLAY,
    /** Unknown or unmapped reason. */
    UNKNOWN,
}

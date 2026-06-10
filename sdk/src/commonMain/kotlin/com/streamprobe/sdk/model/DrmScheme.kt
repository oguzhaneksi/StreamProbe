package com.streamprobe.sdk.model

enum class DrmScheme {
    WIDEVINE,
    PLAYREADY,
    CLEARKEY,

    /** Apple FairPlay Streaming (iOS / AVContentKeySession). */
    FAIRPLAY,
    UNKNOWN,
}

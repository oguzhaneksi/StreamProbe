package com.streamprobe.android

import androidx.media3.common.MimeTypes

enum class StreamType {
    MP4,
    HLS,
    DASH,
}

data class Stream(
    val title: String,
    val url: String,
    val type: StreamType,
    val mimeType: String? = null,
)

val defaultStreams = listOf(
    Stream(
        title = "Apple Bipbop (VOD)",
        url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
        type = StreamType.HLS,
        mimeType = MimeTypes.APPLICATION_M3U8,
    ),
    Stream(
        title = "CBS News (Live)",
        url = "https://news20e7hhcb.airspace-cdn.cbsivideo.com/index.m3u8",
        type = StreamType.HLS,
        mimeType = MimeTypes.APPLICATION_M3U8,
    ),
)

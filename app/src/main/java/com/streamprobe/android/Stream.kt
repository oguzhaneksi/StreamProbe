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
    Stream(
        title = "Envivio DASH (VOD)",
        url = "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd",
        type = StreamType.DASH,
        mimeType = MimeTypes.APPLICATION_MPD,
    ),
    Stream(
        title = "DASHIF Live — Basic",
        url = "https://livesim.dashif.org/livesim/testpic_2s/Manifest.mpd",
        type = StreamType.DASH,
        mimeType = MimeTypes.APPLICATION_MPD,
    ),
    Stream(
        title = "DASHIF Live — Availability Time Offset (ATO=10s)",
        url = "https://livesim.dashif.org/livesim/ato_10/testpic_2s/Manifest.mpd",
        type = StreamType.DASH,
        mimeType = MimeTypes.APPLICATION_MPD,
    ),
    Stream(
        title = "Unified Streaming — Live + SCTE-35 + ABR",
        url = "https://demo.unified-streaming.com/k8s/live/stable/scte35.isml/.mpd",
        type = StreamType.DASH,
        mimeType = MimeTypes.APPLICATION_MPD,
    ),
)

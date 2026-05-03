package com.streamprobe.sdk.internal

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo

@UnstableApi
internal fun Format.toActiveTrackInfo() =
    ActiveTrackInfo(
        bitrate = bitrate,
        width = width,
        height = height,
        codecs = codecs,
    )

@UnstableApi
internal fun Format.toAudioTrackInfo(isMuxed: Boolean) =
    AudioTrackInfo(
        language = language,
        label = labels.firstOrNull()?.value,
        codecs = codecs,
        bitrate = bitrate,
        channelCount = channelCount,
        sampleRate = sampleRate,
        isMuxed = isMuxed,
        id = id,
    )

@UnstableApi
internal fun Format.toSubtitleTrackInfo(kind: SubtitleKind) =
    SubtitleTrackInfo(
        language = language,
        label = labels.firstOrNull()?.value,
        mimeType = sampleMimeType,
        kind = kind,
        id = id,
    )

/** Infers [isMuxed] from the container MIME type. */
@UnstableApi
internal fun Format.toAudioTrackInfoDetecting() = toAudioTrackInfo(isMuxed = containerMimeType?.startsWith("video/") == true)

/** Infers [SubtitleKind] from the sample MIME type. */
@UnstableApi
internal fun Format.toSubtitleTrackInfoDetecting(): SubtitleTrackInfo {
    val kind =
        if (sampleMimeType == MimeTypes.APPLICATION_CEA608 ||
            sampleMimeType == MimeTypes.APPLICATION_CEA708
        ) {
            SubtitleKind.CC
        } else {
            SubtitleKind.SIDECAR
        }
    return toSubtitleTrackInfo(kind)
}

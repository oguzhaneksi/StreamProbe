package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.VariantInfo
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAssetVariant
import platform.AVFoundation.AVAssetVariantVideoAttributes
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.extendedLanguageTag
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.presentationSize
import platform.CoreGraphics.CGSize
import platform.Foundation.languageCode

/*
 * Maps AVFoundation value objects to StreamProbe's existing platform-neutral models.
 *
 * The iOS analogue of `FormatExtensions` on Android. It invents no new model and degrades
 * gracefully via nullable fields: AVFoundation surfaces far less per-variant detail than a
 * Media3 `Format`, so unavailable values map to the same "unknown" sentinels the Android side
 * already uses (`-1` dimensions/frame-rate, `null` ids/codecs, `0` channel/sample-rate).
 *
 * The numeric/string conversions are factored into pure top-level helpers so they unit-test
 * without a live `AVAsset`.
 */

/** Picks the most representative bitrate (peak preferred, then average); 0 when both unavailable. */
internal fun pickVariantBitrate(
    peakBitRate: Double,
    averageBitRate: Double,
): Int =
    when {
        peakBitRate > 0 -> peakBitRate.toInt()
        averageBitRate > 0 -> averageBitRate.toInt()
        else -> 0
    }

/** A CoreGraphics dimension as an Int, or -1 when not a positive value (matches Android's unknown sentinel). */
internal fun dimensionOrUnknown(value: Double): Int = if (value > 0) value.toInt() else -1

/** Nominal frame rate as a Float, or -1f when unavailable (matches Android's unknown sentinel). */
internal fun frameRateOrUnknown(nominalFrameRate: Double): Float = if (nominalFrameRate > 0) nominalFrameRate.toFloat() else -1f

/** Joins FourCC codec types into a single comma-separated string, or null when empty. */
internal fun joinCodecs(codecTypes: List<String>): String? = codecTypes.filter { it.isNotBlank() }.ifEmpty { null }?.joinToString(",")

/**
 * Resolves a BCP-47 language tag from a media-selection option, preferring the explicit
 * `extendedLanguageTag` over the locale's language code. AVFoundation's "und" (undetermined)
 * collapses to null so the UI shows nothing rather than a meaningless token.
 */
internal fun preferredLanguageTag(
    extendedLanguageTag: String?,
    localeLanguageCode: String?,
): String? {
    val tag = extendedLanguageTag?.takeIf { it.isNotBlank() } ?: localeLanguageCode?.takeIf { it.isNotBlank() }
    return tag?.takeUnless { it.equals("und", ignoreCase = true) }
}

/**
 * iOS exposes no reliable CEA-608/708-vs-WebVTT/TTML signal through `AVMediaSelectionOption`,
 * so legible renditions default to SIDECAR. (Known iOS gap vs Android — see roadmap risk #5.)
 */
internal fun defaultSubtitleKind(): SubtitleKind = SubtitleKind.SIDECAR

@OptIn(ExperimentalForeignApi::class)
private fun readPresentationSize(size: CValue<CGSize>): Pair<Int, Int> =
    size.useContents { dimensionOrUnknown(width) to dimensionOrUnknown(height) }

@OptIn(ExperimentalForeignApi::class)
private fun videoCodecs(attributes: AVAssetVariantVideoAttributes): String? =
    joinCodecs(attributes.codecTypes.mapNotNull { it?.toString() })

/** Maps an `AVAssetVariant` (iOS 15+) to a [VariantInfo]. `isSelected`/`id` are unknown up front. */
@OptIn(ExperimentalForeignApi::class)
internal fun mapVariant(variant: AVAssetVariant): VariantInfo {
    val video = variant.videoAttributes
    val (width, height) = video?.let { readPresentationSize(it.presentationSize) } ?: (-1 to -1)
    return VariantInfo(
        bitrate = pickVariantBitrate(variant.peakBitRate, variant.averageBitRate),
        width = width,
        height = height,
        codecs = video?.let { videoCodecs(it) },
        frameRate = video?.let { frameRateOrUnknown(it.nominalFrameRate) } ?: -1f,
        id = null,
        isSelected = false,
    )
}

/** Maps an audible `AVMediaSelectionOption` to an [AudioTrackInfo]. Channel/sample-rate are unavailable on iOS. */
internal fun mapAudioOption(option: AVMediaSelectionOption): AudioTrackInfo =
    AudioTrackInfo(
        language = preferredLanguageTag(option.extendedLanguageTag, option.locale?.languageCode),
        label = option.displayName.takeIf { it.isNotBlank() },
        codecs = null,
        bitrate = 0,
        channelCount = 0,
        sampleRate = 0,
        isMuxed = false,
        id = null,
        isSelected = false,
    )

/** Maps a legible `AVMediaSelectionOption` to a [SubtitleTrackInfo]. */
internal fun mapLegibleOption(option: AVMediaSelectionOption): SubtitleTrackInfo =
    SubtitleTrackInfo(
        language = preferredLanguageTag(option.extendedLanguageTag, option.locale?.languageCode),
        label = option.displayName.takeIf { it.isNotBlank() },
        mimeType = null,
        kind = defaultSubtitleKind(),
        id = null,
        isSelected = false,
    )

package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.SubtitleKind

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
 *
 * Gotcha: `variants` is an extension on `AVURLAsset`, not `AVAsset` — callers must cast
 * `item.asset as? AVURLAsset` before reading them.
 */

/** Picks the most representative bitrate (peak preferred, then average); 0 when both unavailable. */
public fun pickVariantBitrate(
    peakBitRate: Double,
    averageBitRate: Double,
): Int =
    when {
        peakBitRate > 0 -> peakBitRate.toInt()
        averageBitRate > 0 -> averageBitRate.toInt()
        else -> 0
    }

/** A CoreGraphics dimension as an Int, or -1 when not a positive value (matches Android's unknown sentinel). */
public fun dimensionOrUnknown(value: Double): Int = if (value > 0) value.toInt() else -1

/** Nominal frame rate as a Float, or -1f when unavailable (matches Android's unknown sentinel). */
public fun frameRateOrUnknown(nominalFrameRate: Double): Float = if (nominalFrameRate > 0) nominalFrameRate.toFloat() else -1f

/** Joins codec type strings into a single comma-separated string, or null when empty. */
public fun joinCodecs(codecTypes: List<String>): String? = codecTypes.filter { it.isNotBlank() }.ifEmpty { null }?.joinToString(",")

/**
 * Converts a [CMVideoCodecType] integer (a FourCharCode) to its 4-character ASCII name.
 *
 * AVFoundation's [AVAssetVariantVideoAttributes.codecTypes] returns NSArray<NSNumber> where each
 * value is a [CMVideoCodecType] constant — a FourCharCode (`UInt32`). The constants are defined
 * as 4-character literals in CoreMedia (e.g. `kCMVideoCodecType_H264 = 'avc1'`), so the integer
 * `1635148593` round-trips back to `"avc1"` via big-endian byte extraction.
 *
 * Note: iOS does not expose the codec profile/level, so the result is the bare FourCC tag
 * (e.g. `"avc1"`, `"hvc1"`, `"av01"`) rather than the full MIME codec string Android produces
 * from the HLS `CODECS` attribute (e.g. `"avc1.42e00a"`).
 */
public fun fourCCToString(value: Int): String =
    buildString(4) {
        for (shift in 24 downTo 0 step 8) {
            append(((value ushr shift) and 0xFF).toChar())
        }
    }

/**
 * Resolves a BCP-47 language tag from a media-selection option, preferring the explicit
 * `extendedLanguageTag` over the locale's language code. AVFoundation's "und" (undetermined)
 * collapses to null so the UI shows nothing rather than a meaningless token.
 */
public fun preferredLanguageTag(
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
public fun defaultSubtitleKind(): SubtitleKind = SubtitleKind.SIDECAR

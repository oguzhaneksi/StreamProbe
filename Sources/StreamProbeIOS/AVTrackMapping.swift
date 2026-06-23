import AVFoundation
import CoreMedia
import StreamProbeCore

/*
 * Swift equivalents of the Kotlin-internal, AVFoundation-typed variant/option mappers. They extract
 * AVFoundation fields and delegate the numeric/string logic to the public Core helpers
 * (`AVMetricMappersKt`), then build the platform-neutral SDK models. The iOS gaps the Kotlin mappers
 * documented hold here too: audio channel/sample-rate unavailable, codec = bare FourCC, legible = SIDECAR.
 */

/// Maps an `AVAssetVariant` (iOS 15+) to a `VariantInfo`. `id`/`isSelected` are unknown up front.
func mapVariant(_ variant: AVAssetVariant) -> VariantInfo {
    // The Swift AVFoundation overlay types these bit rates as optional `Double?` (nil = unavailable),
    // whereas the K/N binding the Kotlin probe used surfaces them as plain `Double`. Coalescing nil to
    // a non-positive sentinel reproduces the Kotlin behavior exactly: `pickVariantBitrate` treats any
    // value `<= 0` as "unavailable".
    let bitrate = AVMetricMappersKt.pickVariantBitrate(
        peakBitRate: variant.peakBitRate ?? -1,
        averageBitRate: variant.averageBitRate ?? -1
    )
    guard let video = variant.videoAttributes else {
        return VariantInfo(bitrate: bitrate, width: -1, height: -1, codecs: nil, frameRate: -1, id: nil, isSelected: false)
    }
    let size = video.presentationSize
    let codecs = AVMetricMappersKt.joinCodecs(
        codecTypes: video.codecTypes.map { AVMetricMappersKt.fourCCToString(value: Int32(bitPattern: $0)) }
    )
    return VariantInfo(
        bitrate: bitrate,
        width: AVMetricMappersKt.dimensionOrUnknown(value: Double(size.width)),
        height: AVMetricMappersKt.dimensionOrUnknown(value: Double(size.height)),
        codecs: codecs,
        frameRate: AVMetricMappersKt.frameRateOrUnknown(nominalFrameRate: Double(video.nominalFrameRate ?? -1)),
        id: nil,
        isSelected: false
    )
}

/// Maps an audible `AVMediaSelectionOption` to an `AudioTrackInfo`. Channel/sample-rate are unavailable on iOS.
func mapAudioOption(_ option: AVMediaSelectionOption, isSelected: Bool) -> AudioTrackInfo {
    AudioTrackInfo(
        language: AVMetricMappersKt.preferredLanguageTag(
            extendedLanguageTag: option.extendedLanguageTag,
            localeLanguageCode: option.locale?.languageCode
        ),
        label: option.displayName.isEmpty ? nil : option.displayName,
        codecs: nil,
        bitrate: 0,
        channelCount: 0,
        sampleRate: 0,
        isMuxed: false,
        id: nil,
        isSelected: isSelected
    )
}

/// Maps a legible `AVMediaSelectionOption` to a `SubtitleTrackInfo`.
func mapLegibleOption(_ option: AVMediaSelectionOption, isSelected: Bool) -> SubtitleTrackInfo {
    SubtitleTrackInfo(
        language: AVMetricMappersKt.preferredLanguageTag(
            extendedLanguageTag: option.extendedLanguageTag,
            localeLanguageCode: option.locale?.languageCode
        ),
        label: option.displayName.isEmpty ? nil : option.displayName,
        mimeType: nil,
        kind: AVMetricMappersKt.defaultSubtitleKind(),
        id: nil,
        isSelected: isSelected
    )
}

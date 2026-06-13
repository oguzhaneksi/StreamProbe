package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.SubtitleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure mapping helpers extracted from [mapVariant] / [mapAudioOption] /
 * [mapLegibleOption]. These exercise the numeric/string conversions without a live `AVAsset`;
 * the end-to-end AVFoundation chain is covered by `AVPlayerProbePocTest`.
 */
class AVMetricMappersTest {
    @Test
    fun pickVariantBitrate_prefersPeak() {
        assertEquals(5_000_000, pickVariantBitrate(peakBitRate = 5_000_000.0, averageBitRate = 3_000_000.0))
    }

    @Test
    fun pickVariantBitrate_fallsBackToAverageWhenPeakUnavailable() {
        assertEquals(3_000_000, pickVariantBitrate(peakBitRate = -1.0, averageBitRate = 3_000_000.0))
    }

    @Test
    fun pickVariantBitrate_zeroWhenBothUnavailable() {
        assertEquals(0, pickVariantBitrate(peakBitRate = 0.0, averageBitRate = -1.0))
    }

    @Test
    fun dimensionOrUnknown_mapsPositiveAndUnknown() {
        assertEquals(1920, dimensionOrUnknown(1920.0))
        assertEquals(-1, dimensionOrUnknown(0.0))
        assertEquals(-1, dimensionOrUnknown(-3.0))
    }

    @Test
    fun frameRateOrUnknown_mapsPositiveAndUnknown() {
        assertEquals(30f, frameRateOrUnknown(30.0))
        assertEquals(-1f, frameRateOrUnknown(0.0))
        assertEquals(-1f, frameRateOrUnknown(-1.0))
    }

    @Test
    fun joinCodecs_joinsAndDropsBlankEntries() {
        assertEquals("avc1.42e00a,mp4a.40.2", joinCodecs(listOf("avc1.42e00a", "mp4a.40.2")))
        assertEquals("avc1", joinCodecs(listOf("avc1", "", "  ")))
        assertNull(joinCodecs(emptyList()))
        assertNull(joinCodecs(listOf("", "  ")))
    }

    @Test
    fun preferredLanguageTag_prefersExtendedTag() {
        assertEquals("en-US", preferredLanguageTag(extendedLanguageTag = "en-US", localeLanguageCode = "en"))
    }

    @Test
    fun preferredLanguageTag_fallsBackToLocaleWhenTagBlankOrNull() {
        assertEquals("fr", preferredLanguageTag(extendedLanguageTag = null, localeLanguageCode = "fr"))
        assertEquals("fr", preferredLanguageTag(extendedLanguageTag = "  ", localeLanguageCode = "fr"))
    }

    @Test
    fun preferredLanguageTag_undeterminedOrEmptyBecomesNull() {
        assertNull(preferredLanguageTag(extendedLanguageTag = "und", localeLanguageCode = null))
        assertNull(preferredLanguageTag(extendedLanguageTag = "UND", localeLanguageCode = null))
        assertNull(preferredLanguageTag(extendedLanguageTag = null, localeLanguageCode = null))
    }

    @Test
    fun defaultSubtitleKind_isSidecar() {
        assertEquals(SubtitleKind.SIDECAR, defaultSubtitleKind())
    }

    @Test
    fun fourCCToString_convertsKnownVideoCodecTypes() {
        // Values are the CMVideoCodecType FourCharCode constants from CoreMedia/CMFormatDescription.h.
        assertEquals("avc1", fourCCToString(1635148593)) // kCMVideoCodecType_H264 = 'avc1'
        assertEquals("hvc1", fourCCToString(1752589105)) // kCMVideoCodecType_HEVC = 'hvc1'
        assertEquals("av01", fourCCToString(1635135537)) // kCMVideoCodecType_AV1  = 'av01'
        assertEquals("vp09", fourCCToString(1987063865)) // kCMVideoCodecType_VP9  = 'vp09'
        assertEquals("mp4v", fourCCToString(1836070006)) // kCMVideoCodecType_MPEG4Video = 'mp4v'
    }
}

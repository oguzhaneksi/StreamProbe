package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.AudioTrackInfo
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.CdnProvider
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.NetworkTiming
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.SegmentTrackType
import com.streamprobe.sdk.model.SubtitleKind
import com.streamprobe.sdk.model.SubtitleTrackInfo
import com.streamprobe.sdk.model.SwitchReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayFormattingTest {
    private fun makeCdnInfo(
        status: CacheStatus = CacheStatus.UNKNOWN,
        xCache: String? = null,
        cacheControl: String? = null,
        cdnSpecificHeaders: Map<String, String> = emptyMap(),
        cdnProvider: CdnProvider? = null,
    ) = CdnHeaderInfo(
        cacheControl = cacheControl,
        xCache = xCache,
        via = null,
        cdnSpecificHeaders = cdnSpecificHeaders,
        cacheStatus = status,
        cdnProvider = cdnProvider,
    )

    private fun makeMetric(
        totalDurationMs: Long = 200L,
        sizeBytes: Long = 500_000L,
        throughputBytesPerSec: Long = 2_500_000L,
        cdnInfo: CdnHeaderInfo = makeCdnInfo(),
        networkTiming: NetworkTiming? = null,
        uri: String = "https://example.com/seg.ts",
        trackType: SegmentTrackType = SegmentTrackType.UNKNOWN,
    ) = SegmentMetric(
        requestTimestampMs = 1_000L,
        totalDurationMs = totalDurationMs,
        sizeBytes = sizeBytes,
        throughputBytesPerSec = throughputBytesPerSec,
        uri = uri,
        cdnInfo = cdnInfo,
        networkTiming = networkTiming,
        trackType = trackType,
    )

    @Test
    fun `formatSegmentMetric with null returns placeholder`() {
        val result = OverlayFormatters.formatSegmentMetric(null)
        assertEquals("\u2014", result)
    }

    @Test
    fun `formatSegmentMetric returns two lines separated by newline`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric())
        assertTrue(result.contains("\n"), "Expected newline separator in: $result")
        assertEquals(2, result.lines().size)
    }

    @Test
    fun `formatSegmentMetric first line contains DL duration`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(totalDurationMs = 200L))
        val line1 = result.lines().first()
        assertTrue(line1.contains("DL: 200ms"), "Expected 'DL: 200ms' in line1: $line1")
    }

    @Test
    fun `formatSegmentDetails contains size throughput and optional ttfb`() {
        val timing = NetworkTiming(ttfbMs = 40L, transferDurationMs = 160L, isEstimated = true)
        val result =
            OverlayFormatters.formatSegmentDetails(
                makeMetric(sizeBytes = 1_200_000L, throughputBytesPerSec = 3_800_000L, networkTiming = timing),
            )
        assertTrue(result.contains("Size:"), "Expected 'Size:' in: $result")
        assertTrue(result.contains("1.2 MB"), "Expected '1.2 MB' in: $result")
        assertTrue(result.contains("TP:"), "Expected 'TP:' in: $result")
        assertTrue(result.contains("3.8 MB/s"), "Expected '3.8 MB/s' in: $result")
        assertTrue(result.contains("TTFB: ~40ms"), "Expected 'TTFB: ~40ms' in: $result")
    }

    @Test
    fun `formatSegmentDetails omits TTFB when networkTiming is null`() {
        val result = OverlayFormatters.formatSegmentDetails(makeMetric(networkTiming = null))
        assertTrue(!result.contains("TTFB"), "Expected no 'TTFB' in: $result")
    }

    @Test
    fun `formatSegmentMetric second line contains throughput`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(throughputBytesPerSec = 3_800_000L))
        val line2 = result.lines()[1]
        assertTrue(line2.contains("TP:"), "Expected 'TP:' in line2: $line2")
        assertTrue(line2.contains("3.8 MB/s"), "Expected '3.8 MB/s' in line2: $line2")
    }

    @Test
    fun `formatSegmentMetric includes TTFB on second line when networkTiming present`() {
        val timing = NetworkTiming(ttfbMs = 40L, transferDurationMs = 160L, isEstimated = true)
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(networkTiming = timing))
        val line2 = result.lines()[1]
        assertTrue(line2.contains("TTFB: ~40ms"), "Expected 'TTFB: ~40ms' in line2: $line2")
    }

    @Test
    fun `formatSegmentMetric omits TTFB when networkTiming is null`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(networkTiming = null))
        assertTrue(!result.contains("TTFB"), "Expected no 'TTFB' in: $result")
    }

    @Test
    fun `formatSegmentMetric formats large size in MB`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(sizeBytes = 1_200_000L))
        assertTrue(result.contains("1.2 MB"), "Expected '1.2 MB' in: $result")
    }

    @Test
    fun `formatSegmentMetric formats throughput in MB slash s`() {
        val result = OverlayFormatters.formatSegmentMetric(makeMetric(throughputBytesPerSec = 3_800_000L))
        assertTrue(result.contains("3.8 MB/s"), "Expected '3.8 MB/s' in: $result")
    }

    @Test
    fun `formatCdnStatus with HIT shows indicator`() {
        val result = CdnFormatters.formatCdnStatus(makeCdnInfo(status = CacheStatus.HIT))
        assertTrue(result.contains("HIT"), "Expected 'HIT' in: $result")
    }

    @Test
    fun `formatCdnStatus with MISS shows indicator`() {
        val result = CdnFormatters.formatCdnStatus(makeCdnInfo(status = CacheStatus.MISS))
        assertTrue(result.contains("MISS"), "Expected 'MISS' in: $result")
    }

    @Test
    fun `formatCdnStatus with null returns placeholder`() {
        val result = CdnFormatters.formatCdnStatus(null)
        assertEquals("\u2014", result)
    }

    @Test
    fun `formatCdnStatus with known provider prepends provider tag`() {
        val result =
            CdnFormatters.formatCdnStatus(
                makeCdnInfo(status = CacheStatus.HIT, cdnProvider = CdnProvider.CLOUDFLARE),
            )
        assertTrue(result.startsWith("[CLOUDFLARE]"), "Expected '[CLOUDFLARE]' prefix in: $result")
        assertTrue(result.contains("HIT"), "Expected 'HIT' in: $result")
    }

    @Test
    fun `formatCdnStatus with UNKNOWN provider omits provider tag`() {
        val result =
            CdnFormatters.formatCdnStatus(
                makeCdnInfo(status = CacheStatus.HIT, cdnProvider = CdnProvider.UNKNOWN),
            )
        assertTrue(!result.startsWith("["), "Expected no brackets in: $result")
    }

    @Test
    fun `formatCdnStatus with null provider omits provider tag`() {
        val result =
            CdnFormatters.formatCdnStatus(
                makeCdnInfo(status = CacheStatus.MISS, cdnProvider = null),
            )
        assertTrue(!result.startsWith("["), "Expected no brackets in: $result")
    }

    @Test
    fun `formatCdnStatus includes all provider labels correctly`() {
        mapOf(
            CdnProvider.CLOUDFLARE to "[CLOUDFLARE]",
            CdnProvider.CLOUDFRONT to "[CLOUDFRONT]",
            CdnProvider.FASTLY to "[FASTLY]",
            CdnProvider.AKAMAI to "[AKAMAI]",
        ).forEach { (provider, expectedTag) ->
            val result =
                CdnFormatters.formatCdnStatus(
                    makeCdnInfo(status = CacheStatus.HIT, cdnProvider = provider),
                )
            assertTrue(result.startsWith(expectedTag), "Expected '$expectedTag' in: $result")
        }
    }
    // ── TTFB formatter tests ──────────────────────────────────────────────────

    @Test
    fun `formatTtfb with null returns dash`() {
        assertEquals("—", OverlayFormatters.formatTtfb(null))
    }

    @Test
    fun `formatTtfb with estimated timing prefixes tilde`() {
        val result = OverlayFormatters.formatTtfb(NetworkTiming(ttfbMs = 40L, transferDurationMs = 160L, isEstimated = true))
        assertEquals("~40ms", result)
    }

    @Test
    fun `formatTtfb with measured timing has no tilde prefix`() {
        val result = OverlayFormatters.formatTtfb(NetworkTiming(ttfbMs = 40L, transferDurationMs = 160L, isEstimated = false))
        assertEquals("40ms", result)
    }

    // ── ABR formatting tests ──────────────────────────────────────────────────

    private fun makeTrack(
        height: Int,
        bitrate: Int = 2_500_000,
    ) = ActiveTrackInfo(
        bitrate = bitrate,
        width = height * 16 / 9,
        height = height,
        codecs = "avc1.42e00a",
    )

    @Test
    fun `formatAbrSwitch with resolution change shows height labels`() {
        val from = makeTrack(720)
        val to = makeTrack(1080)
        val result = OverlayFormatters.formatAbrSwitch(from, to)
        assertEquals("720p \u2192 1080p", result)
    }

    @Test
    fun `formatAbrSwitch with bitrate-only change shows bitrate labels`() {
        val from = makeTrack(720, bitrate = 1_500_000)
        val to = makeTrack(720, bitrate = 5_000_000)
        val result = OverlayFormatters.formatAbrSwitch(from, to)
        assertTrue(result.contains("Mbps") || result.contains("kbps"), "Expected bitrate labels in: $result")
    }

    @Test
    fun `formatAbrSwitch with null from shows dash arrow label`() {
        val to = makeTrack(720)
        val result = OverlayFormatters.formatAbrSwitch(null, to)
        assertTrue(result.contains("720p"), "Expected '→ 720p' in: $result")
    }

    @Test
    fun `formatBufferDuration formats seconds`() {
        val result = OverlayFormatters.formatBufferDuration(12_400L)
        assertEquals("buf: 12.4s", result)
    }

    @Test
    fun `formatRelativeTimestamp formats minutes and seconds`() {
        val base = 1_000L
        val result = OverlayFormatters.formatRelativeTimestamp(base + 62_000L, base)
        assertEquals("+1:02", result)
    }

    @Test
    fun `formatRelativeTimestamp zero offset returns plus zero`() {
        val base = 5_000L
        val result = OverlayFormatters.formatRelativeTimestamp(base, base)
        assertEquals("+0:00", result)
    }

    @Test
    fun `formatSwitchReason returns correct label for each enum value`() {
        assertEquals("INITIAL", OverlayFormatters.formatSwitchReason(SwitchReason.INITIAL))
        assertEquals("ADAPTIVE", OverlayFormatters.formatSwitchReason(SwitchReason.ADAPTIVE))
        assertEquals("MANUAL", OverlayFormatters.formatSwitchReason(SwitchReason.MANUAL))
        assertEquals("TRICKPLAY", OverlayFormatters.formatSwitchReason(SwitchReason.TRICKPLAY))
        assertEquals("UNKNOWN", OverlayFormatters.formatSwitchReason(SwitchReason.UNKNOWN))
    }

    // ── Error formatter tests ─────────────────────────────────────────────────

    @Test
    fun `formatErrorCategory returns short label for each category`() {
        assertEquals("LOAD", OverlayFormatters.formatErrorCategory(ErrorCategory.LOAD_ERROR))
        assertEquals("CODEC", OverlayFormatters.formatErrorCategory(ErrorCategory.VIDEO_CODEC_ERROR))
        assertEquals("FRAMES", OverlayFormatters.formatErrorCategory(ErrorCategory.DROPPED_FRAMES))
        assertEquals("AUDIO", OverlayFormatters.formatErrorCategory(ErrorCategory.AUDIO_SINK_ERROR))
        assertEquals("ACODEC", OverlayFormatters.formatErrorCategory(ErrorCategory.AUDIO_CODEC_ERROR))
    }

    @Test
    fun `formatAbsoluteTimestamp returns HH mm ss SSS format`() {
        // Just verify the format shape: HH:mm:ss.SSS (length 12 with separators)
        val result = OverlayFormatters.formatAbsoluteTimestamp(0L)
        // Should match HH:mm:ss.SSS
        assertTrue(result.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")), "Expected HH:mm:ss.SSS format in: $result")
    }

    @Test
    fun `formatErrorsForExport produces correct header and rows`() {
        val base = 1_000L
        val errors =
            listOf(
                PlaybackErrorEvent(
                    timestampMs = base + 23_000L,
                    category = ErrorCategory.LOAD_ERROR,
                    message = "HTTP 404: seg_42.ts",
                ),
                PlaybackErrorEvent(
                    timestampMs = base + 65_000L,
                    category = ErrorCategory.DROPPED_FRAMES,
                    message = "5 frames in 100ms",
                ),
            )

        val result = OverlayFormatters.formatErrorsForExport(errors, base)

        assertTrue(result.startsWith("[StreamProbe] 2 errors"), "Expected header")
        assertTrue(result.contains("#1"), "Expected first row")
        assertTrue(result.contains("LOAD"), "Expected LOAD category")
        assertTrue(result.contains("HTTP 404: seg_42.ts"), "Expected HTTP 404 message")
        assertTrue(result.contains("#2"), "Expected second row")
        assertTrue(result.contains("FRAMES"), "Expected FRAMES category")
        assertTrue(result.contains(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]")), "Expected absolute timestamp in [HH:mm:ss.SSS] format")
    }

    @Test
    fun `formatErrorsForExport includes detail on second line when present`() {
        val base = 1_000L
        val errors =
            listOf(
                PlaybackErrorEvent(
                    timestampMs = base + 5_000L,
                    category = ErrorCategory.LOAD_ERROR,
                    message = "HTTP 500: seg_1.ts",
                    detail = "java.io.IOException: connection reset",
                ),
            )

        val result = OverlayFormatters.formatErrorsForExport(errors, base)

        assertTrue(result.contains("    java.io.IOException: connection reset"), "Expected detail indented on next line")
    }

    @Test
    fun `formatErrorsForExport with empty list returns just header`() {
        val result = OverlayFormatters.formatErrorsForExport(emptyList(), 0L)
        assertEquals("[StreamProbe] 0 errors", result)
    }

    // ── Audio formatter tests ─────────────────────────────────────────────────

    private fun makeAudio(
        language: String? = "en",
        label: String? = null,
        codecs: String? = "mp4a.40.2",
        bitrate: Int = 128_000,
        channelCount: Int = 2,
        sampleRate: Int = 48_000,
        isMuxed: Boolean = false,
    ) = AudioTrackInfo(
        language = language,
        label = label,
        codecs = codecs,
        bitrate = bitrate,
        channelCount = channelCount,
        sampleRate = sampleRate,
        isMuxed = isMuxed,
    )

    @Test
    fun `formatActiveAudio with null returns Loading`() {
        assertEquals("Loading\u2026", OverlayFormatters.formatActiveAudio(null))
    }

    @Test
    fun `formatActiveAudio with only language contains display language`() {
        // Platform-neutral: the formatter must surface whatever the platform `displayLanguage`
        // actual resolves for the tag (Android and iOS both: "English").
        val result = OverlayFormatters.formatActiveAudio(makeAudio(language = "en"))
        val expected = displayLanguage("en")
        assertNotNull(expected, "displayLanguage should resolve a non-null name for 'en'")
        assertTrue(result.contains(expected), "Expected '$expected' in: $result")
    }

    @Test
    fun `formatActiveAudio prefers label over language`() {
        val result = OverlayFormatters.formatActiveAudio(makeAudio(language = "en", label = "English (Descriptive)"))
        assertTrue(result.contains("English (Descriptive)"), "Expected label in: $result")
    }

    @Test
    fun `formatActiveAudio with stereo shows stereo`() {
        val result = OverlayFormatters.formatActiveAudio(makeAudio(channelCount = 2))
        assertTrue(result.contains("stereo"), "Expected 'stereo' in: $result")
    }

    @Test
    fun `formatActiveAudio with 5_1 shows 5_1`() {
        val result = OverlayFormatters.formatActiveAudio(makeAudio(channelCount = 6))
        assertTrue(result.contains("5.1"), "Expected '5.1' in: $result")
    }

    @Test
    fun `formatActiveAudio includes bitrate and sampleRate`() {
        val result = OverlayFormatters.formatActiveAudio(makeAudio(bitrate = 128_000, sampleRate = 48_000))
        assertTrue(result.contains("128"), "Expected bitrate in: $result")
        assertTrue(result.contains("48.0 kHz"), "Expected kHz in: $result")
    }

    @Test
    fun `formatActiveAudio with no language and no label returns Unknown`() {
        val result =
            OverlayFormatters.formatActiveAudio(
                makeAudio(language = null, label = null, codecs = null, bitrate = 0, channelCount = 0, sampleRate = 0),
            )
        assertEquals("Unknown", result)
    }

    // ── Subtitle formatter tests ──────────────────────────────────────────────

    private fun makeSubtitle(
        language: String? = "en",
        label: String? = null,
        mimeType: String? = "text/vtt",
        kind: SubtitleKind = SubtitleKind.SIDECAR,
    ) = SubtitleTrackInfo(language = language, label = label, mimeType = mimeType, kind = kind)

    @Test
    fun `formatActiveSubtitle with null returns Off`() {
        assertEquals("Off", OverlayFormatters.formatActiveSubtitle(null))
    }

    @Test
    fun `formatActiveSubtitle with language shows display language`() {
        // Platform-neutral: see formatActiveAudio counterpart. Android and iOS both resolve "Turkish".
        val result = OverlayFormatters.formatActiveSubtitle(makeSubtitle(language = "tr"))
        val expected = displayLanguage("tr")
        assertNotNull(expected, "displayLanguage should resolve a non-null name for 'tr'")
        assertTrue(result.contains(expected), "Expected '$expected' in: $result")
    }

    @Test
    fun `formatActiveSubtitle CC adds the CC suffix`() {
        val result = OverlayFormatters.formatActiveSubtitle(makeSubtitle(kind = SubtitleKind.CC))
        assertTrue(result.contains("(CC)"), "Expected '(CC)' in: $result")
    }

    @Test
    fun `formatActiveSubtitle SIDECAR does not add the CC suffix`() {
        val result = OverlayFormatters.formatActiveSubtitle(makeSubtitle(kind = SubtitleKind.SIDECAR))
        assertTrue(!result.contains("(CC)"), "Expected no '(CC)' in: $result")
    }

    @Test
    fun `formatActiveSubtitle shows WebVTT for vtt mime type`() {
        val result = OverlayFormatters.formatActiveSubtitle(makeSubtitle(mimeType = "text/vtt"))
        assertTrue(result.contains("WebVTT"), "Expected 'WebVTT' in: $result")
    }

    @Test
    fun `formatActiveSubtitle shows TTML for ttml mime type`() {
        val result = OverlayFormatters.formatActiveSubtitle(makeSubtitle(mimeType = "application/ttml+xml"))
        assertTrue(result.contains("TTML"), "Expected 'TTML' in: $result")
    }

    @Test
    fun `formatActiveSubtitle with no language and no label returns Unknown`() {
        val result =
            OverlayFormatters.formatActiveSubtitle(
                makeSubtitle(language = null, label = null, mimeType = null),
            )
        assertEquals("Unknown", result)
    }

    @Test
    fun `segmentTrackBadge maps each track type`() {
        assertEquals("V", OverlayFormatters.segmentTrackBadge(SegmentTrackType.VIDEO))
        assertEquals("A", OverlayFormatters.segmentTrackBadge(SegmentTrackType.AUDIO))
        assertEquals("T", OverlayFormatters.segmentTrackBadge(SegmentTrackType.TEXT))
        assertNull(OverlayFormatters.segmentTrackBadge(SegmentTrackType.UNKNOWN))
    }

    @Test
    fun `formatSegmentMetric first line includes track label and extension`() {
        val result =
            OverlayFormatters.formatSegmentMetric(
                makeMetric(uri = "https://host/path/seg7.aac", trackType = SegmentTrackType.AUDIO),
            )
        assertEquals("DL: 200ms  ·  AUDIO  ·  aac", result.lines().first())
    }

    @Test
    fun `formatSegmentMetric first line omits track label for UNKNOWN but keeps extension`() {
        val result =
            OverlayFormatters.formatSegmentMetric(
                makeMetric(uri = "https://host/path/seg7.ts", trackType = SegmentTrackType.UNKNOWN),
            )
        assertEquals("DL: 200ms  ·  ts", result.lines().first())
    }

    @Test
    fun `formatSegmentMetric first line is plain DL when no track type or extension`() {
        val result =
            OverlayFormatters.formatSegmentMetric(
                makeMetric(uri = "https://host/path/segment", trackType = SegmentTrackType.UNKNOWN),
            )
        assertEquals("DL: 200ms", result.lines().first())
    }

    @Test
    fun `segmentExtension reads a plain uri`() {
        assertEquals("ts", OverlayFormatters.segmentExtension("https://host/path/seg3.ts"))
    }

    @Test
    fun `segmentExtension strips query string`() {
        assertEquals("ts", OverlayFormatters.segmentExtension("https://host/path/seg3.ts?token=abc"))
    }

    @Test
    fun `segmentExtension strips fragment`() {
        assertEquals("m4s", OverlayFormatters.segmentExtension("https://host/path/seg3.m4s#frag"))
    }

    @Test
    fun `segmentExtension returns null for extensionless path`() {
        assertNull(OverlayFormatters.segmentExtension("https://host/path/segment"))
    }

    @Test
    fun `segmentExtension returns null for unknown uri`() {
        assertNull(OverlayFormatters.segmentExtension("(unknown)"))
    }

    @Test
    fun `segmentExtension returns null for overlong extension`() {
        assertNull(OverlayFormatters.segmentExtension("https://host/path/file.megabyte"))
    }
}

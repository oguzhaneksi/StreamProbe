package com.streamprobe.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ProbeCore]: proves the [DiagnosticsSink] writes reach the wrapped
 * [SessionStore] and that [ProbeCore.clear] resets it. Models are built via the existing
 * primitive mapper helpers so the test needs no knowledge of every model field.
 */
class ProbeCoreTest {
    @Test
    fun updateActiveTrack_writesThroughToStore() {
        val core = ProbeCore()
        val track = activeTrackFromIndicatedBitrate(1_500_000.0)
        core.updateActiveTrack(track)
        assertEquals(track, core.sessionStore.activeTrack.value)
    }

    @Test
    fun addSegmentMetric_writesThroughToStore() {
        val core = ProbeCore()
        val metric =
            accessLogSegmentMetric(
                nowMs = 1_000L,
                uri = "seg-1.ts",
                sizeBytes = 1_000L,
                observedBitrate = 8_000.0,
                transferDurationSeconds = 1.0,
            )
        core.addSegmentMetric(metric)
        assertEquals(metric, core.sessionStore.latestSegmentMetric.value)
        assertEquals(
            metric,
            core.sessionStore.segmentMetrics.value
                .lastOrNull(),
        )
    }

    @Test
    fun clear_resetsTheStore() {
        val core = ProbeCore()
        core.updateActiveTrack(activeTrackFromIndicatedBitrate(1_000_000.0))
        core.addSegmentMetric(
            accessLogSegmentMetric(
                nowMs = 2_000L,
                uri = "seg-2.ts",
                sizeBytes = 500L,
                observedBitrate = 4_000.0,
                transferDurationSeconds = 1.0,
            ),
        )
        core.clear()
        assertNull(core.sessionStore.activeTrack.value)
        assertEquals(emptyList(), core.sessionStore.segmentMetrics.value)
    }
}

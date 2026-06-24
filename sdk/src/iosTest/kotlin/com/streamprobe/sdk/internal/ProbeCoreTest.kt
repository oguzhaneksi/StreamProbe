package com.streamprobe.sdk.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ProbeCore]: proves the [DiagnosticsSink] writes reach the wrapped
 * [SessionStore] and that [ProbeCore.clear] resets it. Models are built via the existing
 * primitive mapper helpers so the test needs no knowledge of every model field.
 *
 * Lifecycle tests ([start_propagatesStoreWritesToViewState] etc.) replace [Dispatchers.Main]
 * with a [StandardTestDispatcher] so that [ProbeCore.start] — which creates a
 * `CoroutineScope(Dispatchers.Main + SupervisorJob())` — uses the test dispatcher and can be
 * driven by [advanceUntilIdle]. This mirrors the pattern in `OverlayPresenterTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeCoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Sink write-through ─────────────────────────────────────────────────────

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

    // ── Start / stop lifecycle ─────────────────────────────────────────────────

    @Test
    fun start_propagatesStoreWritesToViewState() =
        runTest(testDispatcher) {
            val core = ProbeCore()
            val before = core.presenter.viewState.value

            core.start()
            core.updateActiveTrack(activeTrackFromIndicatedBitrate(2_000_000.0))
            advanceUntilIdle()

            val after = core.presenter.viewState.value
            assertEquals(true, after != before, "viewState must have updated after a store write")
        }

    @Test
    fun start_isIdempotent() =
        runTest(testDispatcher) {
            val core = ProbeCore()

            core.start()
            core.start() // second call must be a no-op, not a crash

            core.updateActiveTrack(activeTrackFromIndicatedBitrate(2_000_000.0))
            advanceUntilIdle()

            val state = core.presenter.viewState.value
            // viewState contains the active-track text — verify it updated despite the double start
            assertEquals(
                true,
                state.stats.activeTrackText.contains("2.0 Mbps"),
                "viewState stats must reflect the written track after double start(): " +
                    "got '${state.stats.activeTrackText}'",
            )
        }

    @Test
    fun stop_freezesUpdates() =
        runTest(testDispatcher) {
            val core = ProbeCore()
            core.start()

            core.updateActiveTrack(activeTrackFromIndicatedBitrate(1_000_000.0))
            advanceUntilIdle()
            val frozen = core.presenter.viewState.value

            core.stop()

            core.updateActiveTrack(activeTrackFromIndicatedBitrate(9_000_000.0))
            advanceUntilIdle()

            assertEquals(frozen, core.presenter.viewState.value, "viewState must not change after stop()")
        }

    @Test
    fun stop_isSafeWhenNeverStarted() {
        val core = ProbeCore()
        core.stop() // must not throw
    }
}

package com.streamprobe.sdk.internal.presenter

import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.model.ActiveTrackInfo
import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.PlaybackErrorEvent
import com.streamprobe.sdk.model.SegmentMetric
import com.streamprobe.sdk.model.TracksSnapshot
import com.streamprobe.sdk.model.VariantInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayPresenterTest {
    private val dispatcher = StandardTestDispatcher()

    private fun makeCdnInfo(status: CacheStatus = CacheStatus.UNKNOWN) =
        CdnHeaderInfo(
            cacheControl = null,
            xCache = null,
            via = null,
            cdnSpecificHeaders = emptyMap(),
            cacheStatus = status,
            cdnProvider = null,
        )

    private fun makeMetric() =
        SegmentMetric(
            requestTimestampMs = 1_000L,
            totalDurationMs = 200L,
            sizeBytes = 500_000L,
            throughputBytesPerSec = 2_500_000L,
            uri = "https://example.com/seg.ts",
            cdnInfo = makeCdnInfo(CacheStatus.HIT),
        )

    private fun makeError(message: String = "HTTP 404") =
        PlaybackErrorEvent(
            timestampMs = 1_000L,
            category = ErrorCategory.LOAD_ERROR,
            message = message,
        )

    private fun makeDrmEvent(timestampMs: Long = 1_000L) =
        DrmSessionEvent.SessionAcquired(timestampMs, DrmScheme.WIDEVINE, DrmSessionState.OPENING)

    // ── Task 2: initial state ─────────────────────────────────────────────────

    @Test
    fun initialStateIsTracksNotCollapsed() {
        val state = OverlayPresenter(SessionStore()).viewState.value
        assertEquals(ViewMode.TRACKS, state.mode)
        assertFalse(state.isCollapsed)
        assertEquals("Loading…", state.stats.activeTrackText)
        assertFalse(state.stats.drmVisible)
        assertNull(state.errorIndicator)
        assertTrue(state.chipRowVisible)
        assertFalse(state.errorsHeaderVisible)
        assertEquals("Errors (0)", state.errorsTitle)
        assertTrue(state.lists.renditionRows.isEmpty())
    }

    // ── Task 3: start() folds store updates into viewState ────────────────────

    @Test
    fun startReflectsStoreUpdates() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.updateActiveTrack(ActiveTrackInfo(bitrate = 5_000_000, width = 1920, height = 1080, codecs = null))
            store.addSegmentMetric(makeMetric())
            store.updateTrackList(
                TracksSnapshot(variants = listOf(VariantInfo(5_000_000, 1920, 1080, "avc1", 30f))),
            )
            advanceUntilIdle()

            val state = presenter.viewState.value
            assertEquals("1920×1080  ·  5.0 Mbps", state.stats.activeTrackText)
            assertEquals("DL: 200ms\nSize: 500.0 KB  ·  TP: 2.5 MB/s", state.stats.latestSegmentText)
            // VIDEO header + 1 variant row.
            assertEquals(2, state.lists.renditionRows.size)
            assertEquals(OverlayRow.SectionHeader("VIDEO"), state.lists.renditionRows[0])
            assertTrue(state.lists.renditionRows[1] is OverlayRow.Video)
            scope.cancel()
        }

    @Test
    fun nullTrackListLeavesRenditionRowsUntouched() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.updateTrackList(TracksSnapshot(variants = listOf(VariantInfo(1_000_000, 640, 360, null, -1f))))
            advanceUntilIdle()
            assertEquals(2, presenter.viewState.value.lists.renditionRows.size)

            // A null-yielding clear must NOT wipe the already-assembled rows (matches old observe behavior).
            store.clear()
            advanceUntilIdle()
            assertEquals(2, presenter.viewState.value.lists.renditionRows.size)
            scope.cancel()
        }

    // ── Task 4: chip selection + collapse ─────────────────────────────────────

    @Test
    fun onChipSelectedSwitchesModeAndChrome() {
        val presenter = OverlayPresenter(SessionStore())

        presenter.onChipSelected(ViewMode.SEGMENTS)
        var state = presenter.viewState.value
        assertEquals(ViewMode.SEGMENTS, state.mode)
        assertTrue(state.chipRowVisible)
        assertFalse(state.errorsHeaderVisible)

        presenter.onChipSelected(ViewMode.ERRORS)
        state = presenter.viewState.value
        assertEquals(ViewMode.ERRORS, state.mode)
        assertFalse(state.chipRowVisible)
        assertTrue(state.errorsHeaderVisible)
    }

    @Test
    fun onCollapseToggledFlipsCollapsed() {
        val presenter = OverlayPresenter(SessionStore())
        assertFalse(presenter.viewState.value.isCollapsed)
        presenter.onCollapseToggled()
        assertTrue(presenter.viewState.value.isCollapsed)
        presenter.onCollapseToggled()
        assertFalse(presenter.viewState.value.isCollapsed)
    }

    // ── Task 5: error indicator + tap + back ──────────────────────────────────

    @Test
    fun errorIndicatorReflectsCount() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.addPlaybackError(makeError("e1"))
            store.addPlaybackError(makeError("e2"))
            store.addPlaybackError(makeError("e3"))
            advanceUntilIdle()

            val indicator = presenter.viewState.value.errorIndicator
            assertNotNull(indicator)
            assertEquals("⚠ 3", indicator.text)
            assertTrue(indicator.contentDescription.contains("3"))
            assertEquals("Errors (3)", presenter.viewState.value.errorsTitle)
            scope.cancel()
        }

    @Test
    fun errorIndicatorTapExpandsAndSwitchesSavingPrevious() {
        val presenter = OverlayPresenter(SessionStore())
        presenter.onChipSelected(ViewMode.SEGMENTS)
        presenter.onCollapseToggled()
        assertTrue(presenter.viewState.value.isCollapsed)

        presenter.onErrorIndicatorTapped()
        var state = presenter.viewState.value
        assertFalse(state.isCollapsed)
        assertEquals(ViewMode.ERRORS, state.mode)

        presenter.onBackPressed()
        state = presenter.viewState.value
        assertEquals(ViewMode.SEGMENTS, state.mode)
    }

    // ── Task 6: DRM visibility + auto-fallback ────────────────────────────────

    @Test
    fun drmEventsMakeDrmVisible() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.addDrmSessionEvent(makeDrmEvent())
            advanceUntilIdle()

            assertTrue(presenter.viewState.value.stats.drmVisible)
            scope.cancel()
        }

    @Test
    fun drmEmptyingFallsBackFromDrmToTracks() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.addDrmSessionEvent(makeDrmEvent())
            advanceUntilIdle()
            presenter.onChipSelected(ViewMode.DRM)
            assertEquals(ViewMode.DRM, presenter.viewState.value.mode)

            store.clear()
            advanceUntilIdle()
            assertEquals(ViewMode.TRACKS, presenter.viewState.value.mode)
            scope.cancel()
        }

    @Test
    fun drmEmptyingResetsPreviousModeSoBackGoesToTracks() =
        runTest(dispatcher) {
            val store = SessionStore()
            val presenter = OverlayPresenter(store)
            val scope = CoroutineScope(dispatcher)
            presenter.start(scope)

            store.addDrmSessionEvent(makeDrmEvent())
            store.addPlaybackError(makeError())
            advanceUntilIdle()

            presenter.onChipSelected(ViewMode.DRM)
            presenter.onErrorIndicatorTapped() // previousViewMode = DRM, mode = ERRORS
            assertEquals(ViewMode.ERRORS, presenter.viewState.value.mode)

            store.clear() // empties DRM → previousViewMode must reset to TRACKS
            advanceUntilIdle()

            presenter.onBackPressed()
            assertEquals(ViewMode.TRACKS, presenter.viewState.value.mode)
            scope.cancel()
        }

    // ── Task 7: clear errors ──────────────────────────────────────────────────

    @Test
    fun onClearErrorsClickedEmptiesStore() {
        val store = SessionStore()
        store.addPlaybackError(makeError())
        OverlayPresenter(store).onClearErrorsClicked()
        assertTrue(store.playbackErrors.value.isEmpty())
    }
}

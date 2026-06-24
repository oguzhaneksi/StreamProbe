package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.DrmStatusInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DrmSessionStoreTest {
    private lateinit var store: SessionStore

    @BeforeTest
    fun setUp() {
        store = SessionStore()
    }

    private fun makeSessionAcquired(timestampMs: Long = 1_000L) =
        DrmSessionEvent.SessionAcquired(timestampMs, DrmScheme.WIDEVINE, DrmSessionState.OPENING)

    @Test
    fun `addDrmSessionEvent appends to list`() =
        runTest {
            val e1 = makeSessionAcquired(1_000L)
            val e2 = makeSessionAcquired(2_000L)

            store.addDrmSessionEvent(e1)
            store.addDrmSessionEvent(e2)

            val result = store.drmSessionEvents.first()
            assertEquals(2, result.size)
            // The store stamps a monotonic id on each event; everything else is preserved.
            assertEquals(e1.copy(id = 1L), result[0])
            assertEquals(e2.copy(id = 2L), result[1])
            assertEquals(1L, result[0].id)
            assertEquals(2L, result[1].id)
        }

    @Test
    fun `DRM event list is capped at MAX_DRM_EVENTS`() =
        runTest {
            repeat(SessionStore.MAX_DRM_EVENTS + 1) { i ->
                store.addDrmSessionEvent(makeSessionAcquired(i.toLong()))
            }

            val result = store.drmSessionEvents.first()
            assertEquals(SessionStore.MAX_DRM_EVENTS, result.size)
            assertEquals(1L, result.first().timestampMs)
            assertEquals(SessionStore.MAX_DRM_EVENTS.toLong(), result.last().timestampMs)
        }

    @Test
    fun `updateDrmState emits to currentDrmState flow`() =
        runTest {
            val info = DrmStatusInfo(DrmScheme.WIDEVINE, DrmSessionState.OPENED_WITH_KEYS, 312L)

            store.updateDrmState(info)

            assertEquals(info, store.currentDrmState.first())
        }

    @Test
    fun `updateDrmState can be set to null`() =
        runTest {
            store.updateDrmState(DrmStatusInfo(DrmScheme.WIDEVINE, DrmSessionState.OPENING))
            store.updateDrmState(null)

            assertNull(store.currentDrmState.first())
        }

    @Test
    fun `clear resets DRM flows`() =
        runTest {
            store.addDrmSessionEvent(makeSessionAcquired())
            store.updateDrmState(DrmStatusInfo(DrmScheme.WIDEVINE, DrmSessionState.OPENED_WITH_KEYS))

            store.clear()

            assertEquals(emptyList<DrmSessionEvent>(), store.drmSessionEvents.first())
            assertNull(store.currentDrmState.first())
        }

    @Test
    fun `addDrmSessionEvent after clear restarts ids from 1`() =
        runTest {
            store.addDrmSessionEvent(makeSessionAcquired(1_000L))
            store.addDrmSessionEvent(makeSessionAcquired(2_000L))
            store.clear()

            store.addDrmSessionEvent(makeSessionAcquired(3_000L))

            val result = store.drmSessionEvents.first()
            assertEquals(1, result.size)
            assertEquals(1L, result[0].id)
        }
}

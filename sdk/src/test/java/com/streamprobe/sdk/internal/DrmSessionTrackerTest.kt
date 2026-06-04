package com.streamprobe.sdk.internal

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.KeyRequestInfo
import com.streamprobe.sdk.model.DrmSessionEvent
import com.streamprobe.sdk.model.DrmSessionState
import com.streamprobe.sdk.model.ErrorCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DrmSessionTrackerTest {
    private lateinit var sessionStore: SessionStore
    private lateinit var tracker: DrmSessionTracker

    @Before
    fun setUp() {
        sessionStore = SessionStore()
        tracker = DrmSessionTracker(sessionStore)
    }

    private fun makeEventTime(): AnalyticsListener.EventTime =
        AnalyticsListener.EventTime(0L, Timeline.EMPTY, 0, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L)

    private fun makeKeyRequestInfo(): KeyRequestInfo = KeyRequestInfo.Builder().build()

    @Test
    fun `onDrmSessionAcquired emits SessionAcquired with mapped state`() =
        runTest {
            tracker.onDrmSessionAcquired(makeEventTime(), DrmSession.STATE_OPENING)

            val events = sessionStore.drmSessionEvents.first()
            assertEquals(1, events.size)
            val acquired = events[0] as DrmSessionEvent.SessionAcquired
            assertEquals(DrmSessionState.OPENING, acquired.state)
        }

    @Test
    fun `onDrmSessionManagerError emits both DrmSessionEvent and PlaybackErrorEvent`() =
        runTest {
            val error = RuntimeException("License request failed")

            tracker.onDrmSessionManagerError(makeEventTime(), error)

            val drmEvents = sessionStore.drmSessionEvents.first()
            assertEquals(1, drmEvents.size)
            val sessionError = drmEvents[0] as DrmSessionEvent.SessionError
            assertEquals("License request failed", sessionError.message)

            val errorEvents = sessionStore.playbackErrors.first()
            assertEquals(1, errorEvents.size)
            assertEquals(ErrorCategory.DRM_ERROR, errorEvents[0].category)
            assertEquals("License request failed", errorEvents[0].message)
        }

    @Test
    fun `onDrmSessionReleased emits SessionReleased and clears currentDrmState`() =
        runTest {
            tracker.onDrmSessionAcquired(makeEventTime(), DrmSession.STATE_OPENING)
            tracker.onDrmSessionReleased(makeEventTime())

            val events = sessionStore.drmSessionEvents.first()
            assertEquals(2, events.size)
            assertEquals(true, events[1] is DrmSessionEvent.SessionReleased)
            assertNull(sessionStore.currentDrmState.first())
        }

    @Test
    fun `reset clears DRM state so next session starts fresh`() =
        runTest {
            tracker.onDrmSessionAcquired(makeEventTime(), DrmSession.STATE_OPENING)
            tracker.reset()
            tracker.onDrmKeysLoaded(makeEventTime(), makeKeyRequestInfo())

            val events = sessionStore.drmSessionEvents.first()
            val keysLoaded = events.filterIsInstance<DrmSessionEvent.KeysLoaded>()
            assertEquals(1, keysLoaded.size)
            assertEquals(0L, keysLoaded[0].licenseLatencyMs)
        }
}

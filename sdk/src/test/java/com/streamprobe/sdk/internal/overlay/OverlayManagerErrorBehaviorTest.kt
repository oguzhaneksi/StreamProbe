package com.streamprobe.sdk.internal.overlay

import android.view.View
import androidx.activity.ComponentActivity
import com.streamprobe.sdk.internal.SessionStore
import com.streamprobe.sdk.model.ErrorCategory
import com.streamprobe.sdk.model.PlaybackErrorEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OverlayManagerErrorBehaviorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sessionStore: SessionStore
    private lateinit var manager: OverlayManager
    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStore = SessionStore()
        manager = OverlayManager(sessionStore)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        manager.show(activity)
    }

    @After
    fun tearDown() {
        manager.hide()
        Dispatchers.resetMain()
    }

    private fun overlay() = manager.overlayViewForTest()

    private fun makeError(message: String = "HTTP 404: seg.ts") = PlaybackErrorEvent(
        timestampMs = System.currentTimeMillis(),
        category = ErrorCategory.LOAD_ERROR,
        message = message,
    )

    @Test
    fun `header indicator hidden when errors empty`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(View.GONE, overlay().errorIndicator.visibility)
    }

    @Test
    fun `header indicator visible and counted when errors present`() = runTest(testDispatcher) {
        sessionStore.addPlaybackError(makeError("Error 1"))
        sessionStore.addPlaybackError(makeError("Error 2"))
        sessionStore.addPlaybackError(makeError("Error 3"))

        advanceUntilIdle()

        assertEquals(View.VISIBLE, overlay().errorIndicator.visibility)
        val text = overlay().errorIndicator.text.toString()
        assertEquals(true, text.contains("3"))
    }

    @Test
    fun `tapping indicator switches to errors view`() = runTest(testDispatcher) {
        sessionStore.addPlaybackError(makeError())
        advanceUntilIdle()

        overlay().errorIndicator.performClick()

        assertEquals(View.VISIBLE, overlay().errorsViewHeader.visibility)
        assertEquals(View.GONE, overlay().variantsChip.visibility)
    }

    @Test
    fun `tapping indicator while collapsed expands body`() = runTest(testDispatcher) {
        overlay().collapseBtn.performClick()
        assertEquals(View.GONE, overlay().body.visibility)

        sessionStore.addPlaybackError(makeError())
        advanceUntilIdle()

        overlay().errorIndicator.performClick()

        assertEquals(View.VISIBLE, overlay().body.visibility)
        assertEquals(View.VISIBLE, overlay().errorsViewHeader.visibility)
    }

    @Test
    fun `back button restores previousViewMode`() = runTest(testDispatcher) {
        overlay().segmentsChip.performClick()
        assertEquals(true, overlay().segmentsChip.isChecked)

        sessionStore.addPlaybackError(makeError())
        advanceUntilIdle()

        overlay().errorIndicator.performClick()
        assertEquals(View.VISIBLE, overlay().errorsViewHeader.visibility)

        overlay().backButton.performClick()

        assertEquals(View.GONE, overlay().errorsViewHeader.visibility)
        assertEquals(true, overlay().segmentsChip.isChecked)
    }

    @Test
    fun `clear button empties errors and hides indicator`() = runTest(testDispatcher) {
        sessionStore.addPlaybackError(makeError())
        advanceUntilIdle()

        assertEquals(View.VISIBLE, overlay().errorIndicator.visibility)

        overlay().errorIndicator.performClick()
        overlay().clearButton.performClick()
        advanceUntilIdle()

        assertEquals(View.GONE, overlay().errorIndicator.visibility)
        assertEquals(0, sessionStore.playbackErrors.value.size)
    }
}

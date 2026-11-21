package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The liveness record the watchdog and diagnostics page read: every stage
 * stamp lands in the snapshot, a session start resets the record, and the
 * last error survives until the next session.
 */
class CaptureHealthTest {

    @Before
    fun reset() {
        CaptureHealth.onSessionStarted(now = 0L)
    }

    @Test
    fun `a fresh session reports an empty record`() {
        val snapshot = CaptureHealth.snapshot()
        assertEquals(0L, snapshot.lastFrameAcquiredAt)
        assertEquals(0L, snapshot.lastOcrRunAt)
        assertEquals(0L, snapshot.lastOcrSkippedAt)
        assertEquals(0L, snapshot.lastLinePublishedAt)
        assertEquals(0L, snapshot.lastDisplayRebuildAt)
        assertNull(snapshot.lastError)
        assertNull(snapshot.lastStopReason)
        assertEquals(0L, snapshot.lastStopAt)
    }

    @Test
    fun `every stage stamp is visible in the snapshot`() {
        CaptureHealth.onFrameAcquired(now = 10L)
        CaptureHealth.onOcrRun(now = 20L)
        CaptureHealth.onOcrSkipped(now = 30L)
        CaptureHealth.onLinePublished(now = 40L)
        CaptureHealth.onDisplayRebuilt(now = 50L)

        val snapshot = CaptureHealth.snapshot()
        assertEquals(10L, snapshot.lastFrameAcquiredAt)
        assertEquals(20L, snapshot.lastOcrRunAt)
        assertEquals(30L, snapshot.lastOcrSkippedAt)
        assertEquals(40L, snapshot.lastLinePublishedAt)
        assertEquals(50L, snapshot.lastDisplayRebuildAt)
    }

    @Test
    fun `the last error is kept with its timestamp`() {
        CaptureHealth.onError("capture died", now = 99L)
        val snapshot = CaptureHealth.snapshot()
        assertEquals("capture died", snapshot.lastError)
        assertEquals(99L, snapshot.lastErrorAt)
    }

    @Test
    fun `a new session clears the previous session stamps and error`() {
        CaptureHealth.onFrameAcquired(now = 10L)
        CaptureHealth.onError("old failure", now = 20L)

        CaptureHealth.onSessionStarted(now = 500L)

        val snapshot = CaptureHealth.snapshot()
        assertEquals(500L, snapshot.sessionStartedAt)
        assertEquals(0L, snapshot.lastFrameAcquiredAt)
        assertNull(snapshot.lastError)
    }

    @Test
    fun `the stop reason is recorded with its timestamp`() {
        CaptureHealth.onSessionStopped(CaptureHealth.StopReasons.USER_BUTTON, now = 60L)

        val snapshot = CaptureHealth.snapshot()
        assertEquals(CaptureHealth.StopReasons.USER_BUTTON, snapshot.lastStopReason)
        assertEquals(60L, snapshot.lastStopAt)
    }

    @Test
    fun `a new session clears the previous stop reason`() {
        CaptureHealth.onSessionStopped(CaptureHealth.StopReasons.PROJECTION_ENDED, now = 70L)
        CaptureHealth.onSessionStarted(now = 80L)

        val snapshot = CaptureHealth.snapshot()
        assertNull(snapshot.lastStopReason)
        assertEquals(0L, snapshot.lastStopAt)
    }
}

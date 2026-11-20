package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureTrackerTest {

    @Test
    fun `threshold reached only after max consecutive failures`() {
        val tracker = FailureTracker(maxConsecutiveFailures = 3)
        assertFalse(tracker.onFailure())
        assertFalse(tracker.onFailure())
        assertTrue(tracker.onFailure())
    }

    @Test
    fun `a success resets the consecutive count`() {
        val tracker = FailureTracker(maxConsecutiveFailures = 2)
        assertFalse(tracker.onFailure())
        tracker.onSuccess()
        assertFalse(tracker.onFailure())
        assertTrue(tracker.onFailure())
    }

    @Test
    fun `reset clears the count`() {
        val tracker = FailureTracker(maxConsecutiveFailures = 1)
        assertTrue(tracker.onFailure())
        tracker.reset()
        assertTrue(tracker.onFailure())
    }
}

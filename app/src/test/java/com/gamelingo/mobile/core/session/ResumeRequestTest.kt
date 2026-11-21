package com.gamelingo.mobile.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The resume marker the capture service sets when the system kills the
 * projection: visible until a session starts again, and the notification
 * auto-resume fires exactly once per tap.
 */
class ResumeRequestTest {

    @Before
    fun reset() {
        ResumeRequest.reset()
    }

    @Test
    fun `an interruption is visible until a new session starts`() {
        assertFalse(ResumeRequest.interrupted)

        ResumeRequest.onUnexpectedInterruption()
        assertTrue(ResumeRequest.interrupted)

        ResumeRequest.onSessionStarting()
        assertFalse(ResumeRequest.interrupted)
    }

    @Test
    fun `auto resume is consumed exactly once`() {
        ResumeRequest.onUnexpectedInterruption()
        ResumeRequest.onResumeNotificationTap()

        assertTrue(ResumeRequest.consumeAutoResume())
        assertFalse(ResumeRequest.consumeAutoResume())
    }

    @Test
    fun `a new session clears a pending auto resume too`() {
        ResumeRequest.onResumeNotificationTap()
        ResumeRequest.onSessionStarting()

        assertFalse(ResumeRequest.consumeAutoResume())
    }

    @Test
    fun `dismiss hides the banner but keeps nothing else`() {
        ResumeRequest.onUnexpectedInterruption()
        ResumeRequest.dismiss()

        assertFalse(ResumeRequest.interrupted)
    }
}

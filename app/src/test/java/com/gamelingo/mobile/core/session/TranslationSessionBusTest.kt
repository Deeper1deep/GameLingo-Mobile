package com.gamelingo.mobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TranslationSessionBusTest {

    @Before
    fun resetBus() {
        TranslationSessionBus.dismissError()
        TranslationSessionBus.onSessionStopped()
    }

    @Test
    fun `bus starts stopped with no line`() {
        assertEquals(SessionState.STOPPED, TranslationSessionBus.state.value)
        assertNull(TranslationSessionBus.latestLine.value)
    }

    @Test
    fun `starting flips the state to running`() {
        TranslationSessionBus.onSessionStarted()
        assertEquals(SessionState.RUNNING, TranslationSessionBus.state.value)
    }

    @Test
    fun `a new line is published with its timestamp`() {
        TranslationSessionBus.onNewLine("Get in the car!", 1_000L)
        assertEquals(OcrLine("Get in the car!", 1_000L), TranslationSessionBus.latestLine.value)
    }

    @Test
    fun `stopping clears the latest line`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("text", 1L)
        TranslationSessionBus.onSessionStopped()
        assertEquals(SessionState.STOPPED, TranslationSessionBus.state.value)
        assertNull(TranslationSessionBus.latestLine.value)
    }

    @Test
    fun `error state is published with its reason`() {
        TranslationSessionBus.onSessionError(SessionError.PROJECTION_REJECTED)
        assertEquals(SessionState.ERROR, TranslationSessionBus.state.value)
        assertEquals(SessionError.PROJECTION_REJECTED, TranslationSessionBus.error.value)
    }

    @Test
    fun `teardown after an error does not erase the reason`() {
        // The capture service reports the error, then its teardown calls
        // onSessionStopped: the user must still see why the session died.
        TranslationSessionBus.onSessionError(SessionError.CAPTURE_PERSISTENT_FAILURE)
        TranslationSessionBus.onSessionStopped()
        assertEquals(SessionError.CAPTURE_PERSISTENT_FAILURE, TranslationSessionBus.error.value)
    }

    @Test
    fun `starting a new session clears the previous error`() {
        TranslationSessionBus.onSessionError(SessionError.SETTINGS_UNAVAILABLE)
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        assertNull(TranslationSessionBus.error.value)
        assertEquals(SessionState.RUNNING, TranslationSessionBus.state.value)
    }

    @Test
    fun `dismissing the error settles the state back to stopped`() {
        TranslationSessionBus.onSessionError(SessionError.OVERLAY_PERMISSION_LOST)
        TranslationSessionBus.dismissError()
        assertNull(TranslationSessionBus.error.value)
        assertEquals(SessionState.STOPPED, TranslationSessionBus.state.value)
    }
}

package com.gamelingo.mobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TranslationSessionBusTest {

    @Before
    fun resetBus() {
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
    fun `error state is published`() {
        TranslationSessionBus.onSessionError()
        assertEquals(SessionState.ERROR, TranslationSessionBus.state.value)
    }
}

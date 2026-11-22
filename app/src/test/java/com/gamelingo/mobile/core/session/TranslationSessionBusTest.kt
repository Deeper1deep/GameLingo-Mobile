package com.gamelingo.mobile.core.session

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `generation bumps on every start and every stop`() {
        val before = TranslationSessionBus.currentGeneration()
        TranslationSessionBus.onSessionStarted()
        val afterStart = TranslationSessionBus.currentGeneration()
        TranslationSessionBus.onSessionStopped()
        val afterStop = TranslationSessionBus.currentGeneration()
        assertTrue(afterStart > before)
        assertTrue(afterStop > afterStart)
    }

    @Test
    fun `ocrLines sheds the oldest lines and keeps the freshest 64`() = runTest {
        val received = mutableListOf<OcrLine>()
        // UNDISPATCHED subscribes before the first emission; a lazily started
        // collector would miss everything (replay is 0 by design).
        val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            TranslationSessionBus.ocrLines.collect { received.add(it) }
        }
        // Emit faster than the collector drains: the buffer saturates and the
        // stalest lines are shed, never the fresh ones.
        repeat(100) { i -> TranslationSessionBus.onNewLine("line-$i", i.toLong()) }
        runCurrent()
        collector.cancelAndJoin()

        assertEquals(64, received.size)
        assertEquals("line-36", received.first().text)
        assertEquals("line-99", received.last().text)
    }

    @Test
    fun `a translation is published while its line is still the active one`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Get in the car now", 1_000L)
        assertTrue(TranslationSessionBus.onTranslated("Get in the car now", "اركب السيارة الآن", "e", false, 1_000L))
        assertEquals("اركب السيارة الآن", TranslationSessionBus.translatedLine.value?.translated)
    }

    @Test
    fun `a stale result for a replaced line never reaches the screen`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Get in the car now", 1_000L)
        TranslationSessionBus.onNewLine("We are out of time", 1_500L)
        assertEquals(false, TranslationSessionBus.onTranslated("Get in the car now", "قديمة", "e", false, 1_000L))
        assertNull(TranslationSessionBus.translatedLine.value)
    }

    @Test
    fun `confirmed absence clears the translation and refuses the late result`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Get in the car now", 0L)
        TranslationSessionBus.onTranslated("Get in the car now", "اركب", "e", false, 0L)
        TranslationSessionBus.onTextObserved("Get in the car now", 100L)
        TranslationSessionBus.onTextObserved("", 400L)
        TranslationSessionBus.onTextObserved("", 800L)
        assertTrue(TranslationSessionBus.onTextObserved("", 1_300L))
        assertNull(TranslationSessionBus.translatedLine.value)
        assertEquals(false, TranslationSessionBus.onTranslated("Get in the car now", "متأخرة", "e", false, 0L))
    }

    @Test
    fun `a session restart refuses results from the old session`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Get in the car now", 0L)
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        assertEquals(false, TranslationSessionBus.onTranslated("Get in the car now", "قديمة", "e", false, 0L))
    }
}

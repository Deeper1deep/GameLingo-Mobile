package com.gamelingo.mobile.core.status

import com.gamelingo.mobile.core.session.OcrLine
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslatedLine
import com.gamelingo.mobile.data.settings.AiMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusTest {

    private val line = OcrLine(text = "Hello", capturedAtMillis = 100L)
    private fun translated(at: Long) = TranslatedLine(
        original = "Hello",
        translated = "مرحبا",
        engineId = "mlkit-ondevice",
        fromCache = false,
        capturedAtMillis = at,
    )

    @Test
    fun stoppedIsReady() {
        assertEquals(SessionPhase.READY, resolveSessionPhase(SessionState.STOPPED, null, null, null))
    }

    @Test
    fun errorStateIsError() {
        assertEquals(SessionPhase.ERROR, resolveSessionPhase(SessionState.ERROR, null, null, null))
    }

    @Test
    fun runningWithoutLinesIsCapturing() {
        assertEquals(SessionPhase.CAPTURING, resolveSessionPhase(SessionState.RUNNING, null, null, null))
    }

    @Test
    fun newLineWithoutTranslationIsTranslating() {
        assertEquals(SessionPhase.TRANSLATING, resolveSessionPhase(SessionState.RUNNING, line, null, null))
    }

    @Test
    fun staleTranslationIsTranslating() {
        assertEquals(
            SessionPhase.TRANSLATING,
            resolveSessionPhase(SessionState.RUNNING, line, translated(50L), null),
        )
    }

    @Test
    fun currentTranslationIsCapturing() {
        assertEquals(
            SessionPhase.CAPTURING,
            resolveSessionPhase(SessionState.RUNNING, line, translated(100L), null),
        )
    }

    @Test
    fun failureWhileRunningIsError() {
        assertEquals(SessionPhase.ERROR, resolveSessionPhase(SessionState.RUNNING, line, null, "boom"))
    }

    @Test
    fun localModeIsLocal() {
        assertEquals(EngineReadiness.LOCAL, resolveEngineReadiness(AiMode.LOCAL, true, true))
    }

    @Test
    fun onlineNeedsKeyAndConsent() {
        assertEquals(EngineReadiness.ONLINE_READY, resolveEngineReadiness(AiMode.ONLINE, true, true))
        assertEquals(EngineReadiness.ONLINE_NOT_CONFIGURED, resolveEngineReadiness(AiMode.ONLINE, false, true))
        assertEquals(EngineReadiness.ONLINE_NOT_CONFIGURED, resolveEngineReadiness(AiMode.ONLINE, true, false))
    }

    @Test
    fun autoFallsBackToLocalWithoutCloud() {
        assertEquals(EngineReadiness.AUTO_ONLINE, resolveEngineReadiness(AiMode.AUTO, true, true))
        assertEquals(EngineReadiness.AUTO_LOCAL, resolveEngineReadiness(AiMode.AUTO, false, true))
        assertEquals(EngineReadiness.AUTO_LOCAL, resolveEngineReadiness(AiMode.AUTO, true, false))
    }

    @Test
    fun formatsElapsedTime() {
        assertEquals("0:00", formatElapsed(-5L))
        assertEquals("0:09", formatElapsed(9_999L))
        assertEquals("1:05", formatElapsed(65_000L))
        assertEquals("1:01:01", formatElapsed(3_661_000L))
    }
}

package com.gamelingo.mobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLineBusTest {
    private fun fresh() { TranslationSessionBus.onSessionStopped(); TranslationSessionBus.onSessionStarted() }

    @Test fun `audio ids are negative and distinct per utterance`() {
        fresh()
        val a = TranslationSessionBus.onAudioLine("Open the gate", 1L, 1)
        val b = TranslationSessionBus.onAudioLine("Open the gate", 2L, 2)
        assertTrue(TranslationSessionBus.isAudioId(a) && TranslationSessionBus.isAudioId(b))
        assertTrue(a != b)
        assertTrue(TranslationSessionBus.isSubtitleActive(a))
    }

    @Test fun `a translated audio line is shown without a tracked on-screen line, then cleared`() {
        fresh()
        val id = TranslationSessionBus.onAudioLine("Open the gate", 1L, 7)
        val shown = TranslationSessionBus.onTranslated(
            "Open the gate", "افتح البوابة", "e", false, 1L, subtitleId = id,
            generation = TranslationSessionBus.currentGeneration(),
        )
        assertTrue(shown)
        assertNotNull(TranslationSessionBus.translatedLine.value)
        TranslationSessionBus.clearAudioLine(id - 1) // a different utterance: must not clear this one
        assertNotNull(TranslationSessionBus.translatedLine.value)
        TranslationSessionBus.clearAudioLine(id)
        assertNull(TranslationSessionBus.translatedLine.value)
    }

    @Test fun `a stale generation audio translation is refused`() {
        fresh()
        val id = TranslationSessionBus.onAudioLine("Open the gate", 1L, 9)
        val gen = TranslationSessionBus.currentGeneration()
        TranslationSessionBus.onSessionStopped()
        assertEquals(false, TranslationSessionBus.onTranslated("Open the gate", "x", "e", false, 1L, id, gen))
    }
}

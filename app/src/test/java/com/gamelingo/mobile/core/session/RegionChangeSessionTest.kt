package com.gamelingo.mobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Changing the translation area while a session runs. */
class RegionChangeSessionTest {

    @Before
    fun reset() {
        TranslationSessionBus.dismissError()
        TranslationSessionBus.onSessionStopped()
    }

    @Test
    fun `a region change hides the current subtitle and keeps the session running`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Get in the car", 1_000L)
        val line = TranslationSessionBus.latestLine.value!!
        assertTrue(
            TranslationSessionBus.onTranslated(
                "Get in the car", "اركب", "e", false, 1_000L, line.subtitleId, line.generation,
            ),
        )
        TranslationSessionBus.onRegionChanged()
        assertNull(TranslationSessionBus.translatedLine.value)
        assertNull(TranslationSessionBus.latestLine.value)
        assertEquals(SessionState.RUNNING, TranslationSessionBus.state.value)
    }

    @Test
    fun `a translation requested before the change is refused after it`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Old area text", 1_000L)
        val old = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onRegionChanged()
        assertFalse(
            TranslationSessionBus.onTranslated(
                "Old area text", "قديم", "e", false, 1_000L, old.subtitleId, old.generation,
            ),
        )
        assertNull(TranslationSessionBus.translatedLine.value)
    }

    @Test
    fun `a stale failure from the old area never shows`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Old area text", 1_000L)
        val old = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onRegionChanged()
        TranslationSessionBus.onTranslationFailed("Old area text", old.subtitleId, old.generation)
        assertNull(TranslationSessionBus.translationFailure.value)
    }

    @Test
    fun `the new area translates normally right after the change`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Old", 1_000L)
        TranslationSessionBus.onRegionChanged()
        TranslationSessionBus.onNewLine("New area line", 2_000L)
        val line = TranslationSessionBus.latestLine.value!!
        assertTrue(
            TranslationSessionBus.onTranslated(
                "New area line", "سطر جديد", "e", false, 2_000L, line.subtitleId, line.generation,
            ),
        )
        assertEquals("سطر جديد", TranslationSessionBus.translatedLine.value?.translated)
    }

    @Test
    fun `the same sentence in the new area is a new appearance`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Run!", 1_000L)
        val first = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onRegionChanged()
        TranslationSessionBus.onNewLine("Run!", 1_500L)
        val second = TranslationSessionBus.latestLine.value!!
        assertTrue(second.subtitleId > first.subtitleId)
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun `a subtitle that disappears from the region is cleared`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Where were you?", 0L)
        TranslationSessionBus.onTranslated("Where were you?", "فين كنت؟", "e", false, 0L)
        TranslationSessionBus.onTextObserved("Where were you?", 100L)
        TranslationSessionBus.onTextObserved("", 400L)
        TranslationSessionBus.onTextObserved("", 800L)
        assertTrue(TranslationSessionBus.onTextObserved("", 1_300L))
        assertNull(TranslationSessionBus.translatedLine.value)
    }
}

package com.gamelingo.mobile.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Rotation or any capture-geometry change while a translation is still in flight. */
class GeometryChangeSessionTest {

    @Before
    fun reset() {
        TranslationSessionBus.dismissError()
        TranslationSessionBus.onSessionStopped()
    }

    @Test
    fun `a translation that completes after a rotation is dropped and shows nothing`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Portrait line", 1_000L)
        val old = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onGeometryChanged() // rotation lands while the request is in flight
        val shown = TranslationSessionBus.onTranslated(
            "Portrait line", "سطر", "e", false, 1_000L, old.subtitleId, old.generation,
        )
        assertFalse(shown)
        assertNull(TranslationSessionBus.translatedLine.value)
    }

    @Test
    fun `a cached result for the old geometry is dropped too`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Cached", 1_000L)
        val old = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onGeometryChanged()
        assertFalse(
            TranslationSessionBus.onTranslated("Cached", "مخزن", "e", true, 1_000L, old.subtitleId, old.generation),
        )
    }

    @Test
    fun `a visible translation and its source box are cleared on geometry change`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine(
            "Visible", 1_000L,
            sourceBox = com.gamelingo.mobile.core.overlay.NormalizedBox(0.1f, 0.7f, 0.9f, 0.8f),
        )
        val line = TranslationSessionBus.latestLine.value!!
        assertTrue(TranslationSessionBus.onTranslated("Visible", "ظاهر", "e", false, 1_000L, line.subtitleId, line.generation))
        TranslationSessionBus.onGeometryChanged()
        assertNull(TranslationSessionBus.translatedLine.value)
        assertNull(TranslationSessionBus.latestLine.value)
        assertEquals(SessionState.RUNNING, TranslationSessionBus.state.value)
    }

    @Test
    fun `a stale failure from before the rotation never shows`() {
        TranslationSessionBus.onSessionStarted()
        TranslationSessionBus.onNewLine("Fails", 1_000L)
        val old = TranslationSessionBus.latestLine.value!!
        TranslationSessionBus.onGeometryChanged()
        TranslationSessionBus.onTranslationFailed("Fails", old.subtitleId, old.generation)
        assertNull(TranslationSessionBus.translationFailure.value)
    }

    @Test
    fun `repeated rotations each invalidate everything before them and the next line works`() {
        TranslationSessionBus.onSessionStarted()
        val olds = (0 until 4).map { i ->
            TranslationSessionBus.onNewLine("L$i", 1_000L + i)
            TranslationSessionBus.latestLine.value!!.also { TranslationSessionBus.onGeometryChanged() }
        }
        olds.forEach {
            assertFalse(TranslationSessionBus.onTranslated(it.text, "x", "e", false, it.capturedAtMillis, it.subtitleId, it.generation))
        }
        TranslationSessionBus.onNewLine("After", 9_000L)
        val fresh = TranslationSessionBus.latestLine.value!!
        assertTrue(TranslationSessionBus.onTranslated("After", "بعد", "e", false, 9_000L, fresh.subtitleId, fresh.generation))
    }
}

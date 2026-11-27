package com.gamelingo.mobile.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossarySuggesterTest {
    private val lines = listOf(
        "We have to find Joel before dark.",
        "Did you see Joel near the gate?",
        "Tell Ellie to run.",
        "Monday is when we leave.",
        "Thursday was bad. Then Monday came.",
    )

    @Test fun `repeated mid-sentence capitalised names are suggested`() {
        assertEquals(listOf("Joel"), GlossarySuggester.suggest(lines, emptyList()))
    }

    @Test fun `a single occurrence is not enough`() {
        assertTrue("Ellie" !in GlossarySuggester.suggest(lines, emptyList()))
    }

    @Test fun `sentence-initial words and weekdays are ignored`() {
        val s = GlossarySuggester.suggest(listOf("Hello there.", "Hello again.", "Go Monday", "See Monday"), emptyList())
        assertTrue(s.isEmpty())
    }

    @Test fun `terms already in the glossary or dismissed are not suggested again`() {
        assertTrue(GlossarySuggester.suggest(lines, listOf("joel")).isEmpty())
        assertTrue(GlossarySuggester.suggest(lines, emptyList(), dismissed = listOf("Joel")).isEmpty())
    }

    @Test fun `multi-word names stay together`() {
        val s = GlossarySuggester.suggest(listOf("Take it to Tommy Miller.", "Ask Tommy Miller."), emptyList())
        assertEquals(listOf("Tommy Miller"), s)
    }

    @Test fun `pool is bounded, dismissable and clearable`() {
        SuggestionPool.clear()
        lines.forEach(SuggestionPool::record)
        assertEquals(listOf("Joel"), SuggestionPool.suggestions(emptyList()))
        SuggestionPool.dismiss("Joel")
        assertTrue(SuggestionPool.suggestions(emptyList()).isEmpty())
        SuggestionPool.clear()
        repeat(500) { SuggestionPool.record("See Zed again $it") }
        SuggestionPool.clear()
        assertTrue(SuggestionPool.suggestions(emptyList()).isEmpty())
    }
}

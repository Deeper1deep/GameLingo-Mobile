package com.gamelingo.mobile.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun `identical strings score one`() {
        assertEquals(1f, TextSimilarity.similarity("hello there", "hello there"), 0.0001f)
    }

    @Test
    fun `single character flicker on a subtitle line stays above the stability threshold`() {
        // Mirrors real OCR flicker on game subtitles: one letter differs.
        val score = TextSimilarity.similarity(
            "joel its nice but i",
            "joel its nlce but i",
        )
        assertTrue("score was $score", score >= TextStabilityTracker.DEFAULT_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `genuinely different lines stay below the stability threshold`() {
        val score = TextSimilarity.similarity(
            "joel its nice but i",
            "ellie we need to go now",
        )
        assertTrue("score was $score", score < TextStabilityTracker.DEFAULT_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `empty strings score zero against non empty`() {
        assertEquals(0f, TextSimilarity.similarity("", "hello"), 0.0001f)
        assertEquals(0f, TextSimilarity.similarity("hello", ""), 0.0001f)
    }
}

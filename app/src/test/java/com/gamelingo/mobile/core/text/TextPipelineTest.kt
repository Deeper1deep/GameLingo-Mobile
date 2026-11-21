package com.gamelingo.mobile.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `normalize collapses whitespace and trims`() {
        assertEquals("Go to the marker", TextNormalizer.normalize("  Go   to\n\tthe  marker  "))
    }

    @Test
    fun `normalize keeps case`() {
        assertEquals("Los Santos", TextNormalizer.normalize("Los Santos"))
    }

    @Test
    fun `comparison key ignores case and punctuation`() {
        assertEquals(
            TextNormalizer.comparisonKey("Wait, what?!"),
            TextNormalizer.comparisonKey("wait what"),
        )
    }

    @Test
    fun `comparison key strips OCR punctuation flicker`() {
        assertEquals(
            TextNormalizer.comparisonKey("Let's go!"),
            TextNormalizer.comparisonKey("Lets go."),
        )
    }

    @Test
    fun `comparison key keeps arabic letters`() {
        val key = TextNormalizer.comparisonKey("يلا بينا!")
        assertTrue(key.contains("يلا"))
        assertTrue(key.contains("بينا"))
    }

    @Test
    fun `comparison key of punctuation-only input is empty`() {
        assertEquals("", TextNormalizer.comparisonKey("?!..."))
    }
}

class TextStabilityTrackerTest {

    @Test
    fun `first reading is not yet stable`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        assertFalse(tracker.offer("hello"))
    }

    @Test
    fun `second identical reading is stable`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        tracker.offer("hello")
        assertTrue(tracker.offer("hello"))
    }

    @Test
    fun `changing text resets the streak`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        tracker.offer("hello")
        assertFalse(tracker.offer("goodbye"))
        assertTrue(tracker.offer("goodbye"))
    }

    @Test
    fun `punctuation flicker does not reset the streak`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        tracker.offer("Wait, what?!")
        assertTrue(tracker.offer("wait what"))
    }

    @Test
    fun `single letter OCR flicker does not reset the streak`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        tracker.offer("Joel: It is nice but I-")
        assertTrue(tracker.offer("Joel: It is nlce but I-"))
    }

    @Test
    fun `alternating flicker still stabilizes instead of never delivering`() {
        val tracker = TextStabilityTracker(requiredReadings = 2)
        assertFalse(tracker.offer("It is nice"))
        assertTrue(tracker.offer("It is nlce"))
        assertTrue(tracker.offer("It is nice"))
    }

    @Test
    fun `a full subtitle line is accepted on its first reading`() {
        // Long lines are almost never OCR noise; waiting a whole extra
        // capture interval was the biggest start-of-translation delay.
        val tracker = TextStabilityTracker()
        assertTrue(tracker.offer("Joel: We need to get out of here now"))
    }

    @Test
    fun `a short fragment still needs two sightings`() {
        val tracker = TextStabilityTracker()
        assertFalse(tracker.offer("Run!"))
        assertTrue(tracker.offer("Run!"))
    }

    @Test
    fun `empty input resets the tracker`() {
        val tracker = TextStabilityTracker(requiredReadings = 1)
        assertFalse(tracker.offer("   "))
        assertTrue(tracker.offer("hello"))
    }
}

class DeduplicatorTest {

    @Test
    fun `first text is delivered`() {
        assertTrue(Deduplicator().shouldDeliver("hello"))
    }

    @Test
    fun `same text twice is delivered once`() {
        val dedup = Deduplicator()
        assertTrue(dedup.shouldDeliver("hello"))
        assertFalse(dedup.shouldDeliver("hello"))
    }

    @Test
    fun `different text is delivered`() {
        val dedup = Deduplicator()
        dedup.shouldDeliver("hello")
        assertTrue(dedup.shouldDeliver("goodbye"))
    }

    @Test
    fun `punctuation-only change is still a duplicate`() {
        val dedup = Deduplicator()
        dedup.shouldDeliver("Wait, what?!")
        assertFalse(dedup.shouldDeliver("wait what"))
    }

    @Test
    fun `single letter OCR flicker is still a duplicate`() {
        val dedup = Deduplicator()
        dedup.shouldDeliver("Joel: It is nice but I-")
        assertFalse(dedup.shouldDeliver("Joel: It is nlce but I-"))
    }

    @Test
    fun `flicker back to the original reading is still a duplicate`() {
        val dedup = Deduplicator()
        dedup.shouldDeliver("Joel: It is nice but I-")
        dedup.shouldDeliver("Joel: It is nlce but I-")
        assertFalse(dedup.shouldDeliver("Joel: It is nice but I-"))
    }

    @Test
    fun `blank text is never delivered`() {
        assertFalse(Deduplicator().shouldDeliver("   "))
    }

    @Test
    fun `reset allows redelivery`() {
        val dedup = Deduplicator()
        dedup.shouldDeliver("hello")
        dedup.reset()
        assertTrue(dedup.shouldDeliver("hello"))
    }

    @Test
    fun `visible line keeps sliding the duplicate window`() {
        var now = 0L
        val dedup = Deduplicator(expiryMs = 1_000L, clock = { now })
        assertTrue(dedup.shouldDeliver("Capture the flag"))
        now = 800L
        assertFalse(dedup.shouldDeliver("Capture the flag"))
        now = 1_500L // 700 ms after the last sighting
        assertFalse(dedup.shouldDeliver("Capture the flag"))
    }

    @Test
    fun `touch keeps the last line recent`() {
        var now = 0L
        val dedup = Deduplicator(expiryMs = 1_000L, clock = { now })
        assertTrue(dedup.shouldDeliver("Capture the flag"))
        now = 900L
        dedup.touch()
        now = 1_500L
        assertFalse(dedup.shouldDeliver("Capture the flag"))
    }
}

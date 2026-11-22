package com.gamelingo.mobile.core.text

import com.gamelingo.mobile.core.text.TextPresenceTracker.Decision
import com.gamelingo.mobile.core.text.TextPresenceTracker.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brief's mandatory disappearance cases, on a fake clock. */
class TextPresenceTrackerTest {

    private val a = "Get in the car right now"
    private val b = "We are running out of time"

    private fun shown(tracker: TextPresenceTracker, text: String = a, at: Long = 0L) {
        tracker.onLinePublished(text, at)
        assertTrue(tracker.acceptTranslation(text))
    }

    @Test
    fun `1 line appears then disappears - cleared only after misses and grace`() {
        val t = TextPresenceTracker()
        shown(t)
        assertEquals(Decision.None, t.observe(a, 200))
        assertEquals(Decision.None, t.observe("", 400))
        assertEquals(State.DISAPPEARING, t.state)
        assertEquals(Decision.None, t.observe("", 600)) // 2 misses but only 400 ms
        assertCleared(a, t.observe("", 1_300))
        assertEquals(State.CLEARED, t.state)
    }

    @Test
    fun `2 a new line quickly replaces the old one and the old result is refused`() {
        val t = TextPresenceTracker()
        t.onLinePublished(a, 0)
        t.onLinePublished(b, 300)
        assertFalse("stale result for the replaced line", t.acceptTranslation(a))
        assertTrue(t.acceptTranslation(b))
    }

    @Test
    fun `3 disappears with no replacement on a busy screen`() {
        val t = TextPresenceTracker()
        shown(t)
        var cleared: Decision = Decision.None
        var now = 100L
        while (now <= 2_000 && cleared == Decision.None) {
            cleared = t.observe("", now)
            now += 100
        }
        assertCleared(a, cleared)
    }

    @Test
    fun `4 scene change - other text on screen does not keep the old translation`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe(a, 100)
        t.observe("Chapter two begins", 400)
        t.observe("Chapter two begins", 800)
        assertCleared(a, t.observe("Chapter two begins", 1_200))
    }

    @Test
    fun `5 one missed OCR frame never hides a live subtitle`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe(a, 100)
        assertEquals(Decision.None, t.observe("", 200))
        assertEquals(State.DISAPPEARING, t.state)
        assertEquals(Decision.None, t.observe(a, 300))
        assertEquals(State.VISIBLE, t.state)
        // and the miss counter really restarted
        assertEquals(Decision.None, t.observe("", 1_500))
        assertEquals(Decision.None, t.observe(a, 1_600))
    }

    @Test
    fun `6 one of several lines vanishes - only its translation is cleared`() {
        val t = TextPresenceTracker()
        val first = "First speaker says hello"
        val second = "Second speaker answers back"
        shown(t, first)
        // Both on screen: the tracked line is contained in the read text.
        assertEquals(Decision.None, t.observe("$first $second", 100))
        // Now only the second remains.
        t.observe(second, 400)
        t.observe(second, 800)
        assertCleared(first, t.observe(second, 1_300))
    }

    @Test
    fun `7 text reappearing after clearing is tracked again`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe("", 300)
        t.observe("", 700)
        assertCleared(a, t.observe("", 1_300))
        t.onLinePublished(a, 2_000)
        assertTrue(t.acceptTranslation(a))
        assertEquals(State.VISIBLE, t.state)
    }

    @Test
    fun `8 text disappears while its translation is still running`() {
        val t = TextPresenceTracker()
        t.onLinePublished(a, 0) // TRANSLATING, nothing shown yet
        assertEquals(State.TRANSLATING, t.state)
        t.observe("", 300)
        t.observe("", 700)
        assertCleared(a, t.observe("", 1_200))
        assertFalse("the late result must not appear", t.acceptTranslation(a))
    }

    @Test
    fun `9 an old request ending after a new one started is refused`() {
        val t = TextPresenceTracker()
        t.onLinePublished(a, 0)
        t.onLinePublished(b, 500)
        assertFalse(t.acceptTranslation(a))
        assertEquals(State.TRANSLATING, t.state)
    }

    @Test
    fun `10 stopping capture while a translation is shown clears all state`() {
        val t = TextPresenceTracker()
        shown(t)
        t.reset()
        assertEquals(State.CLEARED, t.state)
        assertFalse(t.acceptTranslation(a))
        assertEquals(Decision.None, t.tick(10_000))
    }

    @Test
    fun `11 overlay reopened or service restarted - a reset tracker holds nothing stale`() {
        val t = TextPresenceTracker()
        shown(t)
        t.reset()
        t.onLinePublished(b, 5_000)
        assertFalse(t.acceptTranslation(a))
        assertTrue(t.acceptTranslation(b))
    }

    @Test
    fun `12 static screen - the heartbeat confirms an absence no frame will report`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe(a, 0)
        t.observe("", 300) // the last frame the display ever produced
        assertEquals(Decision.None, t.tick(500)) // quiet period not over
        assertEquals(Decision.None, t.tick(800)) // grace not over
        assertCleared(a, t.tick(1_100))
    }

    @Test
    fun `heartbeat never clears a subtitle that is still being seen`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe(a, 0)
        assertEquals(Decision.None, t.tick(5_000))
        assertEquals(State.VISIBLE, t.state)
    }

    @Test
    fun `grace adapts to a slow OCR cycle`() {
        val t = TextPresenceTracker()
        shown(t)
        t.observe(a, 0)
        t.observe("", 800)
        assertEquals(1_600L, t.effectiveGraceMs())
        assertCleared(a, t.observe("", 1_600))
    }

    @Test
    fun `grace stays at the base on a fast OCR cycle and is capped`() {
        val fast = TextPresenceTracker()
        shown(fast)
        fast.observe(a, 0)
        fast.observe(a, 100)
        assertEquals(1_000L, fast.effectiveGraceMs())

        val slow = TextPresenceTracker()
        shown(slow)
        slow.observe(a, 0)
        slow.observe(a, 4_000)
        assertEquals(TextPresenceTracker.MAX_GRACE_MS, slow.effectiveGraceMs())
    }

    @Test
    fun `unchanged frames keep seen text seen and absent text absent`() {
        val seen = TextPresenceTracker()
        shown(seen)
        seen.observe(a, 0)
        assertEquals(Decision.None, seen.onUnchangedFrame(3_000))
        assertEquals(State.VISIBLE, seen.state)

        val gone = TextPresenceTracker()
        shown(gone)
        gone.observe(a, 0)
        gone.observe("", 300)
        assertEquals(Decision.None, gone.onUnchangedFrame(600))
        assertCleared(a, gone.onUnchangedFrame(1_200))
    }

    @Test
    fun `OCR flicker of the same line still counts as seen`() {
        val t = TextPresenceTracker()
        shown(t)
        assertEquals(Decision.None, t.observe("Get in the car rlght now", 200))
        assertEquals(State.VISIBLE, t.state)
        assertTrue(t.acceptTranslation("Get in the car rlght now"))
    }

    private fun assertCleared(original: String, decision: Decision) {
        assertTrue("expected Clear($original) but was $decision",
            decision is Decision.Clear && decision.original == original)
    }
}

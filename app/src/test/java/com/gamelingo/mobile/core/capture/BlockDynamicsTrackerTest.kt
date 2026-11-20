package com.gamelingo.mobile.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockDynamicsTrackerTest {

    private fun block(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        RecognizedBlock(text, PixelRect(left, top, right, bottom))

    @Test
    fun `no history scores neutral`() {
        val tracker = BlockDynamicsTracker()
        val score = tracker.dynamicScore(block("GAMEPLAY ONLY", 1740, 980, 1900, 1030))
        assertEquals(BlockDynamicsTracker.NEUTRAL, score, 0.0001f)
    }

    @Test
    fun `identical text at the same spot decays to static`() {
        val tracker = BlockDynamicsTracker()
        val watermark = block("GAMEPLAY ONLY", 1740, 980, 1900, 1030)
        var last = -1f
        repeat(BlockDynamicsTracker.STATIC_FRAMES + 2) {
            last = tracker.dynamicScore(watermark)
            tracker.offer(listOf(watermark))
        }
        assertEquals(BlockDynamicsTracker.STATIC, last, 0.0001f)
    }

    @Test
    fun `changing text at the same spot scores dynamic`() {
        val tracker = BlockDynamicsTracker()
        val first = block("Joel: It is nice", 660, 990, 1260, 1040)
        tracker.offer(listOf(first))
        val second = block("but I think we should go", 640, 990, 1280, 1040)
        assertEquals(BlockDynamicsTracker.DYNAMIC, tracker.dynamicScore(second), 0.0001f)
    }

    @Test
    fun `text at a new spot scores neutral`() {
        val tracker = BlockDynamicsTracker()
        tracker.offer(listOf(block("GAMEPLAY ONLY", 1740, 980, 1900, 1030)))
        val elsewhere = block("Hello there friend", 660, 990, 1260, 1040)
        assertEquals(BlockDynamicsTracker.NEUTRAL, tracker.dynamicScore(elsewhere), 0.0001f)
    }

    @Test
    fun `reset clears the history`() {
        val tracker = BlockDynamicsTracker()
        val watermark = block("GAMEPLAY ONLY", 1740, 980, 1900, 1030)
        repeat(BlockDynamicsTracker.STATIC_FRAMES + 1) {
            tracker.dynamicScore(watermark)
            tracker.offer(listOf(watermark))
        }
        tracker.reset()
        assertEquals(BlockDynamicsTracker.NEUTRAL, tracker.dynamicScore(watermark), 0.0001f)
    }
}

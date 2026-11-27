package com.gamelingo.mobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceSegmenterTest {
    private fun frame(seg: UtteranceSegmenter, amp: Int) = ShortArray(seg.frameSamples) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun run(seg: UtteranceSegmenter, pattern: List<Int>): List<UtteranceSegmenter.Event> {
        val events = mutableListOf<UtteranceSegmenter.Event>()
        var t = 0L
        for (amp in pattern) { events += seg.accept(frame(seg, amp), t); t += 30 }
        return events
    }

    @Test fun `silence produces nothing`() {
        assertTrue(run(UtteranceSegmenter(), List(100) { 0 }).isEmpty())
    }

    @Test fun `speech then silence gives one finished utterance with a stable id`() {
        val ev = run(UtteranceSegmenter(), List(20) { 5000 } + List(30) { 0 })
        val finished = ev.filterIsInstance<UtteranceSegmenter.Event.Finished>()
        assertEquals(1, finished.size)
        assertEquals(1L, finished[0].utteranceId)
        assertEquals(1L, ev.filterIsInstance<UtteranceSegmenter.Event.Started>().single().utteranceId)
        assertTrue(finished[0].pcm.size >= 20 * 480)
    }

    @Test fun `a click shorter than the minimum is dropped`() {
        val ev = run(UtteranceSegmenter(), listOf(5000, 5000) + List(30) { 0 })
        assertTrue(ev.filterIsInstance<UtteranceSegmenter.Event.Finished>().isEmpty())
    }

    @Test fun `two utterances get increasing ids`() {
        val ev = run(UtteranceSegmenter(), List(15) { 5000 } + List(30) { 0 } + List(15) { 5000 } + List(30) { 0 })
        assertEquals(listOf(1L, 2L), ev.filterIsInstance<UtteranceSegmenter.Event.Finished>().map { it.utteranceId })
    }

    @Test fun `long speech is cut at the maximum and emits partials`() {
        val ev = run(UtteranceSegmenter(maxUtteranceMillis = 3_000), List(150) { 5000 })
        assertTrue(ev.any { it is UtteranceSegmenter.Event.Partial })
        assertTrue(ev.filterIsInstance<UtteranceSegmenter.Event.Finished>().isNotEmpty())
    }

    @Test fun `dedupe matches the same dialogue across sources but not repeats from one source`() {
        val d = OcrAsrDeduper()
        assertFalse(d.isDuplicate("We need to go now", false, 0))
        assertTrue(d.isDuplicate("we need to go now!", true, 1_500))
        assertFalse(d.isDuplicate("Open the gate", true, 2_000))
        assertFalse(d.isDuplicate("Open the gate", true, 3_000))
        assertFalse(OcrAsrDeduper().isDuplicate("Go", true, 0))
    }

    @Test fun `dedupe expires after the window`() {
        val d = OcrAsrDeduper(windowMillis = 5_000)
        d.isDuplicate("We need to go now", false, 0)
        assertFalse(d.isDuplicate("We need to go now", true, 9_000))
    }
}

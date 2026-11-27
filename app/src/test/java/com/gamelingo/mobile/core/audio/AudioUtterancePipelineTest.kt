package com.gamelingo.mobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUtterancePipelineTest {
    private class FakeAsr(private val finalText: List<String>) : AsrEngine {
        var frames = 0; var resets = 0; var finishes = 0
        override fun acceptFrame(pcm: ShortArray): AsrUpdate? { frames++; return AsrUpdate("part", false) }
        override fun finish(): String = finalText.getOrElse(finishes++) { "" }
        override fun reset() { resets++ }
        override fun close() = Unit
    }

    private fun frames(seg: UtteranceSegmenter, pattern: List<Int>): List<ShortArray> =
        pattern.map { amp -> ShortArray(seg.frameSamples) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() } }

    private fun feed(p: AudioUtterancePipeline, seg: UtteranceSegmenter, pattern: List<Int>, startAt: Long = 0) {
        var t = startAt
        for (f in frames(seg, pattern)) { p.acceptFrame(f, t); t += 30 }
    }

    @Test fun `speech yields one final utterance with the recogniser text and partials on the way`() {
        val seg = UtteranceSegmenter(); val asr = FakeAsr(listOf("we need to go now"))
        val out = mutableListOf<AudioUtterancePipeline.Utterance>(); val partials = mutableListOf<String>()
        val p = AudioUtterancePipeline(seg, asr, onPartial = { _, t -> partials += t }, onUtterance = { out += it })
        feed(p, seg, List(20) { 5000 } + List(30) { 0 })
        assertEquals(1, out.size)
        assertEquals("we need to go now", out[0].text)
        assertEquals(1L, out[0].utteranceId)
        assertTrue(partials.isNotEmpty())
        assertEquals(1, asr.finishes)
    }

    @Test fun `a short burst is dropped and the recogniser is reset`() {
        val seg = UtteranceSegmenter(); val asr = FakeAsr(listOf("noise"))
        val out = mutableListOf<AudioUtterancePipeline.Utterance>()
        val p = AudioUtterancePipeline(seg, asr, onUtterance = { out += it })
        feed(p, seg, listOf(5000, 5000) + List(30) { 0 })
        assertTrue(out.isEmpty()); assertEquals(1, asr.resets); assertEquals(0, asr.finishes)
    }

    @Test fun `speech matching a line just read from the screen is not delivered`() {
        val seg = UtteranceSegmenter(); val asr = FakeAsr(listOf("We need to go now"))
        val out = mutableListOf<AudioUtterancePipeline.Utterance>()
        val p = AudioUtterancePipeline(seg, asr, onUtterance = { out += it })
        p.noteOcrLine("We need to go now.", 100)
        feed(p, seg, List(20) { 5000 } + List(30) { 0 }, startAt = 200)
        assertTrue(out.isEmpty())
    }

    @Test fun `empty recognition delivers nothing`() {
        val seg = UtteranceSegmenter(); val asr = FakeAsr(listOf(""))
        val out = mutableListOf<AudioUtterancePipeline.Utterance>()
        feed(AudioUtterancePipeline(seg, asr, onUtterance = { out += it }), seg, List(20) { 5000 } + List(30) { 0 })
        assertTrue(out.isEmpty())
    }

    @Test fun `asr json parsing`() {
        assertEquals("hello world", AsrJson.textOf("""{ "text" : "hello world" }"""))
        assertEquals("go now", AsrJson.textOf("""{"partial":"go now"}"""))
        assertEquals("it's \"x\"", AsrJson.textOf("""{"text":"it's \"x\""}"""))
        assertEquals("", AsrJson.textOf(null))
        assertEquals("", AsrJson.textOf("{}"))
    }
}

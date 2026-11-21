package com.gamelingo.mobile.translate.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the shared greedy decode loop with a scripted fake runner. */
class GreedyDecoderTest {

    /** Answers decode steps from a fixed script of next-token ids, then EOS. */
    private class ScriptedRunner(
        private val script: IntArray,
        private val eosId: Int,
        private val vocabSize: Int = 64,
    ) : OnnxRunner {
        var encodeCalls = 0
            private set
        var decodeCalls = 0
            private set
        var lastPrefixLength = 0
            private set

        private val state = object : EncoderState {}

        override fun encode(inputIds: IntArray): EncoderState {
            encodeCalls++
            return state
        }

        override fun decodeStep(encoderState: EncoderState, decoderIds: IntArray): FloatArray {
            decodeCalls++
            lastPrefixLength = decoderIds.size
            val next = if (decodeCalls <= script.size) script[decodeCalls - 1] else eosId
            return FloatArray(vocabSize) { if (it == next) 1f else -1f }
        }

        override fun close() = Unit
    }

    @Test
    fun `generate returns the scripted tokens without start or eos`() {
        val runner = ScriptedRunner(intArrayOf(7, 8, 9), eosId = 0)
        val decoder = GreedyDecoder(runner, decoderStartId = 3, eosId = 0)
        val out = decoder.generate(intArrayOf(1, 2))
        assertArrayEquals(intArrayOf(7, 8, 9), out)
        assertEquals(1, runner.encodeCalls)
        assertEquals(4, runner.decodeCalls) // three tokens + the step that saw EOS
    }

    @Test
    fun `immediate eos yields an empty translation`() {
        val runner = ScriptedRunner(intArrayOf(0), eosId = 0)
        val decoder = GreedyDecoder(runner, decoderStartId = 3, eosId = 0)
        assertArrayEquals(intArrayOf(), decoder.generate(intArrayOf(5)))
    }

    @Test
    fun `length budget caps runaway decodes`() {
        val runner = ScriptedRunner(IntArray(500) { 11 }, eosId = 0)
        val decoder = GreedyDecoder(runner, decoderStartId = 3, eosId = 0, maxOutputTokens = 10)
        val out = decoder.generate(intArrayOf(1))
        assertEquals(10, out.size)
        assertEquals(10, runner.decodeCalls)
    }

    @Test
    fun `argmax picks the highest logit not the first index`() {
        val runner = object : OnnxRunner {
            override fun encode(inputIds: IntArray): EncoderState = object : EncoderState {}
            override fun decodeStep(encoderState: EncoderState, decoderIds: IntArray): FloatArray {
                if (decoderIds.size > 1) return FloatArray(8) { if (it == 0) 1f else -1f }
                return FloatArray(8) { i -> if (i == 5) 2f else 0f } // token 5 wins without being first
            }
            override fun close() = Unit
        }
        val decoder = GreedyDecoder(runner, decoderStartId = 3, eosId = 0)
        assertArrayEquals(intArrayOf(5), decoder.generate(intArrayOf(1)))
    }

    @Test
    fun `cancellation aborts the loop instead of hanging`() {
        var ticks = 0
        val runner = ScriptedRunner(IntArray(500) { 11 }, eosId = 0)
        val decoder = GreedyDecoder(
            runner,
            decoderStartId = 3,
            eosId = 0,
            isCancelled = { ++ticks > 5 },
        )
        val thrown = runCatching { decoder.generate(intArrayOf(1)) }.exceptionOrNull()
        assertTrue(thrown is GreedyDecoder.DecodeCancelledException)
        assertTrue("decode must stop soon after cancellation", runner.decodeCalls <= 8)
    }
}

package com.gamelingo.mobile.translate.local

import com.gamelingo.mobile.translate.MlKitTranslationEngine
import com.gamelingo.mobile.translate.TranslationEngine
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.TranslationResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies chain routing: best available engine wins, failures degrade visibly. */
class LocalEngineChainTest {

    private class FakeCandidate(
        override val engineId: String,
        var available: Boolean,
        var answer: String = "ترجمة",
        var failure: Exception? = null,
    ) : LocalAiCandidate {
        var calls = 0
            private set
        var closed = false
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun ensureReady() = Unit

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            failure?.let { throw it }
            return TranslationResult(translatedText = answer, engineId = engineId)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeMlKit(
        var answer: String = "ترجمة ملكيت",
        var failure: Exception? = null,
    ) : TranslationEngine {
        override val engineId: String = MlKitTranslationEngine.ENGINE_ID
        var calls = 0
            private set

        override suspend fun ensureReady() = Unit

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            failure?.let { throw it }
            return TranslationResult(translatedText = answer, engineId = engineId)
        }

        override fun close() = Unit
    }

    private fun chain(
        gemma: FakeCandidate = FakeCandidate(TranslateGemmaEngine.ENGINE_ID, available = false),
        marian: FakeCandidate = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = false),
        mlKit: FakeMlKit = FakeMlKit(),
    ) = Triple(LocalEngineChain(gemma, marian, mlKit), gemma, marian) to mlKit

    @Test
    fun `no downloadable model means mlkit and hasLocalAiModel false`() {
        val (triple, mlKit) = chain()
        val (chain, _, _) = triple
        assertFalse(chain.hasLocalAiModel())
        assertEquals(MlKitTranslationEngine.ENGINE_ID, chain.plannedLocalEngineId())
    }

    @Test
    fun `marian installed picks marian over mlkit`() {
        val (triple, _) = chain(marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = true))
        val (chain, _, _) = triple
        assertTrue(chain.hasLocalAiModel())
        assertEquals(LocalAiTranslationEngine.ENGINE_ID, chain.plannedLocalEngineId())
    }

    @Test
    fun `gemma installed wins over marian`() = runTest {
        val gemma = FakeCandidate(TranslateGemmaEngine.ENGINE_ID, available = true, answer = "مصري")
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = true)
        val (triple, mlKit) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        assertEquals(TranslateGemmaEngine.ENGINE_ID, chain.plannedLocalEngineId())
        val result = chain.translate(TranslationRequest(text = "hi"))
        assertEquals("مصري", result.translatedText)
        assertEquals(TranslateGemmaEngine.ENGINE_ID, result.engineId)
        assertEquals(1, gemma.calls)
        assertEquals(0, marian.calls)
        assertEquals(0, mlKit.calls)
    }

    @Test
    fun `a failing gemma degrades to marian for that line only`() = runTest {
        val gemma = FakeCandidate(
            TranslateGemmaEngine.ENGINE_ID,
            available = true,
            failure = IOException("native crash"),
        )
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = true, answer = "فصحى محسنة")
        val (triple, _) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        val result = chain.translate(TranslationRequest(text = "hi"))
        assertEquals(LocalAiTranslationEngine.ENGINE_ID, result.engineId)
        assertEquals("فصحى محسنة", result.translatedText)
    }

    @Test
    fun `all local ai failing still answers via mlkit`() = runTest {
        val gemma = FakeCandidate(TranslateGemmaEngine.ENGINE_ID, available = true, failure = IOException("x"))
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = true, failure = IOException("y"))
        val (triple, mlKit) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        val result = chain.translate(TranslationRequest(text = "hi"))
        assertEquals(MlKitTranslationEngine.ENGINE_ID, result.engineId)
        assertEquals(1, mlKit.calls)
    }

    @Test
    fun `cancellation from a candidate is never swallowed`() = runTest {
        val gemma = FakeCandidate(
            TranslateGemmaEngine.ENGINE_ID,
            available = true,
            failure = CancellationException("stale"),
        )
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = true)
        val (triple, mlKit) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        val thrown = runCatching { chain.translate(TranslationRequest(text = "hi")) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(0, marian.calls)
        assertEquals(0, mlKit.calls)
    }

    @Test
    fun `missing models are skipped not errors`() = runTest {
        val gemma = FakeCandidate(TranslateGemmaEngine.ENGINE_ID, available = false)
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = false)
        val (triple, mlKit) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        val result = chain.translate(TranslationRequest(text = "hi"))
        assertEquals(MlKitTranslationEngine.ENGINE_ID, result.engineId)
        assertEquals(0, gemma.calls)
        assertEquals(0, marian.calls)
        assertEquals(1, mlKit.calls)
    }

    @Test
    fun `close releases the ai engines but not shared mlkit`() {
        val gemma = FakeCandidate(TranslateGemmaEngine.ENGINE_ID, available = false)
        val marian = FakeCandidate(LocalAiTranslationEngine.ENGINE_ID, available = false)
        val (triple, _) = chain(gemma = gemma, marian = marian)
        val (chain, _, _) = triple
        chain.close()
        assertTrue(gemma.closed)
        assertTrue(marian.closed)
    }
}

package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTranslationEngineTest {

    /** Records calls and answers or fails on demand. */
    private class StubEngine(
        override val engineId: String,
        var answer: String = "ترجمة",
        var failure: Exception? = null,
    ) : TranslationEngine {
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

    private fun hybrid(
        mode: AiMode,
        apiKey: String = "key",
        online: StubEngine = StubEngine("gemini-flash-lite"),
        local: StubEngine = StubEngine("mlkit-ondevice"),
    ): Triple<HybridTranslationEngine, StubEngine, StubEngine> {
        val settings = FakeSettingsRepository(
            AppSettings(aiMode = mode, onlineApiKey = apiKey),
        )
        return Triple(HybridTranslationEngine(online, local, settings), online, local)
    }

    @Test
    fun `LOCAL mode always uses the on-device engine`() = runTest {
        val (engine, online, local) = hybrid(AiMode.LOCAL)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
        assertEquals(1, local.calls)
        assertEquals(0, online.calls)
    }

    @Test
    fun `ONLINE mode uses gemini and surfaces its failure`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = IOException("quota"))
        val (engine, _, local) = hybrid(AiMode.ONLINE, online = online)
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(0, local.calls)
    }

    @Test
    fun `AUTO without a key goes straight to the on-device engine`() = runTest {
        val (engine, online, local) = hybrid(AiMode.AUTO, apiKey = "")
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
        assertEquals(0, online.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun `AUTO with a key prefers gemini`() = runTest {
        val (engine, online, local) = hybrid(AiMode.AUTO)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("gemini-flash-lite", result.engineId)
        assertEquals(1, online.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun `AUTO falls back to the on-device engine when gemini fails`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = IOException("429 quota"))
        val (engine, _, local) = hybrid(AiMode.AUTO, online = online)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
        assertEquals(1, local.calls)
    }

    @Test
    fun `AUTO never swallows cancellation`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = CancellationException("stale"))
        val (engine, _, local) = hybrid(AiMode.AUTO, online = online)
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(0, local.calls)
    }
}

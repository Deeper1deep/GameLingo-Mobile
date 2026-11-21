package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.translate.local.LocalAiRouter
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
        var latencyMs: Long = 0,
    ) : TranslationEngine {
        var calls = 0
            private set

        override suspend fun ensureReady() = Unit

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
            failure?.let { throw it }
            return TranslationResult(translatedText = answer, engineId = engineId)
        }

        override fun close() = Unit
    }

    /** Fake local router: no downloadable AI model unless the test installs one. */
    private class FakeLocalRouter(
        private val delegate: TranslationEngine,
        var hasModel: Boolean = false,
        var failure: Exception? = null,
    ) : LocalAiRouter {
        var calls = 0
            private set

        override fun hasLocalAiModel(): Boolean = hasModel

        override fun plannedLocalEngineId(): String = delegate.engineId

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            failure?.let { throw it }
            return delegate.translate(request)
        }

        override fun close() = Unit
    }

    private fun hybridEngine(
        mode: AiMode,
        apiKey: String = "key",
        optIn: Boolean = true,
        online: StubEngine = StubEngine("gemini-flash-lite"),
        local: StubEngine = StubEngine("mlkit-ondevice"),
        router: FakeLocalRouter = FakeLocalRouter(local),
        autoOnlineFallback: Boolean = false,
    ): HybridTranslationEngine {
        val settings = FakeSettingsRepository(
            AppSettings(
                aiMode = mode,
                onlineApiKey = apiKey,
                cloudTranslationOptIn = optIn,
                autoOnlineFallback = autoOnlineFallback,
            ),
        )
        val engine = HybridTranslationEngine(online, local, router, settings)
        // Deterministic backoff: no random jitter in tests.
        engine.jitter = { 0 }
        return engine
    }

    private fun hybrid(
        mode: AiMode,
        apiKey: String = "key",
        optIn: Boolean = true,
        online: StubEngine = StubEngine("gemini-flash-lite"),
        local: StubEngine = StubEngine("mlkit-ondevice"),
    ): Triple<HybridTranslationEngine, StubEngine, StubEngine> {
        val engine = hybridEngine(mode, apiKey, optIn, online, local)
        return Triple(engine, online, local)
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
    fun `AUTO with a key but without cloud opt-in stays on-device`() = runTest {
        val (engine, online, local) = hybrid(AiMode.AUTO, optIn = false)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
        assertEquals(0, online.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun `ONLINE without cloud opt-in never calls gemini and fails visibly`() = runTest {
        val (engine, online, local) = hybrid(AiMode.ONLINE, optIn = false)
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is OnlineNotConfiguredException)
        assertEquals(0, online.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun `ONLINE with opt-in but no key fails visibly instead of a silent local answer`() = runTest {
        val (engine, online, local) = hybrid(AiMode.ONLINE, apiKey = "")
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is OnlineNotConfiguredException)
        assertEquals(0, online.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun `AUTO skips the online engine during the cooldown after a quota failure`() = runTest {
        var clock = 1_000L
        val online = StubEngine("gemini-flash-lite", failure = QuotaExceededException("429"))
        val (engine, _, local) = hybrid(AiMode.AUTO, online = online)
        engine.now = { clock }

        engine.translate(TranslationRequest(text = "one"))
        assertEquals(1, online.calls)
        assertEquals(1, local.calls)

        // Inside the cooldown the online engine is not even tried.
        engine.translate(TranslationRequest(text = "two"))
        assertEquals(1, online.calls)
        assertEquals(2, local.calls)

        // After the cooldown the online engine is tried again.
        clock += engine.cooldownMs + 1
        engine.translate(TranslationRequest(text = "three"))
        assertEquals(2, online.calls)
        assertEquals(3, local.calls)
    }

    @Test
    fun `AUTO planned engine reports local while cooling down`() = runTest {
        var clock = 1_000L
        val online = StubEngine("gemini-flash-lite", failure = ProviderUnavailableException("503"))
        val (engine, _, _) = hybrid(AiMode.AUTO, online = online)
        engine.now = { clock }
        assertEquals("gemini-flash-lite", engine.plannedEngineId())
        engine.translate(TranslationRequest(text = "one"))
        assertEquals("mlkit-ondevice", engine.plannedEngineId())
        clock += engine.cooldownMs + 1
        assertEquals("gemini-flash-lite", engine.plannedEngineId())
    }

    @Test
    fun `AUTO honors Retry-After beyond the default cooldown`() = runTest {
        var clock = 1_000L
        val online = StubEngine(
            "gemini-flash-lite",
            failure = QuotaExceededException("429", retryAfterMs = 120_000L),
        )
        val (engine, _, local) = hybrid(AiMode.AUTO, online = online)
        engine.now = { clock }

        engine.translate(TranslationRequest(text = "one"))
        assertEquals("mlkit-ondevice", engine.plannedEngineId())

        // Past the default 60s cooldown but inside Retry-After: still local.
        clock += engine.cooldownMs + 1
        assertEquals("mlkit-ondevice", engine.plannedEngineId())

        // Past the Retry-After hint: online is tried again.
        clock = 1_000L + 120_001L
        assertEquals("gemini-flash-lite", engine.plannedEngineId())
        engine.translate(TranslationRequest(text = "two"))
        assertTrue(local.calls >= 1)
    }

    @Test
    fun `ONLINE retries a transient failure with exponential backoff`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = ProviderUnavailableException("503"))
        val (engine, _, _) = hybrid(AiMode.ONLINE, online = online)
        val backoffs = mutableListOf<Long>()
        engine.sleep = { backoffs += it }
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        // 1 initial attempt + 2 retries, then the failure surfaces.
        assertTrue(thrown is ProviderUnavailableException)
        assertEquals(3, online.calls)
        assertEquals(listOf(500L, 1_000L), backoffs)
    }

    @Test
    fun `ONLINE succeeds when a retry heals the transient failure`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = ProviderUnavailableException("503"))
        val (engine, _, _) = hybrid(AiMode.ONLINE, online = online)
        engine.sleep = { }
        var attempt = 0
        val healed = object : TranslationEngine by online {
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                attempt++
                if (attempt == 1) throw ProviderUnavailableException("503")
                return TranslationResult(translatedText = "ترجمة", engineId = engineId)
            }
        }
        val settings = FakeSettingsRepository(
            AppSettings(aiMode = AiMode.ONLINE, onlineApiKey = "key", cloudTranslationOptIn = true),
        )
        val stub2 = StubEngine("mlkit-ondevice")
        val engine2 = HybridTranslationEngine(healed, stub2, FakeLocalRouter(stub2), settings)
        engine2.sleep = { }
        val result = engine2.translate(TranslationRequest(text = "hi"))
        assertEquals("gemini-flash-lite", result.engineId)
    }

    @Test
    fun `quota and key failures are never retried`() = runTest {
        for (failure in listOf(QuotaExceededException("429"), ApiAuthException("403"))) {
            val online = StubEngine("gemini-flash-lite", failure = failure)
            val (engine, _, _) = hybrid(AiMode.ONLINE, online = online)
            engine.sleep = { }
            runCatching { engine.translate(TranslationRequest(text = "hi")) }
            assertEquals(1, online.calls)
        }
    }

    @Test
    fun `AUTO falls back to local only after the retries are exhausted`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = java.io.IOException("no internet"))
        val (engine, _, local) = hybrid(AiMode.AUTO, online = online)
        engine.sleep = { }
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
        assertEquals(3, online.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun `AUTO fast online wins and local never runs`() = runTest {
        val online = StubEngine("gemini-flash-lite", latencyMs = 100)
        val local = StubEngine("mlkit-ondevice", latencyMs = 10)
        val (engine, _, _) = hybrid(AiMode.AUTO, online = online, local = local)
        engine.sleep = { }
        engine.autoSoftTimeoutMs = 1_000
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("gemini-flash-lite", result.engineId)
        assertEquals(0, local.calls)
    }

    @Test
    fun `AUTO slow online loses the race to the local engine`() = runTest {
        val online = StubEngine("gemini-flash-lite", latencyMs = 5_000)
        val local = StubEngine("mlkit-ondevice", latencyMs = 50)
        val (engine, _, _) = hybrid(AiMode.AUTO, online = online, local = local)
        engine.sleep = { }
        engine.autoSoftTimeoutMs = 1_000
        val result = engine.translate(TranslationRequest(text = "hi"))
        // Online exceeded the soft timeout, local answered first.
        assertEquals("mlkit-ondevice", result.engineId)
    }

    @Test
    fun `AUTO fast online failure falls back to local immediately`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = QuotaExceededException("429"))
        val local = StubEngine("mlkit-ondevice", latencyMs = 10)
        val (engine, _, _) = hybrid(AiMode.AUTO, online = online, local = local)
        engine.sleep = { }
        engine.autoSoftTimeoutMs = 1_000
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("mlkit-ondevice", result.engineId)
    }

    @Test
    fun `AUTO race failure surfaces when both engines fail`() = runTest {
        val online = StubEngine("gemini-flash-lite", failure = ApiAuthException("403"))
        val local = StubEngine("mlkit-ondevice", failure = IOException("model missing"))
        val (engine, _, _) = hybrid(AiMode.AUTO, online = online, local = local)
        engine.sleep = { }
        engine.autoSoftTimeoutMs = 1_000
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is IOException)
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

    @Test
    fun `AUTO with a local AI model answers locally without touching online`() = runTest {
        val online = StubEngine("gemini-flash-lite")
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true)
        val engine = hybridEngine(AiMode.AUTO, online = online, local = local, router = router)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("translategemma-4b-v1", result.engineId)
        assertEquals(1, router.calls)
        assertEquals(0, online.calls)
    }

    @Test
    fun `AUTO local-first failure stays local when the user disabled online fallback`() = runTest {
        val online = StubEngine("gemini-flash-lite")
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true, failure = IOException("model crashed"))
        val engine = hybridEngine(
            AiMode.AUTO, online = online, local = local, router = router,
            autoOnlineFallback = false,
        )
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(0, online.calls)
    }

    @Test
    fun `AUTO local-first falls back online only under the user-enabled policy`() = runTest {
        val online = StubEngine("gemini-flash-lite", answer = "ترجمة سحابية")
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true, failure = IOException("model crashed"))
        val engine = hybridEngine(
            AiMode.AUTO, online = online, local = local, router = router,
            autoOnlineFallback = true,
        )
        engine.sleep = { }
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("gemini-flash-lite", result.engineId)
        assertEquals(1, online.calls)
    }

    @Test
    fun `AUTO local-first fallback respects the cloud consent gate`() = runTest {
        val online = StubEngine("gemini-flash-lite")
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true, failure = IOException("model crashed"))
        val engine = hybridEngine(
            AiMode.AUTO, optIn = false, online = online, local = local, router = router,
            autoOnlineFallback = true,
        )
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        // No cloud consent: the local failure surfaces, online stays untouched.
        assertTrue(thrown is IOException)
        assertEquals(0, online.calls)
    }

    @Test
    fun `LOCAL mode routes through the local chain`() = runTest {
        val online = StubEngine("gemini-flash-lite")
        val local = StubEngine("local-ai-v1")
        val router = FakeLocalRouter(local, hasModel = true)
        val engine = hybridEngine(AiMode.LOCAL, online = online, local = local, router = router)
        val result = engine.translate(TranslationRequest(text = "hi"))
        assertEquals("local-ai-v1", result.engineId)
        assertEquals(1, router.calls)
        assertEquals(0, online.calls)
    }

    @Test
    fun `plannedEngineId reflects the installed local AI model in AUTO and LOCAL`() = runTest {
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true)
        val engine = hybridEngine(AiMode.AUTO, local = local, router = router)
        assertEquals("translategemma-4b-v1", engine.plannedEngineId())
        val engineLocal = hybridEngine(AiMode.LOCAL, local = local, router = router)
        assertEquals("translategemma-4b-v1", engineLocal.plannedEngineId())
    }

    @Test
    fun `AUTO local-first never swallows cancellation`() = runTest {
        val online = StubEngine("gemini-flash-lite")
        val local = StubEngine("translategemma-4b-v1")
        val router = FakeLocalRouter(local, hasModel = true, failure = CancellationException("stale"))
        val engine = hybridEngine(
            AiMode.AUTO, online = online, local = local, router = router,
            autoOnlineFallback = true,
        )
        val thrown = runCatching { engine.translate(TranslationRequest(text = "hi")) }
            .exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(0, online.calls)
    }
}

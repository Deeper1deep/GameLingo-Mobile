package com.gamelingo.mobile.translate

import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.translate.local.EgyptianDictionaryHolder
import com.gamelingo.mobile.translate.local.LocalAiRouter
import com.gamelingo.mobile.translate.local.LocalModelManager
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real coordinator + worker + hybrid engine with a scripted engine: a looping
 * output is retried at most once, and when it stays corrupt it is never shown,
 * cached or saved to history. A clean retry is shown, cached and saved once.
 * Plumbing proof only: it says nothing about what the real model produces.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class CorruptOutputPipelineTest {

    private class ScriptedEngine(private val outputs: List<String>) : TranslationEngine {
        override val engineId = "mlkit-ondevice"
        val calls = AtomicInteger(0)
        override suspend fun ensureReady() = Unit
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            val i = calls.getAndIncrement()
            return TranslationResult(outputs[minOf(i, outputs.size - 1)], engineId)
        }
        override fun close() = Unit
    }

    private class Router(private val delegate: TranslationEngine) : LocalAiRouter {
        override fun hasLocalAiModel(): Boolean = false
        override fun plannedLocalEngineId(): String = delegate.engineId
        override suspend fun translate(request: TranslationRequest): TranslationResult = delegate.translate(request)
        override fun close() = Unit
    }

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private val loop = "انزل انزل انزل انزل انزل انزل"

    private class Rig(val engine: ScriptedEngine, val cache: TranslationCache, val inserts: AtomicInteger)

    private fun start(outputs: List<String>): Rig {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val engine = ScriptedEngine(outputs)
        val hybrid = HybridTranslationEngine(
            onlineEngine = engine,
            localEngine = engine,
            localChain = Router(engine),
            settingsRepository = FakeSettingsRepository(AppSettings(aiMode = AiMode.LOCAL)),
        )
        val inserts = AtomicInteger(0)
        val dao = Proxy.newProxyInstance(
            TranslationHistoryDao::class.java.classLoader, arrayOf(TranslationHistoryDao::class.java),
        ) { _, method, _ ->
            if (method.name == "insert") inserts.incrementAndGet()
            if (method.returnType == java.lang.Long.TYPE) 1L else null
        } as TranslationHistoryDao
        val cache = TranslationCache()
        val coordinator = TranslationCoordinator(
            hybridEngine = hybrid,
            settingsRepository = FakeSettingsRepository(AppSettings(aiMode = AiMode.LOCAL, cacheEnabled = true)),
            historyDao = dao,
            cache = cache,
            modelManager = LocalModelManager(context, OkHttpClient()),
            dictionaryHolder = EgyptianDictionaryHolder(context),
            understandingHolder = com.gamelingo.mobile.core.understanding.EnglishUnderstandingHolder(context),
        )
        TranslationSessionBus.dismissError()
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        coordinator.start()
        Thread.sleep(800)
        return Rig(engine, cache, inserts)
    }

    @Test
    fun `a line that stays corrupt after the one retry is not shown, cached or saved`() {
        val rig = start(listOf(loop))
        TranslationSessionBus.onNewLine("Watch out behind the truck", System.currentTimeMillis())
        assertTrue("engine was not called twice", waitUntil { rig.engine.calls.get() >= 2 })
        Thread.sleep(600)
        assertEquals("more than one retry", 2, rig.engine.calls.get())
        assertNull(TranslationSessionBus.translatedLine.value)
        assertEquals(0, rig.cache.size())
        assertEquals(0, rig.inserts.get())
        TranslationSessionBus.onSessionStopped()
    }

    @Test
    fun `a clean retry after a corrupt first answer is shown, cached and saved once`() {
        val rig = start(listOf(loop, "خد بالك ورا العربية"))
        TranslationSessionBus.onNewLine("Watch out behind the truck", System.currentTimeMillis())
        assertTrue("clean retry was not shown", waitUntil { TranslationSessionBus.translatedLine.value != null })
        Thread.sleep(400)
        assertEquals(2, rig.engine.calls.get())
        assertEquals("خد بالك ورا العربية", TranslationSessionBus.translatedLine.value!!.translated)
        assertEquals(1, rig.cache.size())
        assertEquals(1, rig.inserts.get())
        TranslationSessionBus.onSessionStopped()
    }
}

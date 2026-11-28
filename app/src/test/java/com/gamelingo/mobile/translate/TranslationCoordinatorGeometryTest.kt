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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The REAL TranslationCoordinator + TranslationWorker + HybridTranslationEngine with a slow
 * engine: a geometry change (rotation) while a request is in flight must make the late
 * result invisible, and the next line must still translate.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class TranslationCoordinatorGeometryTest {

    private class SlowEngine(var latencyMs: Long) : TranslationEngine {
        override val engineId = "mlkit-ondevice"
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)
        override suspend fun ensureReady() = Unit
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            started.incrementAndGet()
            kotlinx.coroutines.delay(latencyMs)
            finished.incrementAndGet()
            return TranslationResult(translatedText = "ترجمة:" + request.text, engineId = engineId)
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

    @Test
    fun `a translation finishing after a rotation is never shown and the next line still translates`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val engine = SlowEngine(latencyMs = 600)
        val hybrid = HybridTranslationEngine(
            onlineEngine = engine,
            localEngine = engine,
            localChain = Router(engine),
            settingsRepository = FakeSettingsRepository(AppSettings(aiMode = AiMode.LOCAL)),
        )
        val dao = Proxy.newProxyInstance(
            TranslationHistoryDao::class.java.classLoader, arrayOf(TranslationHistoryDao::class.java),
        ) { _, method, _ -> if (method.returnType == java.lang.Long.TYPE) 0L else null } as TranslationHistoryDao
        val coordinator = TranslationCoordinator(
            hybridEngine = hybrid,
            settingsRepository = FakeSettingsRepository(AppSettings(aiMode = AiMode.LOCAL, cacheEnabled = false)),
            historyDao = dao,
            cache = TranslationCache(),
            modelManager = LocalModelManager(context, OkHttpClient()),
            dictionaryHolder = EgyptianDictionaryHolder(context),
            understandingHolder = com.gamelingo.mobile.core.understanding.EnglishUnderstandingHolder(context),
        )

        TranslationSessionBus.dismissError()
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        coordinator.start()
        // The coordinator subscribes to the (non-replaying) line stream asynchronously.
        Thread.sleep(800)

        TranslationSessionBus.onNewLine("Get in the car now", System.currentTimeMillis())
        assertTrue("engine never started the first request", waitUntil { engine.started.get() >= 1 })
        // Rotation lands while the request is still in the slow engine.
        TranslationSessionBus.onGeometryChanged()
        assertTrue("slow engine never finished", waitUntil { engine.finished.get() >= 1 })
        Thread.sleep(300) // let the coordinator attempt its publish
        assertNull("a result from before the rotation was shown", TranslationSessionBus.translatedLine.value)

        // The pipeline is alive: a line read after the rotation translates and shows.
        TranslationSessionBus.onNewLine("They are coming for us", System.currentTimeMillis())
        assertTrue(
            "the line after the rotation was not translated",
            waitUntil { TranslationSessionBus.translatedLine.value != null },
        )
        val shown = TranslationSessionBus.translatedLine.value!!
        assertNotNull(shown)
        assertEquals("They are coming for us", shown.original)
        TranslationSessionBus.onSessionStopped()
        coordinator.shutdownForTest()
    }
}

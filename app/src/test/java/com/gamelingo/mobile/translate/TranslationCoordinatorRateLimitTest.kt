package com.gamelingo.mobile.translate

import androidx.test.core.app.ApplicationProvider
import com.gamelingo.mobile.core.session.RequestState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.translate.local.EgyptianDictionaryHolder
import com.gamelingo.mobile.translate.local.LocalAiRouter
import com.gamelingo.mobile.translate.local.LocalModelManager
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * The REAL coordinator, worker, hybrid engine and Gemini engine over a scripted HTTP layer,
 * in ONLINE mode: a rate limit pauses translation only, the capture side keeps working, and
 * the newest relevant line is translated after recovery. Real (short) wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class TranslationCoordinatorRateLimitTest {

    private class ScriptedApi : GeminiApi {
        val calls = AtomicInteger(0)
        val texts = CopyOnWriteArrayList<String>()
        @Volatile var limitedUntilCall = 0
        @Volatile var latencyFor: (String) -> Long = { 0L }

        override suspend fun generateContent(model: String, apiKey: String, request: GeminiRequest): GeminiResponse {
            val n = calls.incrementAndGet()
            val prompt = request.contents.first().parts.first().text.orEmpty()
            texts.add(prompt)
            if (n <= limitedUntilCall) {
                val raw = okhttp3.Response.Builder().code(429).message("x").protocol(Protocol.HTTP_1_1)
                    .request(Request.Builder().url("http://localhost/").build())
                    .header("Retry-After", "2").build()
                throw HttpException(
                    Response.error<GeminiResponse>("{}".toResponseBody("application/json".toMediaType()), raw),
                )
            }
            kotlinx.coroutines.delay(latencyFor(prompt))
            return GeminiResponse(
                candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart("ترجمة"))))),
            )
        }
    }

    private class NoLocal : LocalAiRouter {
        override fun hasLocalAiModel(): Boolean = false
        override fun plannedLocalEngineId(): String = "mlkit-ondevice"
        override suspend fun translate(request: TranslationRequest): TranslationResult = error("local not used")
        override fun close() = Unit
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Before fun resetGate() {
        OnlineRequestGate.shared.resetForTest()
        OnlineRequestGate.shared.jitter = { 0L }
    }

    @After fun stop() {
        TranslationSessionBus.onSessionStopped()
        OnlineRequestGate.shared.resetForTest()
    }

    private fun start(api: ScriptedApi): TranslationCoordinator {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val settings = FakeSettingsRepository(
            AppSettings(aiMode = AiMode.ONLINE, cloudTranslationOptIn = true, onlineApiKey = "k", cacheEnabled = false),
        )
        val gemini = GeminiTranslationEngine(api, settings)
        gemini.gate = OnlineRequestGate.shared
        val local = object : TranslationEngine {
            override val engineId = "mlkit-ondevice"
            override suspend fun ensureReady() = Unit
            override suspend fun translate(request: TranslationRequest): TranslationResult = error("local not used")
            override fun close() = Unit
        }
        val hybrid = HybridTranslationEngine(
            onlineEngine = gemini, localEngine = local, localChain = NoLocal(), settingsRepository = settings,
        )
        hybrid.maxRetries = 0
        val dao = Proxy.newProxyInstance(
            TranslationHistoryDao::class.java.classLoader, arrayOf(TranslationHistoryDao::class.java),
        ) { _, method, _ -> if (method.returnType == java.lang.Long.TYPE) 0L else null } as TranslationHistoryDao
        val coordinator = TranslationCoordinator(
            hybridEngine = hybrid,
            settingsRepository = settings,
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
        Thread.sleep(800)
        return coordinator
    }

    @Test
    fun `during a rate limit capture and OCR keep advancing, nothing is requested, then only the newest line is translated`() {
        val api = ScriptedApi().also { it.limitedUntilCall = 1 }
        start(api)

        TranslationSessionBus.onNewLine("First line", System.currentTimeMillis())
        assertTrue("429 never reached the worker", waitUntil {
            TranslationSessionBus.workerStatus.value.state == RequestState.RATE_LIMITED
        })
        val status = TranslationSessionBus.workerStatus.value
        assertTrue(status.lastError.orEmpty().startsWith("QUOTA_429"))
        assertTrue("cooldown end must be exposed to diagnostics", status.cooldownUntilMillis > System.currentTimeMillis() - 1_000L)
        assertEquals(1, api.calls.get())

        // The capture/OCR side keeps producing while translation is paused: new lines register as
        // the live appearance, heartbeats and unchanged frames are accepted, and the bus tracks them.
        val seenLines = CopyOnWriteArrayList<String>()
        val t0 = System.currentTimeMillis()
        listOf("Second line", "Third line", "Newest line").forEachIndexed { i, text ->
            TranslationSessionBus.onNewLine(text, t0 + i)
            assertEquals(text, TranslationSessionBus.latestLine.value?.text)
            TranslationSessionBus.onFrameUnchanged(t0 + i + 1)
            TranslationSessionBus.onHeartbeat(t0 + i + 2)
            seenLines.add(TranslationSessionBus.latestLine.value!!.text)
            Thread.sleep(100)
        }
        assertEquals(listOf("Second line", "Third line", "Newest line"), seenLines.toList())
        // Still inside the cooldown: no network request was made for any of them.
        assertEquals("a request left the device during the cooldown", 1, api.calls.get())

        // Recovery: after the provider's 2 s, only the newest relevant line is sent and shown.
        assertTrue("translation never resumed", waitUntil(12_000) {
            TranslationSessionBus.translatedLine.value?.original == "Newest line"
        })
        assertTrue(api.texts.last().contains("Newest line"))
        assertFalse("a superseded line was sent after recovery", api.texts.drop(1).any { it.contains("Second line") && !it.contains("Newest line") })
        assertEquals(RequestState.SUCCESS, TranslationSessionBus.workerStatus.value.state)
    }

    @Test
    fun `an old response arriving after a newer subtitle is never shown`() {
        val api = ScriptedApi()
        api.latencyFor = { prompt -> if (prompt.contains("Old subtitle")) 700L else 0L }
        start(api)

        val shownOriginals = CopyOnWriteArrayList<String>()
        val collector = Thread {
            var last: String? = null
            while (!Thread.currentThread().isInterrupted) {
                val v = TranslationSessionBus.translatedLine.value?.original
                if (v != null && v != last) { shownOriginals.add(v); last = v }
                try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread }
            }
        }
        collector.start()

        TranslationSessionBus.onNewLine("Old subtitle", System.currentTimeMillis())
        assertTrue(waitUntil { api.calls.get() >= 1 })
        // A different subtitle replaces it while the old request is still in flight.
        TranslationSessionBus.onNewLine("New subtitle", System.currentTimeMillis())
        assertTrue("newer line never translated", waitUntil {
            TranslationSessionBus.translatedLine.value?.original == "New subtitle"
        })
        // Give the slow old response time to land and attempt a publish.
        Thread.sleep(1_200)
        collector.interrupt()
        collector.join()
        assertEquals("New subtitle", TranslationSessionBus.translatedLine.value?.original)
        assertFalse("the old response was shown: $shownOriginals", shownOriginals.contains("Old subtitle"))
    }
}

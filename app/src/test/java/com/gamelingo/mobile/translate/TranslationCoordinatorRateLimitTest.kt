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
        @Volatile var shiftEchoes = false
        @Volatile var latencyFor: (String) -> Long = { 0L }
        /** When set, a request whose prompt matches waits until the test completes it (no timing involved). */
        @Volatile var holdUntil: Pair<(String) -> Boolean, kotlinx.coroutines.CompletableDeferred<Unit>>? = null

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
            holdUntil?.let { (matches, latch) -> if (matches(prompt)) latch.await() }
            if (Regex("Translate these (\\d+) lines").containsMatchIn(prompt)) {
                // Batch request: answer one string per numbered line, echoing the line so the test can check alignment.
                val numbered = prompt.lines().mapNotNull { Regex("^(\\d+)\\. (.*)$").find(it)?.groupValues?.get(2) }
                val array = numbered.mapIndexed { i, line ->
                    // "shift" mode: the model labels every answer with the NEXT line's words (misaligned).
                    val echoFrom = if (shiftEchoes) numbered[(i + 1) % numbered.size] else line
                    val echo = echoFrom.split(" ").take(3).joinToString(" ")
                    "{\"n\":${i + 1},\"src\":\"$echo\",\"t\":\"ترجمة: $line\"}"
                }.joinToString(",", "[", "]")
                return GeminiResponse(
                    candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart(array))))),
                )
            }
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

    private val historyInserts = java.util.concurrent.atomic.AtomicInteger(0)
    private var coordinator: TranslationCoordinator? = null

    @After fun stop() {
        coordinator?.shutdownForTest()
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
        ) { _, method, _ ->
            if (method.name == "insert") historyInserts.incrementAndGet()
            if (method.returnType == java.lang.Long.TYPE) 0L else null
        } as TranslationHistoryDao
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
        this.coordinator = coordinator
        Thread.sleep(800)
        return coordinator
    }

    @Test
    fun `during a rate limit capture keeps advancing, nothing is requested, then every waiting line goes in one aligned request`() {
        val api = ScriptedApi().also { it.limitedUntilCall = 1 }
        start(api)

        TranslationSessionBus.onNewLine("First line", System.currentTimeMillis())
        assertTrue("429 never reached the worker", waitUntil {
            TranslationSessionBus.workerStatus.value.state == RequestState.RATE_LIMITED
        })
        assertEquals(1, api.calls.get())
        val t0 = System.currentTimeMillis()
        listOf("Second line", "Third line", "Newest line").forEachIndexed { i, text ->
            TranslationSessionBus.onNewLine(text, t0 + i)
            assertEquals(text, TranslationSessionBus.latestLine.value?.text)
            Thread.sleep(100)
        }
        assertEquals("a request left the device during the cooldown", 1, api.calls.get())

        assertTrue("translation never resumed", waitUntil(12_000) {
            TranslationSessionBus.translatedLine.value?.original == "Newest line"
        })
        Thread.sleep(500)
        println("COORD1 prompts=${api.texts.map { it.replace("\n", " / ") }} state=${TranslationSessionBus.workerStatus.value}")
        assertEquals("one rejected request plus one batch", 2, api.calls.get())
        val batchPrompt = api.texts.last()
        val order = listOf("First line", "Second line", "Third line", "Newest line").map { batchPrompt.indexOf(it) }
        assertTrue("all four lines, in order: $order", order.all { it >= 0 } && order == order.sorted())
        assertEquals("ترجمة: Newest line", TranslationSessionBus.translatedLine.value?.translated)
        assertEquals(0, TranslationSessionBus.workerStatus.value.lostLines)
        assertEquals(1, TranslationSessionBus.workerStatus.value.batchesSent)
        assertEquals(RequestState.SUCCESS, TranslationSessionBus.workerStatus.value.state)
    }

    @Test
    fun `a misaligned batch answer is never shown, every line is translated on its own`() {
        val api = ScriptedApi().also { it.limitedUntilCall = 1; it.shiftEchoes = true }
        start(api)
        TranslationSessionBus.onNewLine("First line", System.currentTimeMillis())
        assertTrue(waitUntil { TranslationSessionBus.workerStatus.value.state == RequestState.RATE_LIMITED })
        val t0 = System.currentTimeMillis()
        listOf("Second line", "Third line", "Newest line").forEachIndexed { i, text ->
            TranslationSessionBus.onNewLine(text, t0 + i)
            Thread.sleep(100)
        }
        assertTrue("never resumed", waitUntil(25_000) { api.calls.get() >= 6 })
        assertTrue("single answer expected for the newest line", waitUntil(10_000) {
            TranslationSessionBus.translatedLine.value?.original == "Newest line"
        })
        val shown = TranslationSessionBus.translatedLine.value!!
        println("COORD3 prompts=${api.texts.map { it.replace("\n", " / ") }}")
        // Not the batch text ("ترجمة: Newest line"): it came from the one-line request.
        assertEquals("ترجمة", shown.translated)
        assertEquals(0, TranslationSessionBus.workerStatus.value.lostLines)
    }

    @Test
    fun `an old response arriving after a newer subtitle is never shown, deterministically, and still reaches the history`() {
        val api = ScriptedApi()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.holdUntil = ({ prompt: String -> prompt.contains("Old subtitle") }) to release
        start(api)

        val shownOriginals = CopyOnWriteArrayList<String>()
        val collector = Thread {
            var last: String? = null
            while (!Thread.currentThread().isInterrupted) {
                val v = TranslationSessionBus.translatedLine.value?.original
                if (v != null && v != last) { shownOriginals.add(v); last = v }
                try { Thread.sleep(2) } catch (_: InterruptedException) { return@Thread }
            }
        }
        collector.start()

        TranslationSessionBus.onNewLine("Old subtitle", System.currentTimeMillis())
        assertTrue(waitUntil { api.calls.get() >= 1 })
        // The old request is now held inside the provider call. The newer subtitle replaces it,
        // and only then is the old response released: the order no longer depends on any timing.
        TranslationSessionBus.onNewLine("New subtitle", System.currentTimeMillis())
        assertEquals("New subtitle", TranslationSessionBus.latestLine.value?.text)
        release.complete(Unit)
        assertTrue("newer line never translated", waitUntil {
            TranslationSessionBus.translatedLine.value?.original == "New subtitle"
        })
        Thread.sleep(300)
        collector.interrupt()
        collector.join()
        assertEquals("New subtitle", TranslationSessionBus.translatedLine.value?.original)
        assertFalse("the old response was shown: $shownOriginals", shownOriginals.contains("Old subtitle"))
        assertEquals(2, api.calls.get())
        // Late translation is still kept: the history got both lines (the dao here counts inserts).
        assertEquals("both the late old line and the new line are in the history", 2, historyInserts.get())
    }

    @Test
    fun `the bus rejects a result for a replaced subtitle atomically, even when it arrives at the same instant as the replacement`() {
        TranslationSessionBus.onSessionStopped()
        TranslationSessionBus.onSessionStarted()
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(300) { round ->
            TranslationSessionBus.onNewLine("old $round", System.currentTimeMillis())
            val oldId = TranslationSessionBus.latestLine.value!!.subtitleId
            val gen = TranslationSessionBus.latestLine.value!!.generation
            val start = java.util.concurrent.CountDownLatch(1)
            val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
            val publisher = Thread {
                start.await()
                accepted.set(TranslationSessionBus.onTranslated("old $round", "ترجمة", "e", false, 0L, oldId, gen))
            }
            val replacer = Thread {
                start.await()
                TranslationSessionBus.onNewLine("new $round", System.currentTimeMillis())
            }
            publisher.start(); replacer.start(); start.countDown(); publisher.join(); replacer.join()
            // Either interleaving is consistent: a result the bus accepted is the stored one (it was live when it
            // landed); a refused result never lands. No half state, and no accept after the replacement.
            val stored = TranslationSessionBus.translatedLine.value?.subtitleId == oldId
            if (accepted.get() != stored) failures.incrementAndGet()
            // And a result arriving after the replacement is always refused.
            assertFalse(TranslationSessionBus.onTranslated("old $round", "ترجمة", "e", false, 0L, oldId, gen))
        }
        assertEquals("accepted and stored state disagreed", 0, failures.get())
        TranslationSessionBus.onSessionStopped()
    }

}

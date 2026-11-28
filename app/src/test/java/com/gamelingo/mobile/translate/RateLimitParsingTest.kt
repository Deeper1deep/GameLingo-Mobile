package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RateLimitParsingTest {

    private fun http(code: Int, body: String, vararg headers: Pair<String, String>): HttpException {
        val raw = okhttp3.Response.Builder()
            .code(code).message("x").protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/").build())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = Response.error<GeminiResponse>(body.toResponseBody("application/json".toMediaType()), raw)
        return HttpException(resp)
    }

    private class ThrowingApi(private val error: Throwable) : GeminiApi {
        var calls = 0
        override suspend fun generateContent(model: String, apiKey: String, request: GeminiRequest): GeminiResponse {
            calls++
            throw error
        }
    }

    private fun engine(api: GeminiApi, t: () -> Long = { 0L }) =
        GeminiTranslationEngine(api, FakeSettingsRepository(AppSettings(onlineApiKey = "k"))).also {
            it.gate = OnlineRequestGate(now = t, jitter = { 0L })
        }

    private val body429 = """{"error":{"code":429,"message":"Quota exceeded for line: SECRET DIALOGUE AIzaSyFAKEKEY","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""

    @Test fun `HTTP 429 maps to a quota error with whitelisted provider facts only`() = runTest {
        val api = ThrowingApi(http(429, body429))
        val e = runCatching { engine(api).translate(TranslationRequest(text = "hello")) }.exceptionOrNull()
        assertTrue(e is QuotaExceededException)
        e as QuotaExceededException
        assertEquals("RESOURCE_EXHAUSTED", e.provider.status)
        assertEquals("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", e.provider.quotaId)
        assertEquals(37_000L, e.retryAfterMs)
        val code = TranslationErrorCode.of(e)
        assertEquals("QUOTA_429/RESOURCE_EXHAUSTED/GenerateRequestsPerMinutePerProjectPerModel-FreeTier", code)
        // Neither dialogue text nor a key from the body can leak into what we keep.
        for (text in listOf(e.message.orEmpty(), code)) {
            assertFalse(text.contains("SECRET"))
            assertFalse(text.contains("AIza"))
        }
    }

    @Test fun `Retry-After header wins over the body delay and the gate pauses the engine`() = runTest {
        var t = 5_000L
        val api = ThrowingApi(http(429, body429, "Retry-After" to "120"))
        val eng = engine(api) { t }
        val e = runCatching { eng.translate(TranslationRequest(text = "a")) }.exceptionOrNull() as QuotaExceededException
        assertEquals(120_000L, e.retryAfterMs)
        // Second line during the cooldown: no request leaves the device.
        val again = runCatching { eng.translate(TranslationRequest(text = "b")) }.exceptionOrNull() as QuotaExceededException
        assertTrue(again.fromCooldown)
        assertEquals(1, api.calls)
        t += 120_001L
        runCatching { eng.translate(TranslationRequest(text = "c")) }
        assertEquals(2, api.calls)
    }

    @Test fun `invalid key is a permanent auth error that never starts a cooldown`() = runTest {
        val api = ThrowingApi(http(401, """{"error":{"code":401,"status":"UNAUTHENTICATED"}}"""))
        val eng = engine(api)
        val e = runCatching { eng.translate(TranslationRequest(text = "a")) }.exceptionOrNull()
        assertTrue(e is ApiAuthException)
        assertEquals("AUTH_REJECTED", TranslationErrorCode.of(e!!))
        assertEquals(0L, eng.gate.remainingMs())
        runCatching { eng.translate(TranslationRequest(text = "b")) }
        assertEquals(2, api.calls)
    }

    @Test fun `Retry-After parsing handles seconds, HTTP dates, garbage and absurd values`() {
        val now = java.time.ZonedDateTime.parse("2026-11-28T03:00:00Z").toInstant().toEpochMilli()
        assertEquals(30_000L, RetryAfter.parseMillis("30", now))
        assertEquals(0L, RetryAfter.parseMillis("0", now))
        assertEquals(45_000L, RetryAfter.parseMillis("Sat, 28 Nov 2026 03:00:45 GMT", now))
        // A date in the past means no wait.
        assertEquals(0L, RetryAfter.parseMillis("Sat, 28 Nov 2026 02:59:00 GMT", now))
        // A long valid hint is preserved in full, never shortened to ten minutes.
        assertEquals(900_000L, RetryAfter.parseMillis("900", now))
        assertEquals(86_400_000L, RetryAfter.parseMillis("86400", now))
        assertEquals(86_400_000L, RetryAfter.parseMillis("Sun, 29 Nov 2026 03:00:00 GMT", now))
        // Only an overflow-sized value is capped (seven days), without arithmetic overflow.
        assertEquals(RetryAfter.MAX_SECONDS * 1_000L, RetryAfter.parseMillis("99999999999999999999999", now))
        assertEquals(RetryAfter.MAX_SECONDS * 1_000L, RetryAfter.parseMillis("9223372036854775807", now))
        assertNull(RetryAfter.parseMillis("soon", now))
        assertNull(RetryAfter.parseMillis("-5", now))
        assertNull(RetryAfter.parseMillis(null, now))
        assertNull(RetryAfter.parseMillis("  ", now))
    }

    @Test fun `a provider hint over ten minutes blocks every request until it is allowed`() = runTest {
        var t = 1_000L
        val api = ThrowingApi(http(429, body429, "Retry-After" to "1800"))
        val eng = engine(api) { t }
        val e = runCatching { eng.translate(TranslationRequest(text = "a")) }.exceptionOrNull() as QuotaExceededException
        assertEquals(1_800_000L, e.retryAfterMs)
        // 11 minutes later (past the old ten-minute clamp) nothing may be sent yet.
        t += 660_000L
        val early = runCatching { eng.translate(TranslationRequest(text = "b")) }.exceptionOrNull() as QuotaExceededException
        assertTrue(early.fromCooldown)
        assertEquals(1, api.calls)
        t += 1_140_001L
        runCatching { eng.translate(TranslationRequest(text = "c")) }
        assertEquals(2, api.calls)
    }

    @Test fun `provider body parsing keeps only whitelisted tokens`() {
        val none = ProviderErrorInfo.parse("""{"error":{"status":"SOMETHING_WITH_DIALOGUE_IN_IT","quotaId":"has spaces and text"}}""")
        assertNull(none.status)
        assertNull(none.quotaId)
        assertNull(ProviderErrorInfo.parse(null).status)
        assertNotNull(ProviderErrorInfo.parse(body429).status)
    }

    @Test fun `stable codes do not depend on class names`() {
        assertEquals("PROVIDER_UNAVAILABLE", TranslationErrorCode.of(ProviderUnavailableException("x")))
        assertEquals("PROVIDER_REFUSED", TranslationErrorCode.of(ProviderRefusedException("x")))
        assertEquals("ONLINE_NOT_CONFIGURED", TranslationErrorCode.of(OnlineNotConfiguredException()))
        assertEquals("NETWORK_IO", TranslationErrorCode.of(IOException("x")))
        assertEquals("QUOTA_COOLDOWN", TranslationErrorCode.of(QuotaExceededException("x", fromCooldown = true)))
        assertEquals("UNKNOWN", TranslationErrorCode.of(IllegalStateException()))
    }

    @Test fun `a translation timeout is classified as TIMEOUT`() = runTest {
        val e = runCatching { withTimeout(10) { kotlinx.coroutines.awaitCancellation() } }.exceptionOrNull()
        assertTrue(e is TimeoutCancellationException)
        assertEquals("TIMEOUT", TranslationErrorCode.of(e!!))
    }

    @Test fun `entry points sharing one gate stay within the concurrency limit`() = runTest {
        class SlowApi : GeminiApi {
            var active = 0
            var peak = 0
            override suspend fun generateContent(model: String, apiKey: String, request: GeminiRequest): GeminiResponse {
                active++
                peak = maxOf(peak, active)
                try { kotlinx.coroutines.delay(100) } finally { active-- }
                return GeminiResponse(
                    candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart("ok"))))),
                )
            }
        }
        val api = SlowApi()
        val shared = OnlineRequestGate(now = { 0L }, jitter = { 0L })
        // Two engine instances stand in for two entry points (live pipeline and the connection test).
        val a = engine(api).also { it.gate = shared }
        val b = engine(api).also { it.gate = shared }
        val jobs = (0 until 6).map { i ->
            async { (if (i % 2 == 0) a else b).translate(TranslationRequest(text = "line $i")) }
        }
        jobs.forEach { it.await() }
        assertEquals(1, api.peak)
        assertEquals(1, shared.peakInFlight.get())
    }
}

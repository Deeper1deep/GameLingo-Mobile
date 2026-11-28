package com.gamelingo.mobile.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRequestGateTest {

    private fun gate(clock: () -> Long, max: Int = 1) =
        OnlineRequestGate(maxConcurrent = max, now = clock, jitter = { 0L })

    @Test fun `a 429 with Retry-After pauses the gate locally then it recovers on its own`() = runTest {
        var t = 1_000L
        val g = gate({ t })
        var networkCalls = 0
        val first = runCatching {
            g.run<Unit> { networkCalls++; throw QuotaExceededException("429", retryAfterMs = 30_000L) }
        }.exceptionOrNull()
        assertTrue(first is QuotaExceededException)
        assertEquals(30_000L, g.remainingMs())
        // Inside the cooldown: refused locally, no network call.
        val during = runCatching { g.run { networkCalls++ } }.exceptionOrNull() as QuotaExceededException
        assertTrue(during.fromCooldown)
        assertEquals(1, networkCalls)
        t += 30_001L
        assertEquals(0L, g.remainingMs())
        g.run { networkCalls++ }
        assertEquals(2, networkCalls)
        assertEquals(0, g.consecutiveRateLimits())
    }

    @Test fun `without a provider hint the cooldown backs off exponentially and is capped`() = runTest {
        var t = 0L
        val g = gate({ t })
        val expected = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        for (want in expected) {
            runCatching { g.run<Unit> { throw QuotaExceededException("429") } }
            assertEquals(want, g.remainingMs())
            t += want + 1
        }
    }

    @Test fun `jitter only adds to the provider hint and never shortens it`() = runTest {
        val g = OnlineRequestGate(now = { 0L }, jitter = { it })
        runCatching { g.run<Unit> { throw QuotaExceededException("429", retryAfterMs = 10_000L) } }
        assertTrue(g.remainingMs() in 10_000L..11_000L)
    }

    @Test fun `an invalid key is not a rate limit and starts no cooldown`() = runTest {
        val g = gate({ 0L })
        val e = runCatching { g.run<Unit> { throw ApiAuthException("401") } }.exceptionOrNull()
        assertTrue(e is ApiAuthException)
        assertEquals(0L, g.remainingMs())
        assertEquals(0, g.consecutiveRateLimits())
    }

    @Test fun `concurrent callers from different entry points never exceed the limit`() = runTest {
        val g = gate({ 0L }, max = 1)
        val jobs = List(6) { i ->
            async { g.run { delay(100); i } }
        }
        assertEquals(List(6) { it }, jobs.awaitAll())
        assertEquals(1, g.peakInFlight.get())

        val two = gate({ 0L }, max = 2)
        List(6) { async { two.run { delay(100) } } }.awaitAll()
        assertEquals(2, two.peakInFlight.get())
    }

    @Test fun `a caller waiting for a permit sees the rate limit of the call ahead of it`() = runTest {
        var t = 0L
        val g = gate({ t })
        var calls = 0
        val a = async {
            runCatching { g.run<Unit> { calls++; delay(50); throw QuotaExceededException("429", retryAfterMs = 60_000L) } }
        }
        val b = async { runCatching { g.run { calls++ } }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(60)
        a.await()
        t += 50
        val seenByB = b.await() as QuotaExceededException
        assertTrue(seenByB.fromCooldown)
        assertEquals(1, calls)
    }

    @Test fun `cancellation propagates, frees the permit and is not a failure`() = runTest {
        val g = gate({ 0L }, max = 1)
        val holder = launch { g.run { delay(10_000) } }
        runCurrent()
        val waiter = launch { g.run { delay(10) } }
        runCurrent()
        waiter.cancel()
        holder.cancel()
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertEquals(0L, g.remainingMs())
        assertEquals(0, g.consecutiveRateLimits())
        // The permit is free again.
        assertEquals(7, g.run { 7 })
        val thrown = runCatching { g.run<Unit> { throw CancellationException("x") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertFalse(g.remainingMs() > 0)
    }

    @Test fun `a local refusal keeps naming the provider fields of the real 429`() = runTest {
        var t = 0L
        val g = gate({ t })
        val info = ProviderErrorInfo(status = "RESOURCE_EXHAUSTED", quotaId = "PerMinutePerProject", retryDelayMs = 20_000L)
        runCatching { g.run<Unit> { throw QuotaExceededException("429", retryAfterMs = 20_000L, provider = info) } }
        t += 5_000L
        val refused = runCatching { g.run { } }.exceptionOrNull() as QuotaExceededException
        assertTrue(refused.fromCooldown)
        assertEquals(info, refused.provider)
        assertEquals("QUOTA_COOLDOWN/RESOURCE_EXHAUSTED/PerMinutePerProject", TranslationErrorCode.of(refused))
    }
}

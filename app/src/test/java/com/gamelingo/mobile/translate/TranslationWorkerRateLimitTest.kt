package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.session.RequestState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pause on a rate limit, auto-resume of only the current relevant line, bounded retries. */
class TranslationWorkerRateLimitTest {

    private val ok = ProcessorOutcome(engineId = "gemini-flash-lite", usedLocalFallback = false)

    private fun TestScope.worker(
        calls: ConcurrentLinkedQueue<String>,
        relevant: (QueuedRequest) -> Boolean = { true },
        maxAutoResumes: Int = 5,
        cooldown: () -> Long = { 0L },
        behavior: suspend (QueuedRequest) -> ProcessorOutcome,
    ) = TranslationWorker(
        processor = { calls.add(it.text); behavior(it) },
        dedupWindowMs = 2_000L,
        dispatcher = StandardTestDispatcher(testScheduler),
        now = { testScheduler.currentTime },
        logger = { },
        enableWatchdog = false,
        isRelevant = relevant,
        maxAutoResumes = maxAutoResumes,
        cooldownRemainingMs = cooldown,
    )

    @Test fun `after a 429 the newest line is retried once when the cooldown ends, without new text`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var limited = true
        val w = worker(calls) { if (limited) throw QuotaExceededException("429", retryAfterMs = 10_000L) else ok }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        runCurrent()
        assertEquals(RequestState.RATE_LIMITED, w.currentStatus().state)
        assertEquals(1, calls.size)
        limited = false
        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals("nothing is sent while paused", 1, calls.size)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("line", "line"), calls.toList())
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test fun `no resume when the subtitle is no longer relevant`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var relevant = true
        val w = worker(calls, relevant = { relevant }) { throw QuotaExceededException("429", retryAfterMs = 5_000L) }
        w.start()
        w.enqueue("gone", 0, subtitleId = 1, generation = 1)
        runCurrent()
        relevant = false
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, calls.size)
        w.stop()
    }

    @Test fun `a newer line supersedes the old one, only the newest is resumed`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var limited = true
        val w = worker(calls) { if (limited) throw QuotaExceededException("429", retryAfterMs = 10_000L) else ok }
        w.start()
        w.enqueue("old", 0, subtitleId = 1, generation = 1)
        runCurrent()
        w.enqueue("new", 1, subtitleId = 2, generation = 1)
        runCurrent()
        limited = false
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(listOf("old", "new", "new"), calls.toList())
        w.stop()
    }

    @Test fun `auto-resume is bounded so a permanent limit cannot loop forever`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls, maxAutoResumes = 2) { throw QuotaExceededException("429", retryAfterMs = 5_000L) }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        advanceTimeBy(120_000L)
        runCurrent()
        // The first attempt plus two resumes, then it stops and waits for a new line.
        assertEquals(3, calls.size)
        assertEquals(RequestState.RATE_LIMITED, w.currentStatus().state)
        w.stop()
    }

    @Test fun `an invalid key is not resumed`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls) { throw ApiAuthException("401") }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(RequestState.AUTH_ERROR, w.currentStatus().state)
        w.stop()
    }

    @Test fun `rapid different lines never lose the newest and a repeated line is sent at most once`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls) { ok }
        w.start()
        listOf("a" to 1L, "b" to 2L, "b" to 2L, "c" to 3L, "d" to 4L).forEachIndexed { i, (t, id) -> w.enqueue(t, i.toLong(), subtitleId = id) }
        runCurrent()
        val list = calls.toList()
        assertEquals("d", list.last())
        assertTrue(list.count { it == "b" } <= 1)
        assertEquals(listOf("a", "b", "c", "d"), list)
        w.stop()
    }

    @Test fun `the last error code is stable and carries no dialogue`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls) { throw QuotaExceededException("429 SECRET LINE", retryAfterMs = 5_000L) }
        w.start()
        w.enqueue("SECRET LINE", 0, subtitleId = 1, generation = 1)
        runCurrent()
        val err = w.currentStatus().lastError.orEmpty()
        assertTrue(err, err.startsWith("QUOTA_429"))
        assertTrue(!err.contains("SECRET"))
        w.stop()
    }

    @Test fun `a long hint delays the resume until it has passed, the resume budget is separate`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var limited = true
        val w = worker(calls, maxAutoResumes = 1) { if (limited) throw QuotaExceededException("429", retryAfterMs = 1_800_000L) else ok }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        runCurrent()
        limited = false
        advanceTimeBy(1_700_000L)
        runCurrent()
        assertEquals("not before the provider allows it", 1, calls.size)
        advanceTimeBy(101_000L)
        runCurrent()
        assertEquals(2, calls.size)
        w.stop()
    }

    @Test fun `an unrepresentable hint enters a blocked state with no automatic resume`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls) { throw QuotaExceededException("429", retryAfterMs = RetryAfter.INDEFINITE_MS) }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        advanceTimeBy(10L * 365 * 86_400_000L)
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(RequestState.RATE_LIMITED, w.currentStatus().state)
        assertEquals(Long.MAX_VALUE, w.currentStatus().cooldownUntilMillis)
        w.stop()
    }

    @Test fun `the real provider limit is kept apart from a later local refusal and kept as history after a success`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var mode = 0
        val info = ProviderErrorInfo(status = "RESOURCE_EXHAUSTED", quotaId = "PerMinutePerProject")
        val w = worker(calls) {
            when (mode) {
                0 -> throw QuotaExceededException("429", retryAfterMs = 20_000L, provider = info)
                1 -> throw QuotaExceededException("cooldown", retryAfterMs = 15_000L, fromCooldown = true)
                else -> ok
            }
        }
        w.start()
        w.enqueue("one", 0, subtitleId = 1, generation = 1)
        runCurrent()
        mode = 1
        w.enqueue("two", 1, subtitleId = 2, generation = 1)
        runCurrent()
        val st = w.currentStatus()
        assertTrue(st.lastError.orEmpty(), st.lastError.orEmpty().startsWith("QUOTA_COOLDOWN"))
        assertEquals("QUOTA_429/RESOURCE_EXHAUSTED/PerMinutePerProject/wait=20s", st.lastProviderLimit)
        assertTrue(st.lastProviderLimitAtMillis >= 0L)
        mode = 2
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(-1L, w.currentStatus().cooldownUntilMillis)
        assertEquals("QUOTA_429/RESOURCE_EXHAUSTED/PerMinutePerProject/wait=20s", w.currentStatus().lastProviderLimit)
        w.stop()
    }

    @Test fun `lines arriving during a cooldown are held, only the newest is sent after it, none fails`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var coolUntil = 10_000L
        val w = worker(calls, cooldown = { (coolUntil - testScheduler.currentTime).coerceAtLeast(0L) }) { ok }
        w.start()
        w.enqueue("a", 0, subtitleId = 1, generation = 1)
        runCurrent()
        w.enqueue("b", 1, subtitleId = 2, generation = 1)
        runCurrent()
        w.enqueue("c", 2, subtitleId = 3, generation = 1)
        runCurrent()
        // Nothing reached the provider and nothing was reported as an error.
        assertEquals(emptyList<String>(), calls.toList())
        val held = w.currentStatus()
        assertEquals(RequestState.RATE_LIMITED, held.state)
        assertTrue(held.cooldownUntilMillis > testScheduler.currentTime)
        assertEquals(null, held.lastError)
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(listOf("c"), calls.toList())
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test fun `a held line that left the screen is not sent and the waiting state ends`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        var relevant = true
        val w = worker(calls, relevant = { relevant }, cooldown = { (5_000L - testScheduler.currentTime).coerceAtLeast(0L) }) { ok }
        w.start()
        w.enqueue("gone", 0, subtitleId = 1, generation = 1)
        runCurrent()
        relevant = false
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(emptyList<String>(), calls.toList())
        assertEquals(-1L, w.currentStatus().cooldownUntilMillis)
        w.stop()
    }

    @Test fun `an unrepresentable wait is not held, it keeps the visible failure path`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls, cooldown = { Long.MAX_VALUE }) { throw QuotaExceededException("blocked", fromCooldown = true, retryAfterMs = Long.MAX_VALUE) }
        w.start()
        w.enqueue("line", 0, subtitleId = 1, generation = 1)
        runCurrent()
        assertEquals(listOf("line"), calls.toList())
        w.stop()
    }
}

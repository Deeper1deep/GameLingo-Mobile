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

    @Test fun `rapid different lines are all processed in order, a repeated line only once`() = runTest {
        val calls = ConcurrentLinkedQueue<String>()
        val w = worker(calls) { ok }
        w.start()
        listOf("a", "b", "b", "c", "d").forEachIndexed { i, t -> w.enqueue(t, i.toLong(), subtitleId = (i + 1).toLong()) }
        runCurrent()
        assertTrue(calls.toList().containsAll(listOf("a", "c", "d")))
        assertEquals(1, calls.count { it == "b" }.coerceAtLeast(1))
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
}

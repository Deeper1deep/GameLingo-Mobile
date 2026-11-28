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

/** Batching worker: nothing is sent during a wait, nothing is dropped silently, backlog is bounded and counted. */
class BatchWorkerTest {

    private val ok = ProcessorOutcome(engineId = "gemini-flash-lite", usedLocalFallback = false)

    private fun TestScope.worker(
        singles: ConcurrentLinkedQueue<String>,
        batches: ConcurrentLinkedQueue<List<String>>,
        cooldown: () -> Long = { 0L },
        recent: () -> Int = { 0 },
        capacity: Int = 40,
        maxAge: Long = 180_000L,
        lost: ConcurrentLinkedQueue<Pair<String, String>> = ConcurrentLinkedQueue(),
        batch: suspend (List<QueuedRequest>) -> BatchResult = { BatchResult(ok) },
    ) = TranslationWorker(
        onLost = { r, reason -> lost.add(r.text to reason) },
        processor = { singles.add(it.text); ok },
        batchProcessor = { batches.add(it.map { r -> r.text }); batch(it) },
        queueCapacity = capacity,
        maxQueueAgeMs = maxAge,
        dedupWindowMs = 0L,
        dispatcher = StandardTestDispatcher(testScheduler),
        now = { testScheduler.currentTime },
        logger = { },
        enableWatchdog = false,
        cooldownRemainingMs = cooldown,
        recentOnlineRequests = recent,
    )

    @Test fun `a wait sends nothing and drops nothing, then all lines go in one request in order`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val w = worker(singles, batches, cooldown = { (10_000L - testScheduler.currentTime).coerceAtLeast(0L) })
        w.start()
        listOf("a", "b", "c").forEachIndexed { i, t -> w.enqueue(t, i.toLong(), subtitleId = i + 1L, generation = 1) }
        runCurrent()
        assertTrue(singles.isEmpty() && batches.isEmpty())
        assertEquals(RequestState.RATE_LIMITED, w.currentStatus().state)
        assertEquals(0, w.currentStatus().lostLines)
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(listOf(listOf("a", "b", "c")), batches.toList())
        assertTrue(singles.isEmpty())
        assertEquals(1, w.currentStatus().batchesSent)
        assertEquals(3, w.currentStatus().batchedLines)
        w.stop()
    }

    @Test fun `a single waiting line uses the normal one line path`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val w = worker(singles, batches)
        w.start()
        w.enqueue("only", 0, subtitleId = 1, generation = 1)
        runCurrent()
        assertEquals(listOf("only"), singles.toList())
        assertTrue(batches.isEmpty())
        w.stop()
    }

    @Test fun `a rate limited batch is requeued in order and sent again after the wait`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        var limited = true
        var until = 0L
        val w = worker(singles, batches, cooldown = { (until - testScheduler.currentTime).coerceAtLeast(0L) }) { lines ->
            if (limited) {
                until = testScheduler.currentTime + 10_000L
                BatchResult(null, retry = lines, error = QuotaExceededException("429", retryAfterMs = 10_000L))
            } else {
                BatchResult(ok)
            }
        }
        w.start()
        listOf("a", "b").forEachIndexed { i, t -> w.enqueue(t, i.toLong(), subtitleId = i + 1L, generation = 1) }
        runCurrent()
        assertEquals(1, batches.size)
        limited = false
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b")), batches.toList())
        assertEquals(0, w.currentStatus().lostLines)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test fun `retries are bounded and the lines given up on are counted`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val lost = ConcurrentLinkedQueue<Pair<String, String>>()
        val w = worker(singles, batches, lost = lost) { lines ->
            BatchResult(null, retry = lines, error = QuotaExceededException("429", retryAfterMs = 5_000L))
        }
        w.start()
        listOf("a", "b").forEachIndexed { i, t -> w.enqueue(t, i.toLong(), subtitleId = i + 1L, generation = 1) }
        repeat(20) { advanceTimeBy(6_000L); runCurrent() }
        assertTrue("bounded: ${batches.size}", batches.size <= 6)
        assertEquals(2, w.currentStatus().lostLines)
        assertEquals(setOf("a" to "retries", "b" to "retries"), lost.toSet())
        w.stop()
    }

    @Test fun `overflow beyond capacity drops the oldest and counts it`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val lost = ConcurrentLinkedQueue<Pair<String, String>>()
        val w = worker(singles, batches, capacity = 3, cooldown = { 100_000L }, lost = lost)
        w.start()
        (1..6).forEach { w.enqueue("l$it", it.toLong(), subtitleId = it.toLong(), generation = 1) }
        runCurrent()
        // The originals of the dropped lines are handed over, with the reason, in drop order.
        assertTrue(lost.toList().take(3).all { it.second == "overflow" })
        assertEquals(listOf("l1", "l2", "l3").take(lost.size.coerceAtMost(3)), lost.toList().take(3).map { it.first })
        assertTrue("lost=${w.currentStatus().lostLines}", w.currentStatus().lostLines >= 3)
        w.stop()
    }

    @Test fun `a line older than the max age is dropped and counted, not silent`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        var until = 100_000L
        val lost = ConcurrentLinkedQueue<Pair<String, String>>()
        val w = worker(singles, batches, maxAge = 30_000L, lost = lost, cooldown = { (until - testScheduler.currentTime).coerceAtLeast(0L) })
        w.start()
        w.enqueue("old", 0, subtitleId = 1, generation = 1)
        runCurrent()
        advanceTimeBy(120_000L)
        runCurrent()
        assertTrue(singles.isEmpty() && batches.isEmpty())
        assertEquals(1, w.currentStatus().lostLines)
        assertEquals(listOf("old" to "expired"), lost.toList())
        w.stop()
    }

    @Test fun `waiting lines are handed over as originals when the session stops`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val lost = ConcurrentLinkedQueue<Pair<String, String>>()
        val w = worker(singles, batches, cooldown = { 100_000L }, lost = lost)
        w.start()
        listOf("a", "b").forEachIndexed { i, t -> w.enqueue(t, i.toLong(), subtitleId = i + 1L, generation = 1) }
        runCurrent()
        w.stop()
        assertEquals(setOf("a" to "stopped", "b" to "stopped"), lost.toSet())
        assertEquals("a stop is deliberate: no loss counter", 0, w.currentStatus().lostLines)
    }

    @Test fun `close to the request budget lines are gathered first, only a delay`() = runTest {
        val singles = ConcurrentLinkedQueue<String>()
        val batches = ConcurrentLinkedQueue<List<String>>()
        val w = worker(singles, batches, recent = { 10 })
        w.start()
        w.enqueue("a", 0, subtitleId = 1, generation = 1)
        runCurrent()
        w.enqueue("b", 1, subtitleId = 2, generation = 1)
        runCurrent()
        assertTrue(singles.isEmpty() && batches.isEmpty())
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(listOf(listOf("a", "b")), batches.toList())
        w.stop()
    }
}

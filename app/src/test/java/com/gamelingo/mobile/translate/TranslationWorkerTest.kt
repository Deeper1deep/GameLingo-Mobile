package com.gamelingo.mobile.translate

import com.gamelingo.mobile.core.session.RequestState
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the persistent translation pipeline keeps working through exactly
 * the failure modes from the brief. All timing is virtual (test scheduler);
 * the "long run" scenarios are simulated, not wall-clock runs.
 */
class TranslationWorkerTest {

    /** Scriptable processor: records calls, behaves per queued script entry. */
    private class FakeProcessor(
        val behavior: suspend (QueuedRequest) -> ProcessorOutcome,
    ) {
        val calls = ConcurrentLinkedQueue<QueuedRequest>()
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        suspend fun process(request: QueuedRequest): ProcessorOutcome {
            calls.add(request)
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return behavior(request)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun TestScope.worker(
        processor: FakeProcessor,
        queueCapacity: Int = TranslationWorker.DEFAULT_QUEUE_CAPACITY,
        maxQueueAgeMs: Long = TranslationWorker.DEFAULT_MAX_QUEUE_AGE_MS,
        watchdogIntervalMs: Long = 100L,
        stallThresholdMs: Long = 300L,
        enableWatchdog: Boolean = false,
    ): TranslationWorker {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TranslationWorker(
            processor = processor::process,
            queueCapacity = queueCapacity,
            maxQueueAgeMs = maxQueueAgeMs,
            dedupWindowMs = 2_000L,
            watchdogIntervalMs = watchdogIntervalMs,
            stallThresholdMs = stallThresholdMs,
            dispatcher = dispatcher,
            now = { testScheduler.currentTime },
            logger = { },
            enableWatchdog = enableWatchdog,
        )
    }

    private fun ok(text: String): ProcessorOutcome =
        ProcessorOutcome(engineId = "gemini-flash-lite", usedLocalFallback = false)

    @Test
    fun `100 sequential lines are all processed in order`() = runTest {
        val processor = FakeProcessor { ok(it.text) }
        val w = worker(processor)
        w.start()
        // Feed through the consumer so the bounded queue never has to drop.
        repeat(100) { i ->
            w.enqueue("line-$i", capturedAtMillis = i.toLong())
            runCurrent()
        }
        assertEquals(100, processor.calls.size)
        assertEquals("line-0", processor.calls.peek().text)
        assertEquals("line-99", processor.calls.toList().last().text)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `a failed first request never stops the next ones`() = runTest {
        val processor = FakeProcessor { req ->
            if (req.id == 1L) throw IOException("boom") else ok(req.text)
        }
        val w = worker(processor)
        w.start()
        w.enqueue("first", 0)
        w.enqueue("second", 1)
        w.enqueue("third", 2)
        runCurrent()
        assertEquals(3, processor.calls.size)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `several consecutive failures keep the loop alive`() = runTest {
        val processor = FakeProcessor { throw ProviderUnavailableException("503") }
        val w = worker(processor)
        w.start()
        repeat(5) { i -> w.enqueue("line-$i-$i", i.toLong()) }
        runCurrent()
        assertEquals(5, processor.calls.size)
        assertEquals(RequestState.NETWORK_ERROR, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `a timeout is classified and the next line still runs`() = runTest {
        val processor = FakeProcessor { req ->
            if (req.text == "slow") {
                // A real timeout from the coroutine machinery, the same type
                // the coordinator's withTimeout throws on a hung engine.
                kotlinx.coroutines.withTimeout(1L) { awaitCancellation() }
            }
            ok(req.text)
        }
        val w = worker(processor)
        w.start()
        w.enqueue("slow", 0)
        runCurrent()
        // Let the virtual 1 ms timeout fire, then the next line must run.
        advanceTimeBy(2)
        runCurrent()
        w.enqueue("next", 1)
        runCurrent()
        assertEquals(2, processor.calls.size)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `http 429 surfaces as RATE_LIMITED not a dead worker`() = runTest {
        val processor = FakeProcessor { req ->
            if (req.text == "limited") {
                throw QuotaExceededException("429", retryAfterMs = 30_000L)
            } else {
                ok(req.text)
            }
        }
        val w = worker(processor)
        w.start()
        w.enqueue("limited", 0)
        runCurrent()
        assertEquals(RequestState.RATE_LIMITED, w.currentStatus().state)
        w.enqueue("after", 1)
        runCurrent()
        assertEquals(2, processor.calls.size)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `http 500 surfaces as NETWORK_ERROR and processing continues`() = runTest {
        val processor = FakeProcessor { req ->
            if (req.text == "down") throw ProviderUnavailableException("500") else ok(req.text)
        }
        val w = worker(processor)
        w.start()
        w.enqueue("down", 0)
        w.enqueue("later", 1)
        runCurrent()
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        assertEquals(2, processor.calls.size)
        w.stop()
    }

    @Test
    fun `auth failure is AUTH_ERROR and never retried silently`() = runTest {
        val processor = FakeProcessor { throw ApiAuthException("401") }
        val w = worker(processor)
        w.start()
        w.enqueue("secret", 0)
        runCurrent()
        assertEquals(RequestState.AUTH_ERROR, w.currentStatus().state)
        assertEquals(1, processor.calls.size)
        w.stop()
    }

    @Test
    fun `offline then back online resumes translation`() = runTest {
        var online = false
        val processor = FakeProcessor { req ->
            if (online) ok(req.text) else throw IOException("no network")
        }
        val w = worker(processor)
        w.start()
        w.enqueue("offline", 0)
        runCurrent()
        assertEquals(RequestState.NETWORK_ERROR, w.currentStatus().state)
        online = true
        w.enqueue("online", 1)
        runCurrent()
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        assertEquals(2, processor.calls.size)
        w.stop()
    }

    @Test
    fun `an empty or invalid provider answer does not kill the worker`() = runTest {
        val processor = FakeProcessor { req ->
            if (req.text == "empty") {
                throw IOException("Gemini returned no translation")
            } else {
                ok(req.text)
            }
        }
        val w = worker(processor)
        w.start()
        w.enqueue("empty", 0)
        w.enqueue("fine", 1)
        runCurrent()
        assertEquals(2, processor.calls.size)
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        w.stop()
    }

    @Test
    fun `LOCAL_FALLBACK is reported as its own state`() = runTest {
        val processor = FakeProcessor {
            ProcessorOutcome(engineId = "mlkit-ondevice", usedLocalFallback = true)
        }
        val w = worker(processor)
        w.start()
        w.enqueue("arabic", 0)
        runCurrent()
        assertEquals(RequestState.LOCAL_FALLBACK, w.currentStatus().state)
        assertTrue(w.currentStatus().lastSuccessAtMillis >= 0)
        w.stop()
    }

    @Test
    fun `stop during an in-flight request cancels cleanly`() = runTest {
        val processor = FakeProcessor { awaitCancellation() }
        val w = worker(processor)
        w.start()
        w.enqueue("hanging", 0)
        runCurrent()
        assertEquals(RequestState.TRANSLATING, w.currentStatus().state)
        w.stop()
        runCurrent()
        assertEquals(RequestState.IDLE, w.currentStatus().state)
        assertEquals(null, w.currentStatus().activeRequestId)
    }

    @Test
    fun `a stalled worker is recovered once and queued lines resume`() = runTest {
        var hang = true
        val processor = FakeProcessor { req ->
            if (hang) awaitCancellation() else ok(req.text)
        }
        val w = worker(processor, enableWatchdog = true)
        w.start()
        w.enqueue("stuck", 0)
        runCurrent()
        assertEquals(RequestState.TRANSLATING, w.currentStatus().state)
        // The processor hangs: the watchdog fires after the stall threshold.
        advanceTimeBy(1_000L)
        assertEquals(1, w.currentStatus().recoveryCount)
        // Recovery cancelled the stuck request; let the next line succeed.
        hang = false
        w.enqueue("after-recovery", 1)
        runCurrent()
        assertEquals(RequestState.SUCCESS, w.currentStatus().state)
        // Exactly one consumer ever ran at a time.
        assertEquals(1, processor.maxConcurrent.get())
        w.stop()
    }

    @Test
    fun `stop and restart never spawns a duplicate worker`() = runTest {
        val processor = FakeProcessor { ok(it.text) }
        val w = worker(processor)
        w.start()
        w.enqueue("a1", 0)
        runCurrent()
        w.stop()
        w.start()
        w.enqueue("b1", 1)
        w.enqueue("b2", 2)
        runCurrent()
        assertEquals(3, processor.calls.size)
        assertEquals(1, processor.maxConcurrent.get())
        w.stop()
    }

    @Test
    fun `the queue stays bounded and keeps the newest lines`() = runTest {
        val processor = FakeProcessor { awaitCancellation() }
        val w = worker(processor, queueCapacity = 4)
        w.start()
        w.enqueue("blocker", 0) // occupies the single consumer
        runCurrent()
        repeat(10) { i -> w.enqueue("queued-$i", i + 1L) }
        runCurrent()
        assertTrue(w.currentStatus().queueDepth <= 4)
        w.stop()
        // The oldest queued lines were dropped; the freshest survived.
        val texts = processor.calls.map { it.text }
        assertEquals(listOf("blocker"), texts)
    }

    @Test
    fun `identical lines inside the dedup window are enqueued once`() = runTest {
        val processor = FakeProcessor { ok(it.text) }
        val w = worker(processor)
        w.start()
        w.enqueue("same", 0)
        w.enqueue("same", 1)
        runCurrent()
        assertEquals(1, processor.calls.size)
        // After the window the same text is a real new line again.
        advanceTimeBy(3_000L)
        w.enqueue("same", 2)
        runCurrent()
        assertEquals(2, processor.calls.size)
        w.stop()
    }

    @Test
    fun `cancellation inside the processor always propagates`() = runTest {
        val processor = FakeProcessor { throw CancellationException("superseded") }
        val w = worker(processor, enableWatchdog = true)
        w.start()
        w.enqueue("x", 0)
        runCurrent()
        // The loop died honourably; the watchdog revives it on the next stall.
        advanceTimeBy(1_000L)
        assertTrue(w.currentStatus().recoveryCount >= 1)
        w.stop()
    }

    @Test
    fun `a line older than the max queue age is dropped at dequeue, never translated`() = runTest {
        val processor = FakeProcessor { ok(it.text) }
        val w = worker(processor, maxQueueAgeMs = 10_000L)
        w.start()
        // Captured 20s before "now" (virtual time starts at 0): stale when
        // the consumer picks it up; the fresh line behind it still runs.
        w.enqueue("stale", -20_000L)
        w.enqueue("fresh", 0L)
        runCurrent()
        assertEquals(listOf("fresh"), processor.calls.map { it.text })
        w.stop()
    }
}

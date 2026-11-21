package com.gamelingo.mobile.translate

import android.util.Log
import com.gamelingo.mobile.core.session.RequestState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.session.WorkerStatus
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel

/** One subtitle line waiting for or undergoing translation. */
data class QueuedRequest(
    val id: Long,
    val text: String,
    val capturedAtMillis: Long,
    val enqueuedAtMillis: Long,
)

/** What the per-request processor reports back to the worker. */
data class ProcessorOutcome(
    val engineId: String,
    val usedLocalFallback: Boolean,
)

/**
 * The persistent translation worker: one bounded queue, exactly one consumer
 * coroutine, explicit per-request states, and a watchdog that recovers a
 * stalled pipeline without touching capture, OCR or the overlay.
 *
 * Design rules (from the persistent-pipeline brief):
 * - A failed request never stops the loop: the failure is classified into a
 *   [RequestState], reported, and the next queued line is processed.
 * - The queue is a mutex-guarded deque bounded at [queueCapacity]; when
 *   full, the OLDEST line is dropped so a stale subtitle never blocks a
 *   fresh one, and identical lines inside [dedupWindowMs] are enqueued
 *   once. A plain deque plus a short poll interval is used instead of a
 *   Channel on purpose: a channel handoff cancelled mid-resume can lose
 *   the element and strand the pipeline, which is exactly the failure
 *   class this worker exists to survive.
 * - The watchdog notices "queue non-empty but no progress" and restarts
 *   the consumer exactly once per stall; it never spawns a second
 *   consumer, and a request stuck in the processor is dropped (logged)
 *   rather than re-run, so a poison line cannot loop the recovery.
 * - No API keys or full texts are logged: Request ID, lengths, states and
 *   millisecond timings only.
 */
class TranslationWorker(
    private val processor: suspend (QueuedRequest) -> ProcessorOutcome,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
    private val watchdogIntervalMs: Long = DEFAULT_WATCHDOG_INTERVAL_MS,
    private val stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS,
    private val maxQueueAgeMs: Long = DEFAULT_MAX_QUEUE_AGE_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
    // Tests drive virtual time: an infinite watchdog loop under a test
    // scheduler never lets the scheduler idle, so they disable it.
    private val enableWatchdog: Boolean = true,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // A plain lock-guarded deque holds the data; the conflated channel is
    // only a wakeup signal and carries nothing, so a cancelled receive can
    // never lose a request. This is deliberately NOT a data channel: a
    // channel handoff cancelled mid-resume can lose the element and strand
    // the pipeline - exactly the failure this worker exists to survive.
    private val queueLock = Any()
    private val queue = ArrayDeque<QueuedRequest>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private val running = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val nextRequestId = AtomicLong(0)

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    /** The request the consumer is currently translating, if any. */
    @Volatile
    private var activeRequest: QueuedRequest? = null

    @Volatile
    private var status = WorkerStatus()

    @Volatile
    private var lastProgressAtMillis: Long = -1

    @Volatile
    private var lastEnqueuedText: String? = null

    @Volatile
    private var lastEnqueuedAtMillis: Long = -1

    /** Starts the consumer and the watchdog. Idempotent: never two workers. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastProgressAtMillis = now()
        workerJob = scope.launch { consumeLoop() }
        if (enableWatchdog) {
            watchdogJob = scope.launch { watchdogLoop() }
        }
        log("worker started")
    }

    /**
     * Stops the consumer and watchdog and drains the queue. The session is
     * over: queued lines belong to it and must not leak into the next one.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerJob?.cancel()
        watchdogJob?.cancel()
        workerJob = null
        watchdogJob = null
        activeRequest = null
        synchronized(queueLock) { queue.clear() }
        lastEnqueuedText = null
        updateStatus(RequestState.IDLE, activeRequestId = null)
        log("worker stopped")
    }

    /**
     * Enqueues a freshly accepted OCR line. Identical text inside the dedup
     * window is skipped; a full queue drops its oldest line, never the new
     * one. Safe to call from any thread; never blocks the caller.
     */
    fun enqueue(text: String, capturedAtMillis: Long) {
        if (!running.get()) return
        val at = now()
        if (text == lastEnqueuedText && at - lastEnqueuedAtMillis < dedupWindowMs) {
            return
        }
        val request = QueuedRequest(
            id = nextRequestId.incrementAndGet(),
            text = text,
            capturedAtMillis = capturedAtMillis,
            enqueuedAtMillis = at,
        )
        synchronized(queueLock) {
            if (queue.size >= queueCapacity) {
                // Full: the oldest queued line is the most stale one.
                // Drop it so the fresh subtitle gets in.
                val dropped = queue.pollFirst()
                log("queue full: dropped #${dropped?.id}, enqueued #${request.id}")
            }
            queue.addLast(request)
        }
        wakeup.trySend(Unit)
        log("enqueued #${request.id} len=${text.length} depth=${queueSize()}")
        lastEnqueuedText = text
        lastEnqueuedAtMillis = at
        updateStatus(RequestState.QUEUED, activeRequestId = activeRequest?.id)
    }

    fun currentStatus(): WorkerStatus = status

    private suspend fun consumeLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val request = synchronized(queueLock) { queue.pollFirst() }
                if (request != null && now() - request.capturedAtMillis > maxQueueAgeMs) {
                    // Max Queue Age: a subtitle this old is off screen by the
                    // time its translation lands. Drop it and take the next
                    // one; translating it would show stale text over new
                    // dialogue.
                    log("dropped #${request.id}: older than maxQueueAgeMs=$maxQueueAgeMs")
                    continue
                }
                if (request == null) {
                    // Wait for the next enqueue's wakeup. The deque is
                    // re-checked before every wait, so a lost or conflated
                    // wakeup can never strand a queued line.
                    wakeup.receive()
                    continue
                }
                activeRequest = request
                lastProgressAtMillis = now()
                updateStatus(RequestState.TRANSLATING, activeRequestId = request.id)
                log(
                    "started #${request.id} waited=${now() - request.enqueuedAtMillis}ms " +
                        "depth=${queueSize()}",
                )
                val startedAt = now()
                try {
                    val outcome = processor(request)
                    lastProgressAtMillis = now()
                    val state = if (outcome.usedLocalFallback) {
                        RequestState.LOCAL_FALLBACK
                    } else {
                        RequestState.SUCCESS
                    }
                    log(
                        "finished #${request.id} state=$state engine=${outcome.engineId} " +
                            "took=${now() - startedAt}ms depth=${queueSize()}",
                    )
                    updateStatus(state, activeRequestId = null, clearError = true)
                } catch (timeout: TimeoutCancellationException) {
                    // A timeout is a failed request, not a cancellation:
                    // classify it and keep the loop alive. Checked before
                    // CancellationException because it subclasses it.
                    lastProgressAtMillis = now()
                    log("failed #${request.id} state=TIMEOUT took=${now() - startedAt}ms")
                    updateStatus(
                        RequestState.TIMEOUT,
                        activeRequestId = null,
                        error = "TimeoutCancellationException",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastProgressAtMillis = now()
                    val state = classify(error)
                    // One failed request changes nothing about the loop: the
                    // next queued line is processed right after.
                    log(
                        "failed #${request.id} state=$state error=${error.javaClass.simpleName} " +
                            "took=${now() - startedAt}ms depth=${queueSize()}",
                    )
                    updateStatus(state, activeRequestId = null, error = error.javaClass.simpleName)
                } finally {
                    activeRequest = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The loop itself broke (processor threw an Error, deque broken):
            // the watchdog sees no progress and recovers the worker.
            log("worker loop crashed: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(watchdogIntervalMs)
            val pending = activeRequest != null || queueSize() > 0
            val silentFor = now() - lastProgressAtMillis
            // A dead consumer with the worker still running is itself a
            // stall: restart it even when the queue happens to be empty.
            val consumerDead = workerJob?.isActive == false
            when {
                consumerDead -> recoverOnce("consumer coroutine died")
                pending && silentFor > stallThresholdMs ->
                    recoverOnce("no progress for ${silentFor}ms")
            }
        }
    }

    /**
     * Restarts the consumer exactly once per stall. Capture, OCR, the
     * overlay and the queue contents are untouched; only the stalled
     * consumer coroutine is replaced. The request it was stuck on is
     * dropped with a log line: re-running a poison line would loop the
     * recovery forever.
     */
    private suspend fun recoverOnce(reason: String) {
        if (!running.get()) return
        if (!recovering.compareAndSet(false, true)) return
        try {
            log("WATCHDOG RECOVERING: $reason")
            val dropped = activeRequest
            activeRequest = null
            if (dropped != null) {
                log("dropped stuck request #${dropped.id} during recovery")
            }
            // Never run two consumers: a consumer stuck in a non-cancellable
            // native op (ONNX decode) keeps running after cancel(), so the
            // replacement is launched only after the old one has actually
            // finished. If it will not finish, the engine is wedged: stop
            // the worker and surface it instead of splitting the engine.
            val oldJob = workerJob
            workerJob = null
            val joined = withTimeoutOrNull(RECOVERY_JOIN_TIMEOUT_MS) {
                oldJob?.cancelAndJoin()
            }
            if (oldJob != null && joined == null) {
                log("consumer did not stop within ${RECOVERY_JOIN_TIMEOUT_MS}ms; engine wedged, worker stopped")
                running.set(false)
                synchronized(queueLock) { queue.clear() }
                updateStatus(
                    RequestState.ENGINE_STUCK,
                    activeRequestId = null,
                    error = "consumer_wedged",
                )
                return
            }
            workerJob = scope.launch { consumeLoop() }
            lastProgressAtMillis = now()
            status = status.copy(recoveryCount = status.recoveryCount + 1)
            updateStatus(RequestState.RECOVERING, activeRequestId = null)
            log("recovered: count=${status.recoveryCount} lastSuccess=${status.lastSuccessAtMillis}")
        } finally {
            recovering.set(false)
        }
    }

    private fun classify(error: Exception): RequestState = when (error) {
        is QuotaExceededException -> RequestState.RATE_LIMITED
        is ApiAuthException, is OnlineNotConfiguredException -> RequestState.AUTH_ERROR
        is ProviderUnavailableException, is IOException -> RequestState.NETWORK_ERROR
        is com.gamelingo.mobile.translate.local.LocalAiTranslationEngine.ModelNotInstalledException,
        is com.gamelingo.mobile.translate.local.TranslateGemmaEngine.ModelNotInstalledException,
        -> RequestState.MODEL_MISSING
        is com.gamelingo.mobile.translate.local.LocalAiTranslationEngine.EngineInitException,
        is com.gamelingo.mobile.translate.local.TranslateGemmaEngine.EngineInitException,
        -> RequestState.ENGINE_INIT_FAILED
        is com.gamelingo.mobile.translate.local.LocalAiTranslationEngine.EmptyModelOutputException,
        -> RequestState.EMPTY_RESULT
        else -> RequestState.UNKNOWN
    }

    private fun updateStatus(
        state: RequestState,
        activeRequestId: Long?,
        error: String? = null,
        clearError: Boolean = false,
    ) {
        status = status.copy(
            state = state,
            activeRequestId = activeRequestId,
            queueDepth = queueSize(),
            lastSuccessAtMillis =
                if (state == RequestState.SUCCESS || state == RequestState.LOCAL_FALLBACK) {
                    now()
                } else {
                    status.lastSuccessAtMillis
                },
            lastError = if (clearError) null else error ?: status.lastError,
        )
        TranslationSessionBus.onWorkerStatus(status)
    }

    private fun queueSize(): Int = synchronized(queueLock) { queue.size }

    private fun log(message: String) {
        logger(
            "req[lastSuccess=${status.lastSuccessAtMillis} recoveries=${status.recoveryCount}] $message",
        )
    }

    companion object {
        private const val TAG = "GameLingoWorker"
        const val DEFAULT_QUEUE_CAPACITY = 16
        const val DEFAULT_DEDUP_WINDOW_MS = 2_000L
        const val DEFAULT_WATCHDOG_INTERVAL_MS = 5_000L
        const val DEFAULT_STALL_THRESHOLD_MS = 60_000L

        /** A queued line older than this is dropped at dequeue time. */
        const val DEFAULT_MAX_QUEUE_AGE_MS = 10_000L

        /** How long recovery waits for a cancelled consumer before giving up. */
        const val RECOVERY_JOIN_TIMEOUT_MS = 15_000L
    }
}

package com.gamelingo.mobile.translate

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Every online request goes through one gate, whichever entry point made it
 * (live subtitles, the connection test, any retry path):
 *
 * - at most [maxConcurrent] requests are in flight;
 * - after a rate limit (429) the gate refuses further calls locally, without
 *   touching the network, until the cooldown ends;
 * - the cooldown is the provider's Retry-After (or retry delay) when it gave
 *   one, otherwise exponential backoff with jitter, capped;
 * - a success clears the cooldown and the backoff.
 *
 * Cancellation is never a failure here: a cancelled caller releases its
 * permit and changes nothing.
 */
class OnlineRequestGate(
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /** Injectable for tests. */
    var now: () -> Long = { System.currentTimeMillis() },
    /** Extra random delay in [0, bound]; tests inject { 0 }. */
    var jitter: (Long) -> Long = { bound -> if (bound <= 0L) 0L else (0L..bound).random() },
) {
    private val permits = Semaphore(maxConcurrent)
    private val cooldownUntil = AtomicLong(0L)
    private val consecutive = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)

    /** Highest number of simultaneous requests seen (diagnostics and tests). */
    val peakInFlight = AtomicInteger(0)

    /** Milliseconds until requests are allowed again; 0 when not cooling down. */
    fun remainingMs(): Long = (cooldownUntil.get() - now()).coerceAtLeast(0L)

    /** Epoch millis the cooldown ends at, or 0 when none is active. */
    fun cooldownEndsAtMillis(): Long = if (remainingMs() > 0L) cooldownUntil.get() else 0L

    /** Consecutive rate limits since the last success. */
    fun consecutiveRateLimits(): Int = consecutive.get()

    suspend fun <T> run(block: suspend () -> T): T {
        throwIfCoolingDown()
        return permits.withPermit {
            // A request that waited for a permit must re-check: the call ahead
            // of it may just have been rate limited.
            throwIfCoolingDown()
            val running = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(running) { a, b -> maxOf(a, b) }
            try {
                val result = block()
                consecutive.set(0)
                cooldownUntil.set(0L)
                result
            } catch (quota: QuotaExceededException) {
                if (!quota.fromCooldown) onRateLimited(quota)
                throw quota
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun throwIfCoolingDown() {
        val remaining = remainingMs()
        if (remaining > 0L) {
            throw QuotaExceededException(
                "online requests paused after a rate limit",
                retryAfterMs = remaining,
                fromCooldown = true,
            )
        }
    }

    private fun onRateLimited(quota: QuotaExceededException) {
        val attempt = consecutive.incrementAndGet()
        val hint = quota.retryAfterMs ?: quota.provider.retryDelayMs
        val base = hint ?: backoffMs(attempt)
        val delayMs = base + jitter(base / JITTER_DIVISOR)
        val until = now() + delayMs
        cooldownUntil.accumulateAndGet(until) { a, b -> maxOf(a, b) }
    }

    private fun backoffMs(attempt: Int): Long {
        var value = BASE_BACKOFF_MS
        repeat((attempt - 1).coerceAtLeast(0)) {
            value = minOf(value * 2L, MAX_BACKOFF_MS)
        }
        return minOf(value, MAX_BACKOFF_MS)
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 1
        const val BASE_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_MS = 300_000L
        private const val JITTER_DIVISOR = 10L

        /** The process-wide gate the real engine uses. */
        val shared = OnlineRequestGate()
    }
}

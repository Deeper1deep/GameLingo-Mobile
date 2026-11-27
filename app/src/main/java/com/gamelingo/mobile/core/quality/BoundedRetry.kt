package com.gamelingo.mobile.core.quality

/**
 * One attempt, and at most ONE retry, for a translation whose output looks
 * corrupted. Never a loop: the second result is final, good or not, so a
 * misbehaving model cannot keep the pipeline busy or burn the queue.
 */
object BoundedRetry {

    data class Outcome<R>(
        /** The result of the last attempt made. */
        val value: R,
        /** True when the last attempt passed the corruption check. */
        val accepted: Boolean,
        /** True when the retry ran. */
        val retried: Boolean,
    )

    suspend fun <R> run(
        first: suspend () -> R,
        retry: suspend () -> R,
        isCorrupt: (R) -> Boolean,
    ): Outcome<R> {
        val one = first()
        if (!isCorrupt(one)) return Outcome(one, accepted = true, retried = false)
        val two = retry()
        return Outcome(two, accepted = !isCorrupt(two), retried = true)
    }
}

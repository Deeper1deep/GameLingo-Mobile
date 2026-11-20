package com.gamelingo.mobile.core.capture

/**
 * Counts consecutive frame-processing failures so a persistently broken
 * capture session surfaces as an error instead of spinning silently.
 * Any successful tick resets the counter: transient failures stay invisible.
 */
class FailureTracker(
    private val maxConsecutiveFailures: Int = 5,
) {
    private var consecutive: Int = 0

    /** @return true exactly when the consecutive-failure threshold is reached. */
    fun onFailure(): Boolean {
        consecutive += 1
        return consecutive >= maxConsecutiveFailures
    }

    fun onSuccess() {
        consecutive = 0
    }

    fun reset() {
        consecutive = 0
    }
}

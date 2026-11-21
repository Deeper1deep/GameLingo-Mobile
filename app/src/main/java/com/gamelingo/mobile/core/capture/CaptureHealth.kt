package com.gamelingo.mobile.core.capture

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local liveness record for the capture pipeline. The capture loop
 * stamps every stage here; the watchdog and the diagnostics page read it to
 * tell "running but quiet" apart from "starved" (battery saver skipping OCR,
 * frames never arriving, or the loop stuck). No frame data ever lands here,
 * only timestamps and short status strings.
 */
object CaptureHealth {

    private val sessionStartedAt = AtomicLong(0)
    private val lastFrameAcquiredAt = AtomicLong(0)
    private val lastOcrRunAt = AtomicLong(0)
    private val lastOcrSkippedAt = AtomicLong(0)
    private val lastLinePublishedAt = AtomicLong(0)
    private val lastDisplayRebuildAt = AtomicLong(0)
    private val lastErrorAt = AtomicLong(0)
    private val lastStopAt = AtomicLong(0)

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastStopReason: String? = null
        private set

    fun onSessionStarted(now: Long = System.currentTimeMillis()) {
        sessionStartedAt.set(now)
        lastFrameAcquiredAt.set(0)
        lastOcrRunAt.set(0)
        lastOcrSkippedAt.set(0)
        lastLinePublishedAt.set(0)
        lastDisplayRebuildAt.set(0)
        lastErrorAt.set(0)
        lastError = null
        lastStopAt.set(0)
        lastStopReason = null
    }

    fun onFrameAcquired(now: Long = System.currentTimeMillis()) = lastFrameAcquiredAt.set(now)
    fun onOcrRun(now: Long = System.currentTimeMillis()) = lastOcrRunAt.set(now)
    fun onOcrSkipped(now: Long = System.currentTimeMillis()) = lastOcrSkippedAt.set(now)
    fun onLinePublished(now: Long = System.currentTimeMillis()) = lastLinePublishedAt.set(now)
    fun onDisplayRebuilt(now: Long = System.currentTimeMillis()) = lastDisplayRebuildAt.set(now)

    fun onError(error: String, now: Long = System.currentTimeMillis()) {
        lastError = error
        lastErrorAt.set(now)
    }

    /**
     * Why the session last stopped, stamped by the capture service on every
     * teardown path (user button, projection ended, persistent failure,
     * service destroyed). A clean STOPPED with no error is impossible to
     * diagnose without this: the diagnostics page shows it verbatim.
     */
    fun onSessionStopped(reason: String, now: Long = System.currentTimeMillis()) {
        lastStopReason = reason
        lastStopAt.set(now)
    }

    /** Milliseconds since the last event of each stage; null when it never happened. */
    data class Snapshot(
        val sessionStartedAt: Long,
        val lastFrameAcquiredAt: Long,
        val lastOcrRunAt: Long,
        val lastOcrSkippedAt: Long,
        val lastLinePublishedAt: Long,
        val lastDisplayRebuildAt: Long,
        val lastError: String?,
        val lastErrorAt: Long,
        val lastStopReason: String?,
        val lastStopAt: Long,
    )

    fun snapshot(): Snapshot = Snapshot(
        sessionStartedAt = sessionStartedAt.get(),
        lastFrameAcquiredAt = lastFrameAcquiredAt.get(),
        lastOcrRunAt = lastOcrRunAt.get(),
        lastOcrSkippedAt = lastOcrSkippedAt.get(),
        lastLinePublishedAt = lastLinePublishedAt.get(),
        lastDisplayRebuildAt = lastDisplayRebuildAt.get(),
        lastError = lastError,
        lastErrorAt = lastErrorAt.get(),
        lastStopReason = lastStopReason,
        lastStopAt = lastStopAt.get(),
    )

    /** Stop-reason keys recorded by the capture service; the UI maps them to Arabic labels. */
    object StopReasons {
        const val USER_BUTTON = "user_button"
        const val PROJECTION_ENDED = "projection_ended"
        const val SERVICE_DESTROYED = "service_destroyed"
        const val MISSING_CONSENT_DATA = "missing_consent_data"
        const val FOREGROUND_START_FAILED = "foreground_start_failed"
        const val PROJECTION_REJECTED = "projection_rejected"
        const val SETTINGS_UNAVAILABLE = "settings_unavailable"
        const val CAPTURE_PERSISTENT_FAILURE = "capture_persistent_failure"
    }
}

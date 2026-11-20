package com.gamelingo.mobile.core.timing

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight per-stage latency measurements across the translation
 * pipeline: frame capture, change detection, OCR, line publish, engine wait,
 * and overlay display. Every mark is a single no-op when [enabled] is false,
 * which is the case in release builds: GameLingoApp flips it on only for
 * DEBUG. Never logs API keys, frame content or full texts; only durations
 * and short line lengths.
 */
object PipelineTiming {

    @Volatile
    var enabled: Boolean = false

    private val lastEndToEndMs = AtomicLong(-1)

    /** Records one stage duration in milliseconds. */
    fun mark(stage: String, durationMs: Long, detail: String = "") {
        if (!enabled) return
        Log.d(TAG, "$stage=${durationMs}ms${if (detail.isEmpty()) "" else " ($detail)"}")
    }

    /** Measures [block] and records it under [stage] when enabled. */
    inline fun <T> timed(stage: String, detail: String = "", block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        val result = block()
        mark(stage, (System.nanoTime() - start) / NANOS_PER_MS, detail)
        return result
    }

    /**
     * Records the end-to-end latency from the moment a line was captured on
     * screen until its translation reached this point (engine done, or
     * overlay shown). [capturedAtMillis] travels with the line on the
     * session bus, so no clock skew: same device, same clock.
     */
    fun endToEnd(stage: String, capturedAtMillis: Long) {
        if (!enabled) return
        val total = System.currentTimeMillis() - capturedAtMillis
        lastEndToEndMs.set(total)
        Log.d(TAG, "$stage total=${total}ms")
    }

    /** Last recorded capture-to-display latency, or -1. Visible for diagnostics. */
    fun lastEndToEndMs(): Long = lastEndToEndMs.get()

    private const val TAG = "GameLingoTiming"
    @PublishedApi internal const val NANOS_PER_MS = 1_000_000L
}

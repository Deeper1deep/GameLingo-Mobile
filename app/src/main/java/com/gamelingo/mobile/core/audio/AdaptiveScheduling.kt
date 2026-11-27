package com.gamelingo.mobile.core.audio

/**
 * Live activity the audio side reports so Smart Auto can pace screen capture. Process-wide and
 * metadata only (a flag and a timestamp, no text).
 */
object SourceActivity {
    /** True only while speech translation is running under the Smart Auto audio profile. */
    @Volatile var smartAuto: Boolean = false
    /** Wall-clock time of the latest finished utterance, 0 when none yet. */
    @Volatile var lastAudioAtMillis: Long = 0L

    fun reset() { smartAuto = false; lastAudioAtMillis = 0L }
}

/**
 * Smart Auto pacing of the screen OCR loop from live activity. It only ever SLOWS the configured
 * capture interval, never speeds it up, never above [MAX_FACTOR] times, and never switches any
 * feature off. Audio runs on its own coroutine with its own bounded drop-oldest frame queue, so
 * OCR load cannot block it; this policy additionally eases the OCR load. The thresholds below are
 * design choices, UNMEASURED on a device.
 */
object AdaptiveScheduling {
    const val AUDIO_ACTIVE_WINDOW_MS = 5_000L
    const val OCR_QUIET_MS = 10_000L
    const val QUEUE_BACKPRESSURE = 3
    const val BACKPRESSURE_FACTOR = 1.5
    const val MAX_FACTOR = 2.0

    fun captureIntervalMs(
        baseMs: Long,
        smartAuto: Boolean,
        nowMs: Long,
        lastAudioAtMs: Long,
        lastOcrAtMs: Long,
        queueDepth: Int,
    ): Long {
        if (!smartAuto) return baseMs
        var factor = 1.0
        // The translation queue is backing up: feed it less new screen text.
        if (queueDepth >= QUEUE_BACKPRESSURE) factor = BACKPRESSURE_FACTOR
        // Speech is the active source and the screen has been quiet: favour audio.
        val audioActive = lastAudioAtMs > 0 && nowMs - lastAudioAtMs <= AUDIO_ACTIVE_WINDOW_MS
        val ocrQuiet = lastOcrAtMs <= 0 || nowMs - lastOcrAtMs > OCR_QUIET_MS
        if (audioActive && ocrQuiet) factor = MAX_FACTOR
        return (baseMs * factor.coerceAtMost(MAX_FACTOR)).toLong()
    }
}

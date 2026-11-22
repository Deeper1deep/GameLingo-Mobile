package com.gamelingo.mobile.core.text

/**
 * Translation Visibility Lifecycle: ties the translation on screen to the
 * ORIGINAL text being on screen, instead of to "a request finished".
 *
 * One tracked line at a time (the overlay shows one subtitle):
 *
 *   TRANSLATING  - the line was published to the pipeline, no result shown yet
 *   VISIBLE      - its translation is accepted and shown
 *   DISAPPEARING - the source was not seen in the latest observation(s)
 *   CLEARED      - absence confirmed: the translation must be hidden
 *
 * Absence is confirmed only by more than one signal, so one OCR miss never
 * hides a live subtitle:
 *  - at least [missingFramesRequired] consecutive observations without the line,
 *  - AND at least the grace period since it was last seen. The grace is
 *    [graceMs] but never shorter than the measured OCR cycle times the required
 *    miss count, so a slow device cannot clear on a single slow cycle.
 *
 * A display produces no frames while the screen is static, so after the
 * subtitle vanishes there may be NO further observation. [tick] (a cheap
 * heartbeat, no OCR) confirms such an absence: at least one miss, no newer
 * frame, and the grace elapsed.
 *
 * Staleness: [acceptTranslation] refuses a result whose source is no longer
 * the tracked line (it disappeared, or a newer line replaced it), which is how
 * a slow request finishing after its text vanished is kept off the screen.
 *
 * Pure and clock-free (callers pass the time), so every case is unit-tested.
 */
class TextPresenceTracker(
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val missingFramesRequired: Int = DEFAULT_MISSING_FRAMES,
    private val similarityThreshold: Float = TextStabilityTracker.DEFAULT_SIMILARITY_THRESHOLD,
) {

    enum class State { CLEARED, TRANSLATING, VISIBLE, DISAPPEARING }

    sealed interface Decision {
        data object None : Decision

        /**
         * Hide the translation of subtitle [subtitleId] ([original] is its
         * text); its source is confirmed gone. The id lets the consumer refuse
         * to clear anything newer than the line that actually disappeared.
         */
        data class Clear(val original: String, val subtitleId: Long) : Decision
    }

    private var activeText: String? = null
    private var activeId: Long = NO_SUBTITLE
    private var nextId: Long = 0L
    private var activeKey: String = ""
    private var shown = false
    private var lastSeenAt = 0L
    private var lastVerifiedAt = 0L
    private var missing = 0
    private var lastObservationAt = -1L
    private var cycleMs = 0L

    @get:Synchronized
    val state: State
        get() = when {
            activeText == null -> State.CLEARED
            missing > 0 -> State.DISAPPEARING
            shown -> State.VISIBLE
            else -> State.TRANSLATING
        }

    /** The calibrated grace right now (for diagnostics and tests). */
    @Synchronized
    fun effectiveGraceMs(): Long = effectiveGrace()

    /** Id of the tracked subtitle appearance, or [NO_SUBTITLE]. */
    @get:Synchronized
    val activeSubtitleId: Long
        get() = if (activeText == null) NO_SUBTITLE else activeId

    /** True while [subtitleId] is the tracked appearance (not replaced, not cleared). */
    @Synchronized
    fun isActive(subtitleId: Long): Boolean = activeText != null && activeId == subtitleId

    /**
     * A new subtitle appearance entered the pipeline: it replaces whatever
     * was tracked and gets a fresh id. The id is monotonic for the lifetime of
     * the tracker (never reused after a reset), so the SAME sentence
     * appearing twice is two different appearances and a result for the first
     * can never be taken for the second.
     *
     * @return the new appearance's id.
     */
    @Synchronized
    fun onLinePublished(text: String, nowMs: Long): Long {
        nextId += 1
        activeId = nextId
        activeText = text
        activeKey = TextNormalizer.comparisonKey(text)
        shown = false
        lastSeenAt = nowMs
        missing = 0
        lastVerifiedAt = nowMs
        return activeId
    }

    /** Marks [subtitleId]'s translation as shown. @return false if it is no longer active. */
    @Synchronized
    fun acceptSubtitle(subtitleId: Long): Boolean {
        if (!isActive(subtitleId)) return false
        shown = true
        return true
    }

    /**
     * True when no OCR pass has confirmed the tracked line for [maxAgeMs]:
     * the caller must run a real OCR pass even if the frame looks unchanged
     * (battery saver), because "the frame did not change" is not proof that
     * the text is still there.
     */
    @Synchronized
    fun needsVerification(nowMs: Long, maxAgeMs: Long = VERIFY_INTERVAL_MS): Boolean =
        activeText != null && nowMs - lastVerifiedAt >= maxAgeMs

    /**
     * @return true when [original]'s translation may be shown: its source is
     * still the tracked line. False for a line that already disappeared or was
     * superseded by a newer one.
     */
    @Synchronized
    fun acceptTranslation(original: String): Boolean {
        if (activeText == null) return false
        if (!sameLine(TextNormalizer.comparisonKey(original))) return false
        shown = true
        return true
    }

    /** One OCR pass finished: [text] is what was read (blank when nothing). */
    @Synchronized
    fun observe(text: String, nowMs: Long): Decision {
        updateCycle(nowMs)
        if (activeText == null) return Decision.None
        lastVerifiedAt = nowMs
        val key = TextNormalizer.comparisonKey(text)
        return if (key.isNotEmpty() && sameLine(key)) {
            lastSeenAt = nowMs
            missing = 0
            Decision.None
        } else {
            miss(nowMs)
        }
    }

    /**
     * The frame equals the previous one and OCR was skipped: the screen did
     * not change, so the previous observation still holds (seen stays seen,
     * absent stays absent).
     */
    @Synchronized
    fun onUnchangedFrame(nowMs: Long): Decision {
        updateCycle(nowMs)
        if (activeText == null) return Decision.None
        return if (missing == 0) {
            // An unchanged frame extends "seen" only while a REAL OCR pass
            // confirmed the text recently. Past that, lastSeen stops moving so
            // the absence can still be confirmed (the caller verifies via
            // needsVerification) instead of being masked forever.
            if (nowMs - lastVerifiedAt < VERIFY_INTERVAL_MS) lastSeenAt = nowMs
            Decision.None
        } else {
            miss(nowMs)
        }
    }

    /**
     * Heartbeat with no new frame. Confirms an absence on a static screen:
     * at least one miss, no frame for a short quiet period, grace elapsed.
     */
    @Synchronized
    fun tick(nowMs: Long): Decision {
        if (activeText == null || missing < 1) return Decision.None
        if (nowMs - lastObservationAt < QUIET_MS) return Decision.None
        if (nowMs - lastSeenAt < effectiveGrace()) return Decision.None
        return clear()
    }

    @Synchronized
    fun reset() {
        activeText = null
        activeId = NO_SUBTITLE
        activeKey = ""
        shown = false
        lastSeenAt = 0L
        lastVerifiedAt = 0L
        missing = 0
        lastObservationAt = -1L
        cycleMs = 0L
    }

    private fun miss(nowMs: Long): Decision {
        missing += 1
        return if (missing >= missingFramesRequired && nowMs - lastSeenAt >= effectiveGrace()) {
            clear()
        } else {
            Decision.None
        }
    }

    private fun clear(): Decision {
        val original = activeText ?: return Decision.None
        val id = activeId
        activeText = null
        activeId = NO_SUBTITLE
        activeKey = ""
        shown = false
        missing = 0
        return Decision.Clear(original, id)
    }

    private fun effectiveGrace(): Long =
        maxOf(graceMs, (cycleMs * missingFramesRequired)).coerceAtMost(MAX_GRACE_MS)

    /** Smoothed time between observations = the real OCR cycle on this device. */
    private fun updateCycle(nowMs: Long) {
        val delta = nowMs - lastObservationAt
        if (lastObservationAt >= 0 && delta in 1..MAX_CYCLE_SAMPLE_MS) {
            cycleMs = if (cycleMs == 0L) delta else (cycleMs * 3 + delta) / 4
        }
        lastObservationAt = nowMs
    }

    private fun sameLine(key: String): Boolean {
        if (activeKey.isEmpty() || key.isEmpty()) return false
        if (TextSimilarity.similarity(key, activeKey) >= similarityThreshold) return true
        // OCR may read a longer or shorter slice of the same subtitle.
        val shorter = minOf(key.length, activeKey.length)
        return shorter >= MIN_CONTAINMENT_CHARS &&
            (key.contains(activeKey) || activeKey.contains(key))
    }

    companion object {
        /** "No subtitle id": legacy callers and tests that do not track appearances. */
        const val NO_SUBTITLE = 0L

        /** A real OCR pass must confirm the line at least this often, even on battery saver. */
        const val VERIFY_INTERVAL_MS = 2_000L

        /** Starting point from the brief (800-1200 ms); adapts upward to the OCR cycle. */
        const val DEFAULT_GRACE_MS = 1_000L
        const val DEFAULT_MISSING_FRAMES = 2
        const val MAX_GRACE_MS = 3_000L

        /** Observation gaps longer than this are static-screen gaps, not OCR cycles. */
        private const val MAX_CYCLE_SAMPLE_MS = 5_000L

        /** No frame for this long after a miss = a static screen, not a busy one. */
        const val QUIET_MS = 400L
        private const val MIN_CONTAINMENT_CHARS = 8
    }
}

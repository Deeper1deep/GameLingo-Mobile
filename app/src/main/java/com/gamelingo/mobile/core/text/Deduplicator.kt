package com.gamelingo.mobile.core.text

/**
 * Prevents the same sentence from being translated twice. Identity is the
 * normalizer's comparison key plus a similarity margin, so punctuation and
 * single-letter OCR flicker ("nice" vs "nlce") do not defeat it and re-run
 * translation for the line already on screen.
 */
class Deduplicator(
    private val similarityThreshold: Float = TextStabilityTracker.DEFAULT_SIMILARITY_THRESHOLD,
    private val expiryMs: Long = DEFAULT_EXPIRY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastDeliveredKey: String? = null
    private var lastDeliveredAt: Long = 0L

    /** @return true when this text is new or changed since the last delivery. */
    fun shouldDeliver(raw: String): Boolean {
        val key = TextNormalizer.comparisonKey(raw)
        if (key.isEmpty()) return false
        val previous = lastDeliveredKey
        // Time expiry: games repeat lines after scene changes or retries.
        // A sentence identical to the last delivery is only a duplicate while
        // it is recent; past the expiry it is a genuine repeat and must
        // translate again instead of being blocked forever.
        val expired = clock() - lastDeliveredAt >= expiryMs
        if (previous != null && !expired &&
            TextSimilarity.similarity(key, previous) >= similarityThreshold
        ) {
            // Still the same text on screen: keep the window sliding so a
            // line that stays visible (an objective, a tutorial hint) is not
            // re-delivered every [expiryMs]. Only text that went away and came
            // back after the window counts as a genuine repeat.
            lastDeliveredAt = clock()
            return false
        }
        lastDeliveredKey = key
        lastDeliveredAt = clock()
        return true
    }

    /**
     * The screen did not change since the last delivery, so the delivered text
     * is still on screen: keep it counted as recent.
     */
    fun touch() {
        if (lastDeliveredKey != null) lastDeliveredAt = clock()
    }

    fun reset() {
        lastDeliveredKey = null
        lastDeliveredAt = 0L
    }

    companion object {
        /** How long an identical repeat counts as a duplicate of the last line. */
        const val DEFAULT_EXPIRY_MS = 30_000L
    }
}

package com.gamelingo.mobile.core.text

/**
 * Prevents the same sentence from being translated twice. Identity is the
 * normalizer's comparison key plus a similarity margin, so punctuation and
 * single-letter OCR flicker ("nice" vs "nlce") do not defeat it and re-run
 * translation for the line already on screen.
 */
class Deduplicator(
    private val similarityThreshold: Float = TextStabilityTracker.DEFAULT_SIMILARITY_THRESHOLD,
) {
    private var lastDeliveredKey: String? = null

    /** @return true when this text is new or changed since the last delivery. */
    fun shouldDeliver(raw: String): Boolean {
        val key = TextNormalizer.comparisonKey(raw)
        if (key.isEmpty()) return false
        val previous = lastDeliveredKey
        if (previous != null &&
            TextSimilarity.similarity(key, previous) >= similarityThreshold
        ) {
            return false
        }
        lastDeliveredKey = key
        return true
    }

    fun reset() {
        lastDeliveredKey = null
    }
}

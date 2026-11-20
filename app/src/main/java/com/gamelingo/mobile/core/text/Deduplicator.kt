package com.gamelingo.mobile.core.text

/**
 * Prevents the same sentence from being translated twice. Identity is the
 * normalizer's comparison key, so punctuation flicker does not defeat it.
 */
class Deduplicator {
    private var lastDeliveredKey: String? = null

    /** @return true when this text is new or changed since the last delivery. */
    fun shouldDeliver(raw: String): Boolean {
        val key = TextNormalizer.comparisonKey(raw)
        if (key.isEmpty() || key == lastDeliveredKey) return false
        lastDeliveredKey = key
        return true
    }

    fun reset() {
        lastDeliveredKey = null
    }
}

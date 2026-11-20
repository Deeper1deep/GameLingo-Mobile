package com.gamelingo.mobile.core.text

/**
 * Subtitle text is considered stable only after the same content has been read
 * [requiredReadings] times in a row. Flickering OCR output is held back until it
 * settles, so half-changed lines never reach the translator.
 */
class TextStabilityTracker(
    private val requiredReadings: Int = 2,
) {
    private var lastKey: String? = null
    private var consecutive: Int = 0

    /** @return true when [raw] has now been read [requiredReadings] consecutive times. */
    fun offer(raw: String): Boolean {
        val key = TextNormalizer.comparisonKey(raw)
        if (key.isEmpty()) {
            reset()
            return false
        }
        if (key == lastKey) {
            consecutive += 1
        } else {
            lastKey = key
            consecutive = 1
        }
        return consecutive >= requiredReadings
    }

    fun reset() {
        lastKey = null
        consecutive = 0
    }
}

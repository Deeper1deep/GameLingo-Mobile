package com.gamelingo.mobile.core.text

/**
 * Watches raw OCR output and says when a line is stable enough to translate.
 *
 * A line becomes accepted once its comparison key stayed similar across
 * [requiredReadings] consecutive captures. Similarity (not exact equality)
 * is deliberate: OCR flickers on low-quality video text ("nice" -> "nlce"),
 * and exact matching would keep resetting the counter, delaying or dropping
 * lines that were actually stable on screen.
 *
 * Deliberately tiny and allocation-free-ish: it runs on every capture frame.
 */
class TextStabilityTracker(
    private val requiredReadings: Int = DEFAULT_REQUIRED_READINGS,
    private val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    private val confidentLength: Int = DEFAULT_CONFIDENT_LENGTH,
) {

    private var lastKey: String? = null
    private var consecutive: Int = 0

    /**
     * @return true when this reading completes enough similar readings in a
     * row, meaning the text is stable enough to translate.
     *
     * Latency vs flicker: a full subtitle line (key of at least
     * [confidentLength] characters) is accepted on its FIRST reading. Long
     * lines are almost never OCR noise, and waiting a whole extra capture
     * interval was the single biggest delay between a subtitle appearing and
     * its translation starting. Short fragments (single-word barks) still
     * need [requiredReadings] sightings: they are exactly where OCR flicker
     * lives.
     */
    fun offer(raw: String): Boolean {
        val key = TextNormalizer.comparisonKey(raw)
        if (key.isEmpty()) {
            reset()
            return false
        }
        val previous = lastKey
        if (previous != null &&
            TextSimilarity.similarity(key, previous) >= similarityThreshold
        ) {
            consecutive += 1
            // Track the freshest reading so drift never builds up.
            lastKey = key
        } else {
            lastKey = key
            consecutive = 1
        }
        val needed = if (key.length >= confidentLength) 1 else requiredReadings
        return consecutive >= needed
    }

    /** Clears the current run; called when the session restarts. */
    fun reset() {
        lastKey = null
        consecutive = 0
    }

    companion object {
        const val DEFAULT_REQUIRED_READINGS = 2
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.8f
        const val DEFAULT_CONFIDENT_LENGTH = 20
    }
}

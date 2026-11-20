package com.gamelingo.mobile.core.text

/**
 * Normalized Levenshtein similarity between two strings: 1.0 identical,
 * 0.0 nothing in common. Used to treat slight OCR flicker ("nice" vs "nlce")
 * as the same subtitle line instead of a brand new one.
 */
object TextSimilarity {

    /** @return similarity ratio in [0, 1]: 1 - distance / maxLength. */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxOf(a.length, b.length)
    }

    /** Classic two-row Levenshtein distance; inputs here are short subtitle lines. */
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}

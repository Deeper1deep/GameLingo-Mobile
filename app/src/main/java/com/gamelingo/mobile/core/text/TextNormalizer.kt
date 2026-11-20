package com.gamelingo.mobile.core.text

/**
 * Normalizes OCR output. OCR flickers on punctuation and spacing between frames,
 * so identity comparisons use [comparisonKey], not the raw string.
 */
object TextNormalizer {

    /** Display normalization: collapsed whitespace, trimmed. Case preserved. */
    fun normalize(raw: String): String =
        raw.replace(WHITESPACE, " ").trim()

    /** Identity key: lowercase, letters/digits/spaces only, single-spaced. */
    fun comparisonKey(raw: String): String =
        normalize(raw)
            .lowercase()
            .replace(NON_ALPHANUMERIC, "")
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N} ]")
}

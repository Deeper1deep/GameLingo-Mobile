package com.gamelingo.mobile.core.capture

/** One block of recognized text with its position inside the captured frame. */
data class RecognizedBlock(
    val text: String,
    val rect: PixelRect,
    /** Individual words with their own boxes; empty when the engine gave none. */
    val words: List<RecognizedWord> = emptyList(),
    /** Mean line confidence (0..1) reported by the recogniser; null when it gave none. Informational: nothing is filtered on it yet. */
    val confidence: Float? = null,
)

/** One recognized word and its box, for per-word colour sampling. */
data class RecognizedWord(val text: String, val rect: PixelRect)

/** @return this rectangle moved by [dx]/[dy] pixels. */
fun RecognizedBlock.shifted(dx: Int, dy: Int): RecognizedBlock = copy(
    rect = rect.translated(dx, dy),
    words = words.map { it.copy(rect = it.rect.translated(dx, dy)) },
)

fun PixelRect.translated(dx: Int, dy: Int): PixelRect =
    PixelRect(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)

/**
 * Mean of the confidences the recogniser actually reported. ML Kit documents 0
 * as "unavailable" (older Play services), so zeros are not scores; null when
 * nothing was reported.
 */
fun meanKnownConfidence(values: List<Float>): Float? =
    values.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

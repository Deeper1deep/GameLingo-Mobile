package com.gamelingo.mobile.core.capture

import kotlin.math.abs

/**
 * Picks the block that is most likely the real subtitle line out of everything
 * the OCR engine saw in a frame, and rejects watermarks, logos and HUD labels.
 *
 * Signals, in order of strength:
 *  - temporal: subtitles change every few seconds; corner watermarks sit
 *    identical forever (via [BlockDynamicsTracker]);
 *  - position: subtitles sit bottom-center; blocks parked in a screen corner
 *    are crushed;
 *  - shape: a subtitle is sentence-like (several words, mixed case), a
 *    watermark is usually a short ALL-CAPS token;
 *  - size: a subtitle line has a plausible height relative to the frame.
 *
 * A static block is damped, never fully silenced: a genuinely paused subtitle
 * line still wins over nothing. Blocks below [MIN_SCORE] are rejected, so a
 * frame with only junk yields no line instead of translating a watermark.
 */
object SubtitleBlockScorer {

    const val MIN_SCORE = 0.25f

    /** Weight of the temporal signal: static blocks keep [STATIC_KEEP] of their score. */
    private const val STATIC_KEEP = 0.35f

    /** Corner zone: block centers beyond these fractions count as corner-parked. */
    private const val CORNER_X_LOW = 0.15f
    private const val CORNER_X_HIGH = 0.85f
    private const val CORNER_Y_LOW = 0.15f
    private const val CORNER_Y_HIGH = 0.82f
    private const val CORNER_KEEP = 0.2f

    /** Plausible subtitle-line height as a fraction of frame height. */
    private const val MIN_HEIGHT_FRACTION = 0.01f
    private const val MAX_HEIGHT_FRACTION = 0.05f

    fun score(
        block: RecognizedBlock,
        frameWidth: Int,
        frameHeight: Int,
        dynamics: BlockDynamicsTracker,
    ): Float {
        if (block.text.isBlank()) return 0f
        val cx = (block.rect.left + block.rect.width / 2f) / frameWidth
        val cy = (block.rect.top + block.rect.height / 2f) / frameHeight

        val centerBias = 1f - abs(cx - 0.5f) * 2f
        val bottomBias = cy.coerceIn(0f, 1f)
        val shape = shapeScore(block.text)
        val heightFraction = block.rect.height.toFloat() / frameHeight
        val size = if (heightFraction in MIN_HEIGHT_FRACTION..MAX_HEIGHT_FRACTION) 1f else 0.4f

        val base = 0.45f * centerBias + 0.25f * bottomBias + 0.15f * shape + 0.15f * size

        val inCorner =
            (cx < CORNER_X_LOW || cx > CORNER_X_HIGH) &&
                (cy < CORNER_Y_LOW || cy > CORNER_Y_HIGH)
        val afterCorner = if (inCorner) base * CORNER_KEEP else base

        val dynamic = dynamics.dynamicScore(block)
        return afterCorner * (STATIC_KEEP + (1f - STATIC_KEEP) * dynamic)
    }

    /**
     * @return the highest-scoring block at or above [MIN_SCORE], preferring the
     * lower one on ties, or null when the frame has no plausible subtitle.
     */
    fun selectBest(
        blocks: List<RecognizedBlock>,
        frameWidth: Int,
        frameHeight: Int,
        dynamics: BlockDynamicsTracker,
    ): RecognizedBlock? =
        blocks
            .map { it to score(it, frameWidth, frameHeight, dynamics) }
            .filter { (_, score) -> score >= MIN_SCORE }
            .maxWithOrNull(
                compareBy<Pair<RecognizedBlock, Float>> { (_, score) -> score }
                    .thenBy { (block, _) -> block.rect.top },
            )
            ?.first

    /** Sentence-like text scores high; short ALL-CAPS tokens look like watermarks. */
    private fun shapeScore(text: String): Float {
        val words = text.trim().split(Regex("\\s+"))
            .filter { word -> word.any(Char::isLetterOrDigit) }
        var score = when {
            words.size >= 3 -> 1f
            words.size == 2 -> 0.7f
            words.size == 1 -> 0.4f
            else -> 0f
        }
        val hasLetters = text.any { it.isLetter() }
        val allCaps = hasLetters && text.none { it.isLowerCase() }
        if (allCaps && text.length <= 20) score *= 0.5f
        return score
    }
}

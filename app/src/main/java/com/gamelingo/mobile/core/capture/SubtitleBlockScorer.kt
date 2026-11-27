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

    /**
     * "Obviously corrupted" needs two independent signals, never confidence alone
     * (stylised but readable subtitles can score low): the recogniser's own
     * confidence is known AND below [LOW_CONFIDENCE], AND the text itself looks
     * like garbage ([looksGarbled]). ML Kit documents 0 as "unavailable", so
     * unknown confidence is null and never counts. Raw OCR output is untouched.
     */
    const val LOW_CONFIDENCE = 0.5f

    fun isReadable(block: RecognizedBlock): Boolean {
        val c = block.confidence ?: return true
        return !(c < LOW_CONFIDENCE && looksGarbled(block.text))
    }

    /** Mostly symbols, or hardly any letters/digits: not words in any language. */
    fun looksGarbled(text: String): Boolean {
        val chars = text.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return true
        val wordChars = chars.count { it.isLetterOrDigit() }
        return wordChars.toFloat() / chars.length < 0.6f
    }

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
            .filter(::isReadable)
            .map { it to score(it, frameWidth, frameHeight, dynamics) }
            .filter { (_, score) -> score >= MIN_SCORE }
            .maxWithOrNull(
                compareBy<Pair<RecognizedBlock, Float>> { (_, score) -> score }
                    .thenBy { (block, _) -> block.rect.top },
            )
            ?.first

    /**
     * @return the winning block's text merged with the lines that continue the
     * same subtitle, top to bottom, or null when the frame has no plausible
     * subtitle. Subtitles often wrap onto two stacked lines; selecting only the
     * single best block used to silently drop the other line.
     */
    fun selectSubtitle(
        blocks: List<RecognizedBlock>,
        frameWidth: Int,
        frameHeight: Int,
        dynamics: BlockDynamicsTracker,
    ): String? {
        val winner = selectBest(blocks, frameWidth, frameHeight, dynamics) ?: return null
        val group = mutableListOf(winner)
        // Grow the group until nothing adjacent is left, so a rare three-line
        // subtitle still comes through whole.
        var grew = true
        while (grew) {
            grew = false
            for (block in blocks) {
                if (block in group || block.text.isBlank() || !isReadable(block)) continue
                if (group.any { continuesSameSubtitle(block, it) }) {
                    group.add(block)
                    grew = true
                }
            }
        }
        return group
            .sortedBy { it.rect.top }
            .joinToString(" ") { it.text.trim() }
    }

    /**
     * A block continues the same subtitle when it sits directly above or below
     * [line] (about one line gap at most), shares its horizontal center and has
     * a comparable height. A corner watermark fails the alignment check even
     * when it shares the subtitle's bottom band.
     */
    private fun continuesSameSubtitle(candidate: RecognizedBlock, line: RecognizedBlock): Boolean {
        val candidateCx = candidate.rect.left + candidate.rect.width / 2f
        val lineCx = line.rect.left + line.rect.width / 2f
        val maxOffset = maxOf(candidate.rect.width, line.rect.width) / 2f
        if (abs(candidateCx - lineCx) > maxOffset) return false

        val heightRatio =
            candidate.rect.height.toFloat() / line.rect.height.toFloat()
        if (heightRatio < 0.5f || heightRatio > 2f) return false

        val gap = maxOf(
            candidate.rect.top - line.rect.bottom,
            line.rect.top - candidate.rect.bottom,
        )
        // Small overlaps are fine (OCR boxes touch); a gap larger than one
        // and a half line heights means a separate text block.
        return gap <= line.rect.height * 1.5f
    }

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

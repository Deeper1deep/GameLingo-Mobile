package com.gamelingo.mobile.core.capture

/**
 * Tracks where text appeared over recent frames. Subtitles change every few
 * seconds at roughly the same spot; watermarks and HUD labels sit identical
 * at one spot for a long time. That difference is the strongest signal for
 * telling them apart.
 *
 * Positions are bucketed on a coarse grid so a line that grows or shrinks a
 * little (subtitles are center-anchored) still maps to the same bucket.
 */
class BlockDynamicsTracker(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {

    // Position bucket -> recognized text, one map per processed frame.
    private val frames = ArrayDeque<Map<Long, String>>()

    /**
     * How subtitle-like [block] looks given recent history:
     * [DYNAMIC] = the text at this spot changed, [STATIC] = identical text at
     * this spot for [STATIC_FRAMES] or more, [NEUTRAL] = not enough history.
     * Between first sighting and [STATIC_FRAMES] the score decays gradually,
     * so a genuinely paused subtitle line is not buried immediately.
     */
    fun dynamicScore(block: RecognizedBlock): Float {
        if (frames.isEmpty()) return NEUTRAL
        val key = positionKey(block.rect)
        val previous = frames.mapNotNull { it[key] }
        if (previous.isEmpty()) return NEUTRAL
        if (previous.any { it != block.text }) return DYNAMIC
        val confirmed = previous.size + 1 // including this frame
        if (confirmed >= STATIC_FRAMES) return STATIC
        return NEUTRAL - (NEUTRAL / STATIC_FRAMES) * confirmed
    }

    /** Records one processed frame. Call once per OCR run, after scoring. */
    fun offer(blocks: List<RecognizedBlock>) {
        val frame = HashMap<Long, String>(blocks.size)
        for (block in blocks) {
            frame[positionKey(block.rect)] = block.text
        }
        frames.addLast(frame)
        while (frames.size > windowSize) frames.removeFirst()
    }

    fun reset() {
        frames.clear()
    }

    private fun positionKey(rect: PixelRect): Long {
        val bx = (rect.left + rect.width / 2) / BUCKET_PX
        val by = (rect.top + rect.height / 2) / BUCKET_PX
        return (bx.toLong() shl 32) or (by.toLong() and 0xffffffffL)
    }

    companion object {
        const val NEUTRAL = 0.5f
        const val DYNAMIC = 1f
        const val STATIC = 0f

        /** Identical text at the same spot for this many frames counts as static. */
        const val STATIC_FRAMES = 8
        const val DEFAULT_WINDOW_SIZE = 10
        private const val BUCKET_PX = 96
    }
}

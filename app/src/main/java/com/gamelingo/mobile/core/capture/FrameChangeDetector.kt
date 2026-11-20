package com.gamelingo.mobile.core.capture

import kotlin.math.abs

/**
 * Cheap luminance-histogram comparison used by battery saver mode: OCR only
 * runs when the frame actually changed. Two layers guard against opposite
 * failure modes:
 *
 * - A GLOBAL histogram over every sampled pixel catches big scene changes
 *   without reacting to compression noise (small luminance jitter stays
 *   inside its bucket).
 * - PER-BAND histograms (horizontal slices of the frame) catch a small local
 *   change, like a subtitle swap, that moves too few pixels to register
 *   globally. Any band that changed beyond [bandThreshold] counts as a
 *   changed frame, so text at the top, middle or bottom is all seen.
 *
 * Every pixel is sampled. The previous version skipped ahead by 257 bytes
 * per sample: on the 64x64 digest that is 16 points total, a diagonal line
 * of pixels that misses most of the screen, so real subtitle changes were
 * silently ignored and OCR stopped detecting new lines.
 */
class FrameChangeDetector(
    private val changeThreshold: Double = 0.02,
    private val bandThreshold: Double = 0.03,
    private val bandCount: Int = DEFAULT_BAND_COUNT,
) {
    private var lastGlobal: LongArray? = null
    private var lastBands: Array<LongArray>? = null

    /**
     * @return true when [frame] differs from the previous frame beyond the
     * thresholds, globally or in any band (fraction of pixels that moved
     * histogram bucket). The first frame counts as changed; an empty frame
     * counts as unchanged. Frames of different sizes never compare equal.
     */
    fun hasChanged(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        val global = LongArray(BUCKETS)
        val bands = Array(bandCount) { LongArray(BUCKETS) }
        // Contiguous chunks of a row-major frame are horizontal bands.
        val bandSize = frame.size / bandCount
        val bandCounts = IntArray(bandCount)
        for (index in frame.indices) {
            val bucket = (frame[index].toInt() and 0xFF) shr SHIFT
            global[bucket] += 1
            val band = (index / bandSize).coerceAtMost(bandCount - 1)
            bands[band][bucket] += 1
            bandCounts[band] += 1
        }

        val previousGlobal = lastGlobal
        val previousBands = lastBands
        lastGlobal = global
        lastBands = bands
        if (previousGlobal == null || previousBands == null) return true

        if (movedFraction(global, previousGlobal, frame.size) > changeThreshold) return true
        for (band in 0 until bandCount) {
            val samples = bandCounts[band]
            if (samples == 0) continue
            if (movedFraction(bands[band], previousBands[band], samples) > bandThreshold) {
                return true
            }
        }
        return false
    }

    /**
     * Fraction of samples that changed bucket between the two histograms.
     * Every moved sample is counted twice (once out of its old bucket, once
     * into the new one), hence the division by two.
     */
    private fun movedFraction(current: LongArray, previous: LongArray, samples: Int): Double {
        var moved = 0L
        for (bucket in 0 until BUCKETS) {
            moved += abs(current[bucket] - previous[bucket])
        }
        return moved.toDouble() / (2.0 * samples)
    }

    fun reset() {
        lastGlobal = null
        lastBands = null
    }

    private companion object {
        const val BUCKETS = 16
        const val SHIFT = 4
        const val DEFAULT_BAND_COUNT = 4
    }
}

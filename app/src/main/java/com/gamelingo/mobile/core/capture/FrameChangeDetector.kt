package com.gamelingo.mobile.core.capture

import kotlin.math.abs

/**
 * Cheap luminance-histogram comparison used by battery saver mode: OCR only runs
 * when the sampled frame actually changed. The histogram ignores small compression
 * noise while still catching any real subtitle change.
 */
class FrameChangeDetector(
    private val sampleStep: Int = 257,
    private val changeThreshold: Double = 0.02,
) {
    private var lastHistogram: LongArray? = null

    /**
     * @return true when [frame] differs from the previous frame beyond [changeThreshold]
     * (fraction of sampled pixels that moved histogram bucket). The first frame counts
     * as changed; an empty frame counts as unchanged.
     */
    fun hasChanged(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        val histogram = LongArray(BUCKETS)
        var samples = 0
        var index = 0
        while (index < frame.size) {
            histogram[(frame[index].toInt() and 0xFF) shr SHIFT] += 1
            samples += 1
            index += sampleStep
        }
        val previous = lastHistogram
        lastHistogram = histogram
        if (previous == null) return true
        var moved = 0L
        for (bucket in 0 until BUCKETS) {
            moved += abs(histogram[bucket] - previous[bucket])
        }
        // Every moved sample is counted twice (once out of its old bucket, once into
        // the new one), so divide by two to get the moved fraction.
        val movedFraction = moved.toDouble() / (2.0 * samples)
        return movedFraction > changeThreshold
    }

    fun reset() {
        lastHistogram = null
    }

    private companion object {
        const val BUCKETS = 16
        const val SHIFT = 4
    }
}

package com.gamelingo.mobile.core.engine

/**
 * A rough ESTIMATE, not a measurement: it adds the on-disk size of the models that would be
 * loaded together and compares it with the device's total RAM. Real resident memory differs
 * (memory-mapped files, runtime buffers), so the result is only a hint to show the user.
 * It never disables a feature.
 */
object ResourceAdvisor {
    enum class Level { COMFORTABLE, TIGHT, RISKY }

    data class Advice(val level: Level, val estimatedModelMb: Long, val totalRamMb: Long)

    const val ASR_UNPACKED_MB_ESTIMATE = 71L
    const val TIGHT_FRACTION = 0.40
    const val RISKY_FRACTION = 0.65

    fun advise(
        totalRamMb: Long,
        gemmaLoadedMb: Long?,
        marianLoadedMb: Long?,
        speechOn: Boolean,
    ): Advice {
        val est = (gemmaLoadedMb ?: 0L) + (marianLoadedMb ?: 0L) + if (speechOn) ASR_UNPACKED_MB_ESTIMATE else 0L
        val level = when {
            totalRamMb <= 0 -> Level.COMFORTABLE
            est > totalRamMb * RISKY_FRACTION -> Level.RISKY
            est > totalRamMb * TIGHT_FRACTION -> Level.TIGHT
            else -> Level.COMFORTABLE
        }
        return Advice(level, est, totalRamMb)
    }
}

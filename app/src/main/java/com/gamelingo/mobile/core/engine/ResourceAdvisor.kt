package com.gamelingo.mobile.core.engine

/**
 * Compares RAM that is available RIGHT NOW (a runtime reading from ActivityManager) with the
 * on-disk size of the models the user has enabled. File size is only a LOWER BOUND of what
 * loading a model needs; it is not the model's resident memory (mapped files, runtime buffers
 * and KV caches differ and are not measured here). So the verdict is conservative and labelled
 * an estimate. It never disables a feature.
 */
object ResourceAdvisor {
    enum class Level { COMFORTABLE, TIGHT, RISKY }

    data class Advice(
        val level: Level,
        /** Sum of the on-disk sizes of the enabled models (lower bound of their load cost). */
        val modelFilesMb: Long,
        /** Reported by ActivityManager now. */
        val availableMb: Long,
        val totalMb: Long,
        /** This app's current proportional set size (real reading), or null when unavailable. */
        val appPssMb: Long?,
        val systemLowMemory: Boolean,
    )

    const val ASR_UNPACKED_MB_FILES = 71L

    /** Available RAM below this multiple of the model files is "tight". */
    const val TIGHT_MULTIPLE = 2.0

    fun advise(
        availableMb: Long,
        totalMb: Long,
        systemLowMemory: Boolean,
        appPssMb: Long?,
        gemmaFilesMb: Long?,
        marianFilesMb: Long?,
        speechOn: Boolean,
    ): Advice {
        val files = (gemmaFilesMb ?: 0L) + (marianFilesMb ?: 0L) + if (speechOn) ASR_UNPACKED_MB_FILES else 0L
        val level = when {
            systemLowMemory -> Level.RISKY
            files > 0 && availableMb < files -> Level.RISKY
            files > 0 && availableMb < files * TIGHT_MULTIPLE -> Level.TIGHT
            else -> Level.COMFORTABLE
        }
        return Advice(level, files, availableMb, totalMb, appPssMb, systemLowMemory)
    }
}

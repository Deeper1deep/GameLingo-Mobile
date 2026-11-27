package com.gamelingo.mobile.core.engine

/**
 * Reads memory PRESSURE right now from runtime readings only: ActivityManager's available RAM and
 * low-memory flag, plus this app's own PSS. It does NOT derive a capacity verdict from model file
 * sizes: on-disk bytes are not resident RAM (mapped files, paging, quantisation and runtime
 * workspaces all differ), and no supported runtime estimate of a model's resident size exists
 * here. File sizes are reported separately as STORAGE only. The "tight" cut-off is a heuristic
 * (unmeasured). It never disables a feature.
 */
object ResourceAdvisor {
    enum class Level { COMFORTABLE, TIGHT, RISKY }

    data class Advice(
        val level: Level,
        /** Sum of on-disk sizes of enabled models. Storage only, not RAM. */
        val modelStorageMb: Long,
        val availableMb: Long,
        val totalMb: Long,
        /** This app's current proportional set size (real reading), or null when unavailable. */
        val appPssMb: Long?,
        val systemLowMemory: Boolean,
    )

    const val ASR_UNPACKED_MB_FILES = 71L

    /** Heuristic, unmeasured: available RAM under this share of total counts as tight. */
    const val TIGHT_AVAILABLE_SHARE = 0.15

    fun advise(
        availableMb: Long,
        totalMb: Long,
        systemLowMemory: Boolean,
        appPssMb: Long?,
        gemmaFilesMb: Long?,
        marianFilesMb: Long?,
        speechOn: Boolean,
    ): Advice {
        val storage = (gemmaFilesMb ?: 0L) + (marianFilesMb ?: 0L) + if (speechOn) ASR_UNPACKED_MB_FILES else 0L
        val level = when {
            systemLowMemory -> Level.RISKY
            totalMb > 0 && availableMb < totalMb * TIGHT_AVAILABLE_SHARE -> Level.TIGHT
            else -> Level.COMFORTABLE
        }
        return Advice(level, storage, availableMb, totalMb, appPssMb, systemLowMemory)
    }
}

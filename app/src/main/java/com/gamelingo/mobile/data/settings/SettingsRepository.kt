package com.gamelingo.mobile.data.settings

import kotlinx.coroutines.flow.Flow

/** Engine selection for the hybrid translator. */
enum class AiMode {
    /** Try the online provider first, fall back to the local engine. */
    AUTO,

    /** Online provider only; fail visibly when there is no connection. */
    ONLINE,

    /** On-device engine only; never touches the network. */
    LOCAL,
}

data class AppSettings(
    val aiMode: AiMode = AiMode.AUTO,
    val captureIntervalMs: Long = DEFAULT_CAPTURE_INTERVAL_MS,
    val restrictToTextRegion: Boolean = true,
    val overlayOpacity: Float = DEFAULT_OVERLAY_OPACITY,
    val overlayTextSizeSp: Int = DEFAULT_OVERLAY_TEXT_SIZE_SP,
    val showOriginalText: Boolean = true,
) {
    companion object {
        const val DEFAULT_CAPTURE_INTERVAL_MS = 1_000L
        const val MIN_CAPTURE_INTERVAL_MS = 300L
        const val MAX_CAPTURE_INTERVAL_MS = 3_000L
        const val DEFAULT_OVERLAY_OPACITY = 0.85f
        const val DEFAULT_OVERLAY_TEXT_SIZE_SP = 18
        const val MIN_OVERLAY_TEXT_SIZE_SP = 12
        const val MAX_OVERLAY_TEXT_SIZE_SP = 30
    }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setAiMode(mode: AiMode)
    suspend fun setCaptureIntervalMs(intervalMs: Long)
    suspend fun setRestrictToTextRegion(restrict: Boolean)
    suspend fun setOverlayOpacity(opacity: Float)
    suspend fun setOverlayTextSizeSp(sizeSp: Int)
    suspend fun setShowOriginalText(show: Boolean)
}

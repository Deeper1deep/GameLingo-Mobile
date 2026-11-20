package com.gamelingo.mobile.data.settings

import com.gamelingo.mobile.core.model.OverlayColors
import kotlinx.coroutines.flow.Flow

/** Every user-tunable setting, persisted in DataStore. Defaults match [SettingsConstraints]. */
data class AppSettings(
    // Translation
    val showOriginalText: Boolean = true,
    // OCR
    val captureIntervalMs: Long = SettingsConstraints.DEFAULT_CAPTURE_INTERVAL_MS,
    val restrictToTextRegion: Boolean = true,
    val regionPreset: TextRegionPreset = TextRegionPreset.LOWER_THIRD,
    val regionTopFraction: Float = SettingsConstraints.DEFAULT_REGION_TOP,
    val regionBottomFraction: Float = SettingsConstraints.DEFAULT_REGION_BOTTOM,
    val dedupEnabled: Boolean = true,
    val ignoreUnstableText: Boolean = true,
    val batterySaverEnabled: Boolean = true,
    // Hybrid AI
    val aiMode: AiMode = AiMode.AUTO,
    /** User-supplied key for the online provider. Stored on-device only, never shipped in the APK. */
    val onlineApiKey: String = "",
    /**
     * Explicit consent to send recognized subtitle text (plus short context) to
     * the cloud provider. False means every mode behaves as local: until the
     * user accepts the in-app explainer, no OCR text ever leaves the device.
     */
    val cloudTranslationOptIn: Boolean = false,
    val requestTimeoutSec: Int = SettingsConstraints.DEFAULT_TIMEOUT_SEC,
    val cacheEnabled: Boolean = true,
    val contextLines: Int = SettingsConstraints.DEFAULT_CONTEXT_LINES,
    // Floating window
    val overlayOpacity: Float = SettingsConstraints.DEFAULT_OVERLAY_OPACITY,
    val overlayTextSizeSp: Int = SettingsConstraints.DEFAULT_OVERLAY_TEXT_SIZE_SP,
    val overlayTextColor: Long = OverlayColors.DEFAULT_TEXT,
    val overlayBackgroundColor: Long = OverlayColors.DEFAULT_BACKGROUND,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM,
) {
    /** The effective OCR band for the current preset. */
    val effectiveRegion: TextRegionFractions
        get() = SettingsConstraints.resolveRegion(
            preset = regionPreset,
            customTop = regionTopFraction,
            customBottom = regionBottomFraction,
        )
}

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setShowOriginalText(show: Boolean)

    suspend fun setCaptureIntervalMs(intervalMs: Long)
    suspend fun setRestrictToTextRegion(restrict: Boolean)
    suspend fun setRegionPreset(preset: TextRegionPreset)
    suspend fun setRegionTopFraction(fraction: Float)
    suspend fun setRegionBottomFraction(fraction: Float)
    suspend fun setDedupEnabled(enabled: Boolean)
    suspend fun setIgnoreUnstableText(ignore: Boolean)
    suspend fun setBatterySaverEnabled(enabled: Boolean)

    suspend fun setAiMode(mode: AiMode)
    suspend fun setOnlineApiKey(key: String)
    suspend fun setCloudTranslationOptIn(enabled: Boolean)
    suspend fun setRequestTimeoutSec(seconds: Int)
    suspend fun setCacheEnabled(enabled: Boolean)
    suspend fun setContextLines(lines: Int)

    suspend fun setOverlayOpacity(opacity: Float)
    suspend fun setOverlayTextSizeSp(sizeSp: Int)
    suspend fun setOverlayTextColor(argb: Long)
    suspend fun setOverlayBackgroundColor(argb: Long)
    suspend fun setOverlayPosition(position: OverlayPosition)
}

package com.gamelingo.mobile.data.settings

import com.gamelingo.mobile.core.model.OverlayColors
import kotlinx.coroutines.flow.Flow

/** Every user-tunable setting, persisted in DataStore. Defaults match [SettingsConstraints]. */
data class AppSettings(
    // Translation
    val showOriginalText: Boolean = true,
    /**
     * Arabic style of every translation. Default EGYPTIAN_ARABIC: the
     * project's established behavior, kept for existing users on update.
     */
    val arabicTranslationMode: ArabicTranslationMode = ArabicTranslationMode.EGYPTIAN_ARABIC,
    // OCR
    val captureIntervalMs: Long = SettingsConstraints.DEFAULT_CAPTURE_INTERVAL_MS,
    val restrictToTextRegion: Boolean = true,
    /**
     * Smart subtitle detection: score positioned OCR blocks and pick the real
     * subtitle line, rejecting static corner watermarks and HUD labels. When
     * false, all recognized text in the scan band is used as before.
     */
    val smartTextDetection: Boolean = true,
    val regionPreset: TextRegionPreset = TextRegionPreset.LOWER_THIRD,
    val regionTopFraction: Float = SettingsConstraints.DEFAULT_REGION_TOP,
    val regionBottomFraction: Float = SettingsConstraints.DEFAULT_REGION_BOTTOM,
    val dedupEnabled: Boolean = true,
    val ignoreUnstableText: Boolean = true,
    val batterySaverEnabled: Boolean = true,
    // Hybrid AI
    val aiMode: AiMode = AiMode.AUTO,
    /** User-supplied key for the online provider. Stored encrypted on-device only, never shipped in the APK. */
    val onlineApiKey: String = "",
    /**
     * Explicit consent to send recognized subtitle text (plus short context) to
     * the cloud provider. False means no OCR text ever leaves the device:
     * LOCAL and AUTO stay on-device, and ONLINE fails visibly with a setup
     * notice instead of silently translating locally.
     */
    val cloudTranslationOptIn: Boolean = false,
    /**
     * AUTO mode with a local AI model installed: whether a local failure may
     * fall back to the online provider. Default false per the offline-first
     * rule: online fallback only happens under a clear user-controlled policy.
     */
    val autoOnlineFallback: Boolean = false,
    /** Local model downloads prefer Wi-Fi; on mobile data the download asks first. */
    val localAiWifiOnly: Boolean = true,
    /**
     * Advanced opt-in: allow the TranslateGemma 4B HD engine in the local
     * chain. Default false: the balanced path is Marian INT8. Gemma is a
     * 4B-parameter model; enabling it on a device that cannot sustain it
     * (thermals, memory) makes translations slower, not better, so it never
     * loads unless the user explicitly asks for it AND the device is cool
     * enough at that moment.
     */
    val gemmaHdOptIn: Boolean = false,
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
    suspend fun setArabicTranslationMode(mode: ArabicTranslationMode)

    suspend fun setCaptureIntervalMs(intervalMs: Long)
    suspend fun setRestrictToTextRegion(restrict: Boolean)
    suspend fun setSmartTextDetection(enabled: Boolean)
    suspend fun setRegionPreset(preset: TextRegionPreset)
    suspend fun setRegionTopFraction(fraction: Float)
    suspend fun setRegionBottomFraction(fraction: Float)
    suspend fun setDedupEnabled(enabled: Boolean)
    suspend fun setIgnoreUnstableText(ignore: Boolean)
    suspend fun setBatterySaverEnabled(enabled: Boolean)

    suspend fun setAiMode(mode: AiMode)
    suspend fun setOnlineApiKey(key: String)

    /**
     * Moves a legacy plaintext API key into encrypted storage. Runs once at
     * app start; a no-op when there is nothing to migrate. Never deletes the
     * plaintext until the ciphertext is confirmed written.
     */
    suspend fun migrateApiKeyIfNeeded()

    suspend fun setCloudTranslationOptIn(enabled: Boolean)
    suspend fun setAutoOnlineFallback(enabled: Boolean)
    suspend fun setLocalAiWifiOnly(enabled: Boolean)
    suspend fun setGemmaHdOptIn(enabled: Boolean)
    suspend fun setRequestTimeoutSec(seconds: Int)
    suspend fun setCacheEnabled(enabled: Boolean)
    suspend fun setContextLines(lines: Int)

    suspend fun setOverlayOpacity(opacity: Float)
    suspend fun setOverlayTextSizeSp(sizeSp: Int)
    suspend fun setOverlayTextColor(argb: Long)
    suspend fun setOverlayBackgroundColor(argb: Long)
    suspend fun setOverlayPosition(position: OverlayPosition)
}

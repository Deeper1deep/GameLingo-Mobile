package com.gamelingo.mobile.data.settings

import com.gamelingo.mobile.core.model.OverlayColors
import kotlinx.coroutines.flow.Flow

/** Every user-tunable setting, persisted in DataStore. Defaults match [SettingsConstraints]. */
data class AppSettings(
    // Translation
    val showOriginalText: Boolean = true,
    /**
     * Keep the colours the game drew words in (item names, speakers) in the
     * overlay. Off = one text colour. Colours are read locally from the
     * captured frame; no pixels leave the device.
     */
    val preserveSourceColors: Boolean = false,
    /** Typography, outline, shadow and box shape of the overlay (JSON in DataStore). */
    val overlayStyle: com.gamelingo.mobile.core.overlay.OverlayStyle = com.gamelingo.mobile.core.overlay.OverlayStyle(),
    /** The user's saved custom preset, if any. */
    val customOverlayPreset: com.gamelingo.mobile.core.overlay.OverlayAppearance? = null,
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
    /**
     * Custom translation area: when on, only text inside the user's own
     * rectangle is read and translated. Off by default = the scan band above.
     */
    val customRegionEnabled: Boolean = false,
    /** The single region used in every orientation (default model). */
    val linkedRegion: com.gamelingo.mobile.core.region.NormalizedRegion? = null,
    /** Off by default: one linked region. On: [customRegions] hold per-orientation overrides. */
    val independentRegions: Boolean = false,
    /** Per-orientation rectangles (legacy values and independent-mode overrides), as screen fractions. */
    val customRegions: com.gamelingo.mobile.core.region.OrientedRegions =
        com.gamelingo.mobile.core.region.OrientedRegions(),
    /**
     * Where the translation is drawn: independent of [customRegions] (what is
     * scanned). Defaults reproduce the existing top/bottom anchor.
     */
    val translationPlacement: com.gamelingo.mobile.core.overlay.TranslationPlacement =
        com.gamelingo.mobile.core.overlay.TranslationPlacement(),
    val showRegionBorder: Boolean = true,
    val regionSensitivity: com.gamelingo.mobile.core.region.RegionSensitivity =
        com.gamelingo.mobile.core.region.RegionSensitivity.BALANCED,
    val regionPreview: Boolean = false,
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
    /**
     * The user's own glossary terms (character names, factions, slang from
     * the game they play). These win over the bundled dictionary so a name
     * the user pins stays exactly as they wrote it. Persisted as JSON in
     * DataStore; no database schema change.
     */
    val userGlossaryTerms: List<UserGlossaryTerm> = emptyList(),
) {
    /** The effective OCR band for the current preset. */
    val effectiveRegion: TextRegionFractions
        get() = SettingsConstraints.resolveRegion(
            preset = regionPreset,
            customTop = regionTopFraction,
            customBottom = regionBottomFraction,
        )

    /**
     * The custom area to scan for a frame of this size. Never null: a missing
     * orientation inherits the linked region, and a missing region altogether
     * falls to the safe default band, never to the full screen.
     */
    fun resolveCustomRegion(frameWidth: Int, frameHeight: Int): com.gamelingo.mobile.core.region.ResolvedRegion =
        com.gamelingo.mobile.core.region.RegionResolver.resolve(
            linkedRegion, customRegions, independentRegions, frameWidth, frameHeight,
        )
}

/** One user-pinned term: [en] must be rendered [ar], in both Arabic modes. */
@kotlinx.serialization.Serializable
data class UserGlossaryTerm(
    val en: String,
    val ar: String,
    /** Optional metadata the user keeps with the term. Not sent to the model. */
    val game: String = "",
    val pronunciation: String = "",
    val note: String = "",
)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setShowOriginalText(show: Boolean)
    suspend fun setArabicTranslationMode(mode: ArabicTranslationMode)
    suspend fun setUserGlossaryTerms(terms: List<UserGlossaryTerm>)

    suspend fun setCaptureIntervalMs(intervalMs: Long)
    suspend fun setRestrictToTextRegion(restrict: Boolean)
    suspend fun setSmartTextDetection(enabled: Boolean)
    suspend fun setRegionPreset(preset: TextRegionPreset)
    suspend fun setRegionTopFraction(fraction: Float)
    suspend fun setRegionBottomFraction(fraction: Float)
    suspend fun setCustomRegionEnabled(enabled: Boolean)

    /** Saves the single linked region used in every orientation. */
    suspend fun setLinkedRegion(region: com.gamelingo.mobile.core.region.NormalizedRegion?)
    suspend fun setIndependentRegions(enabled: Boolean)

    /** Removes the linked region and every per-orientation rectangle (explicit user reset only). */
    suspend fun clearCustomRegions()

    /**
     * One-time, non-destructive move to the linked model: when no linked
     * region exists yet, the active orientation's saved rectangle (else the
     * other valid one) becomes the linked region. Old per-orientation values
     * stay untouched. Safe to call repeatedly.
     */
    suspend fun migrateLinkedRegion(preferLandscape: Boolean)

    /** Saves the translation placement; never touches the OCR region. */
    suspend fun setTranslationPlacement(placement: com.gamelingo.mobile.core.overlay.TranslationPlacement)

    /** Saves (or clears with null) the rectangle of one orientation. */
    suspend fun setCustomRegion(landscape: Boolean, region: com.gamelingo.mobile.core.region.NormalizedRegion?)
    suspend fun setShowRegionBorder(show: Boolean)
    suspend fun setRegionSensitivity(sensitivity: com.gamelingo.mobile.core.region.RegionSensitivity)
    suspend fun setRegionPreview(enabled: Boolean)
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
    suspend fun setPreserveSourceColors(enabled: Boolean)
    suspend fun setOverlayStyle(style: com.gamelingo.mobile.core.overlay.OverlayStyle)
    suspend fun setCustomOverlayPreset(preset: com.gamelingo.mobile.core.overlay.OverlayAppearance?)
}

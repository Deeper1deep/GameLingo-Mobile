package com.gamelingo.mobile.translate

import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.data.settings.TextRegionPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory settings for engine tests; only the AI fields are exercised. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {

    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun setShowOriginalText(show: Boolean) {
        state.value = state.value.copy(showOriginalText = show)
    }

    override suspend fun setCaptureIntervalMs(intervalMs: Long) {
        state.value = state.value.copy(captureIntervalMs = intervalMs)
    }

    override suspend fun setRestrictToTextRegion(restrict: Boolean) {
        state.value = state.value.copy(restrictToTextRegion = restrict)
    }

    override suspend fun setSmartTextDetection(enabled: Boolean) {
        state.value = state.value.copy(smartTextDetection = enabled)
    }

    override suspend fun setRegionPreset(preset: TextRegionPreset) {
        state.value = state.value.copy(regionPreset = preset)
    }

    override suspend fun setRegionTopFraction(fraction: Float) {
        state.value = state.value.copy(regionTopFraction = fraction)
    }

    override suspend fun setRegionBottomFraction(fraction: Float) {
        state.value = state.value.copy(regionBottomFraction = fraction)
    }

    override suspend fun setDedupEnabled(enabled: Boolean) {
        state.value = state.value.copy(dedupEnabled = enabled)
    }

    override suspend fun setIgnoreUnstableText(ignore: Boolean) {
        state.value = state.value.copy(ignoreUnstableText = ignore)
    }

    override suspend fun setBatterySaverEnabled(enabled: Boolean) {
        state.value = state.value.copy(batterySaverEnabled = enabled)
    }

    override suspend fun setAiMode(mode: AiMode) {
        state.value = state.value.copy(aiMode = mode)
    }

    override suspend fun setOnlineApiKey(key: String) {
        state.value = state.value.copy(onlineApiKey = key)
    }

    override suspend fun migrateApiKeyIfNeeded() = Unit

    override suspend fun setCloudTranslationOptIn(enabled: Boolean) {
        state.value = state.value.copy(cloudTranslationOptIn = enabled)
    }

    override suspend fun setAutoOnlineFallback(enabled: Boolean) {
        state.value = state.value.copy(autoOnlineFallback = enabled)
    }

    override suspend fun setLocalAiWifiOnly(enabled: Boolean) {
        state.value = state.value.copy(localAiWifiOnly = enabled)
    }

    override suspend fun setGemmaHdOptIn(enabled: Boolean) {
        state.value = state.value.copy(gemmaHdOptIn = enabled)
    }

    override suspend fun setArabicTranslationMode(mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode) {
        state.value = state.value.copy(arabicTranslationMode = mode)
    }

    override suspend fun setUserGlossaryTerms(terms: List<com.gamelingo.mobile.data.settings.UserGlossaryTerm>) {
        state.value = state.value.copy(userGlossaryTerms = terms)
    }

    override suspend fun setRequestTimeoutSec(seconds: Int) {
        state.value = state.value.copy(requestTimeoutSec = seconds)
    }

    override suspend fun setCacheEnabled(enabled: Boolean) {
        state.value = state.value.copy(cacheEnabled = enabled)
    }

    override suspend fun setContextLines(lines: Int) {
        state.value = state.value.copy(contextLines = lines)
    }

    override suspend fun setOverlayOpacity(opacity: Float) {
        state.value = state.value.copy(overlayOpacity = opacity)
    }

    override suspend fun setOverlayTextSizeSp(sizeSp: Int) {
        state.value = state.value.copy(overlayTextSizeSp = sizeSp)
    }

    override suspend fun setOverlayTextColor(argb: Long) {
        state.value = state.value.copy(overlayTextColor = argb)
    }

    override suspend fun setOverlayBackgroundColor(argb: Long) {
        state.value = state.value.copy(overlayBackgroundColor = argb)
    }

    override suspend fun setOverlayStyle(style: com.gamelingo.mobile.core.overlay.OverlayStyle) {
        state.value = state.value.copy(overlayStyle = style)
    }

    override suspend fun setCustomOverlayPreset(preset: com.gamelingo.mobile.core.overlay.OverlayAppearance?) {
        state.value = state.value.copy(customOverlayPreset = preset)
    }

    override suspend fun setCustomRegionEnabled(enabled: Boolean) {
        state.value = state.value.copy(customRegionEnabled = enabled)
    }

    override suspend fun setCustomRegion(
        landscape: Boolean,
        region: com.gamelingo.mobile.core.region.NormalizedRegion?,
    ) {
        val clean = region?.sanitizedOrNull()
        val cur = state.value.customRegions
        state.value = state.value.copy(
            customRegions = if (landscape) cur.copy(landscape = clean) else cur.copy(portrait = clean),
        )
    }

    override suspend fun setTranslationPlacement(
        placement: com.gamelingo.mobile.core.overlay.TranslationPlacement,
    ) {
        state.value = state.value.copy(translationPlacement = placement.sanitized())
    }

    override suspend fun setShowRegionBorder(show: Boolean) {
        state.value = state.value.copy(showRegionBorder = show)
    }

    override suspend fun setRegionSensitivity(sensitivity: com.gamelingo.mobile.core.region.RegionSensitivity) {
        state.value = state.value.copy(regionSensitivity = sensitivity)
    }

    override suspend fun setRegionPreview(enabled: Boolean) {
        state.value = state.value.copy(regionPreview = enabled)
    }

    override suspend fun setPreserveSourceColors(enabled: Boolean) {
        state.value = state.value.copy(preserveSourceColors = enabled)
    }

    override suspend fun setOverlayPosition(position: OverlayPosition) {
        state.value = state.value.copy(overlayPosition = position)
    }
}

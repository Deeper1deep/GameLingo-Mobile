package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.data.settings.TextRegionPreset
import com.gamelingo.mobile.translate.TranslationRequest
import com.gamelingo.mobile.translate.local.EgyptianDictionaryHolder
import com.gamelingo.mobile.translate.local.LocalAiModelState
import com.gamelingo.mobile.translate.local.LocalEngineChain
import com.gamelingo.mobile.translate.local.LocalModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outcome of the settings-screen local translation test. */
data class LocalTestResult(
    val translatedText: String,
    val engineId: String,
    val elapsedMs: Long,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val localModelManager: LocalModelManager,
    private val localEngineChain: LocalEngineChain,
    private val dictionaryHolder: EgyptianDictionaryHolder,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setShowOriginalText(show: Boolean) =
        viewModelScope.launch { repository.setShowOriginalText(show) }

    fun setCaptureIntervalMs(intervalMs: Long) =
        viewModelScope.launch { repository.setCaptureIntervalMs(intervalMs) }

    fun setRestrictToTextRegion(restrict: Boolean) =
        viewModelScope.launch { repository.setRestrictToTextRegion(restrict) }

    fun setSmartTextDetection(enabled: Boolean) =
        viewModelScope.launch { repository.setSmartTextDetection(enabled) }

    fun setRegionPreset(preset: TextRegionPreset) =
        viewModelScope.launch { repository.setRegionPreset(preset) }

    fun setRegionTopFraction(fraction: Float) =
        viewModelScope.launch { repository.setRegionTopFraction(fraction) }

    fun setRegionBottomFraction(fraction: Float) =
        viewModelScope.launch { repository.setRegionBottomFraction(fraction) }

    fun setDedupEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setDedupEnabled(enabled) }

    fun setIgnoreUnstableText(ignore: Boolean) =
        viewModelScope.launch { repository.setIgnoreUnstableText(ignore) }

    fun setBatterySaverEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setBatterySaverEnabled(enabled) }

    fun setAiMode(mode: AiMode) =
        viewModelScope.launch { repository.setAiMode(mode) }

    fun setOnlineApiKey(key: String) =
        viewModelScope.launch { repository.setOnlineApiKey(key) }

    fun setCloudTranslationOptIn(enabled: Boolean) =
        viewModelScope.launch { repository.setCloudTranslationOptIn(enabled) }

    fun setAutoOnlineFallback(enabled: Boolean) =
        viewModelScope.launch { repository.setAutoOnlineFallback(enabled) }

    fun setLocalAiWifiOnly(enabled: Boolean) =
        viewModelScope.launch { repository.setLocalAiWifiOnly(enabled) }

    fun setGemmaHdOptIn(enabled: Boolean) =
        viewModelScope.launch { repository.setGemmaHdOptIn(enabled) }

    fun setArabicTranslationMode(mode: com.gamelingo.mobile.data.settings.ArabicTranslationMode) =
        viewModelScope.launch { repository.setArabicTranslationMode(mode) }

    /** Pin a term the user wants rendered their way (wins over the bundled dictionary). */
    fun addUserGlossaryTerm(en: String, ar: String) = viewModelScope.launch {
        val source = en.trim()
        val rendering = ar.trim()
        if (source.isEmpty() || rendering.isEmpty()) return@launch
        val current = repository.settings.first().userGlossaryTerms
            .filterNot { it.en.equals(source, ignoreCase = true) }
        repository.setUserGlossaryTerms(
            current + com.gamelingo.mobile.data.settings.UserGlossaryTerm(source, rendering),
        )
    }

    fun removeUserGlossaryTerm(en: String) = viewModelScope.launch {
        val current = repository.settings.first().userGlossaryTerms
            .filterNot { it.en.equals(en, ignoreCase = true) }
        repository.setUserGlossaryTerms(current)
    }

    // ---- Local AI engine section ----

    /** Per-model download/install state for the local AI cards. */
    val localAiStates: StateFlow<Map<String, LocalAiModelState>> = localModelManager.states

    /** Models known to the bundled manifest, in display order. */
    fun localModelSpecs(): List<LocalModelManager.ModelSpec> = localModelManager.specs()

    /** Version of the active Egyptian dictionary. */
    fun dictionaryVersion(): Int = dictionaryHolder.get().version

    /** Off the main thread: a pre-marker install is hashed once on first read. */
    fun refreshLocalAiStates() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        localModelManager.refreshStates()
    }

    /** Starts a consented model download; Wi-Fi preference comes from settings. */
    fun downloadLocalModel(modelId: String) = viewModelScope.launch {
        val wifiOnly = repository.settings.stateIn(viewModelScope).value.localAiWifiOnly
        runCatching { localModelManager.download(modelId, preferWifi = wifiOnly) }
        // Failures surface through localAiStates (Failed with the reason).
    }

    fun deleteLocalModel(modelId: String) = viewModelScope.launch {
        localModelManager.deleteModel(modelId)
    }

    private val _localTestResult = MutableStateFlow<LocalTestResult?>(null)

    /** Result of the last settings-screen local translation test, if any. */
    val localTestResult: StateFlow<LocalTestResult?> = _localTestResult.asStateFlow()

    private val _localTestRunning = MutableStateFlow(false)
    val localTestRunning: StateFlow<Boolean> = _localTestRunning.asStateFlow()

    /**
     * Runs one fixed game line through the local chain and reports the real
     * engine id and wall-clock time, so the user can see both quality and speed
     * of what is actually installed. Never fakes a working local engine: with
     * no model installed the error says so plainly.
     */
    fun testLocalTranslation() = viewModelScope.launch {
        if (_localTestRunning.value) return@launch
        _localTestRunning.value = true
        try {
            val start = System.currentTimeMillis()
            val result = try {
                localEngineChain.translate(
                    TranslationRequest(text = "The heist went sideways, grab the money and run."),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _localTestResult.value = LocalTestResult(
                    translatedText = "",
                    engineId = "none",
                    elapsedMs = System.currentTimeMillis() - start,
                    error = error.message ?: error.javaClass.simpleName,
                )
                return@launch
            }
            _localTestResult.value = LocalTestResult(
                translatedText = result.translatedText,
                engineId = result.engineId,
                elapsedMs = System.currentTimeMillis() - start,
            )
        } finally {
            _localTestRunning.value = false
        }
    }

    fun setRequestTimeoutSec(seconds: Int) =
        viewModelScope.launch { repository.setRequestTimeoutSec(seconds) }

    fun setCacheEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setCacheEnabled(enabled) }

    fun setContextLines(lines: Int) =
        viewModelScope.launch { repository.setContextLines(lines) }

    fun setOverlayOpacity(opacity: Float) =
        viewModelScope.launch { repository.setOverlayOpacity(opacity) }

    fun setOverlayTextSizeSp(sizeSp: Int) =
        viewModelScope.launch { repository.setOverlayTextSizeSp(sizeSp) }

    fun setOverlayTextColor(argb: Long) =
        viewModelScope.launch { repository.setOverlayTextColor(argb) }

    fun setOverlayBackgroundColor(argb: Long) =
        viewModelScope.launch { repository.setOverlayBackgroundColor(argb) }

    /** Applies a whole appearance (editor "Apply" and preset taps). */
    fun applyAppearance(a: com.gamelingo.mobile.core.overlay.OverlayAppearance) {
        val s = a.sanitized()
        viewModelScope.launch {
            repository.setOverlayTextColor(s.textColor)
            repository.setOverlayBackgroundColor(s.backgroundColor)
            repository.setOverlayOpacity(s.opacity)
            repository.setOverlayTextSizeSp(s.textSizeSp)
            repository.setOverlayStyle(s.style)
        }
    }

    fun saveCustomPreset(a: com.gamelingo.mobile.core.overlay.OverlayAppearance?) =
        viewModelScope.launch { repository.setCustomOverlayPreset(a) }

    fun setCustomRegionEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setCustomRegionEnabled(enabled) }

    fun setCustomRegion(landscape: Boolean, region: com.gamelingo.mobile.core.region.NormalizedRegion?) =
        viewModelScope.launch { repository.setCustomRegion(landscape, region) }

    fun setShowRegionBorder(show: Boolean) =
        viewModelScope.launch { repository.setShowRegionBorder(show) }

    fun setRegionSensitivity(s: com.gamelingo.mobile.core.region.RegionSensitivity) =
        viewModelScope.launch { repository.setRegionSensitivity(s) }

    fun setRegionPreview(enabled: Boolean) =
        viewModelScope.launch { repository.setRegionPreview(enabled) }

    fun setPreserveSourceColors(enabled: Boolean) =
        viewModelScope.launch { repository.setPreserveSourceColors(enabled) }

    fun setOverlayPosition(position: OverlayPosition) =
        viewModelScope.launch { repository.setOverlayPosition(position) }
}

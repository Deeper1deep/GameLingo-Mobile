package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.data.settings.TextRegionPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setShowOriginalText(show: Boolean) =
        viewModelScope.launch { repository.setShowOriginalText(show) }

    fun setCaptureIntervalMs(intervalMs: Long) =
        viewModelScope.launch { repository.setCaptureIntervalMs(intervalMs) }

    fun setRestrictToTextRegion(restrict: Boolean) =
        viewModelScope.launch { repository.setRestrictToTextRegion(restrict) }

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

    fun setOverlayPosition(position: OverlayPosition) =
        viewModelScope.launch { repository.setOverlayPosition(position) }
}

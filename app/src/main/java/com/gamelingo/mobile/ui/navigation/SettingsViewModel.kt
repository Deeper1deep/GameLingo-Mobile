package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsRepository
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

    fun setAiMode(mode: AiMode) = viewModelScope.launch { repository.setAiMode(mode) }

    fun setCaptureIntervalMs(intervalMs: Long) =
        viewModelScope.launch { repository.setCaptureIntervalMs(intervalMs) }

    fun setRestrictToTextRegion(restrict: Boolean) =
        viewModelScope.launch { repository.setRestrictToTextRegion(restrict) }

    fun setOverlayOpacity(opacity: Float) =
        viewModelScope.launch { repository.setOverlayOpacity(opacity) }

    fun setOverlayTextSizeSp(sizeSp: Int) =
        viewModelScope.launch { repository.setOverlayTextSizeSp(sizeSp) }

    fun setShowOriginalText(show: Boolean) =
        viewModelScope.launch { repository.setShowOriginalText(show) }
}

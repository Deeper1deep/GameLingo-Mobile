package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.audio.AsrModelManager
import com.gamelingo.mobile.audio.AsrModelState
import com.gamelingo.mobile.audio.AudioPrefs
import com.gamelingo.mobile.audio.AudioSettings
import com.gamelingo.mobile.audio.AudioTranslationController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val prefs: AudioPrefs,
    private val models: AsrModelManager,
    controller: AudioTranslationController,
) : ViewModel() {
    val settings: StateFlow<AudioSettings> = prefs.settings
    val model: StateFlow<AsrModelState> = models.state
    val status: StateFlow<AudioTranslationController.Status> = controller.status
    val modelSizeBytes: Long get() = models.spec.sizeBytes
    val modelLicense: String get() = models.spec.license
    val modelSource: String get() = models.spec.sourcePage

    private var download: Job? = null

    init { models.refresh() }

    fun setSpeech(on: Boolean) = prefs.update { it.copy(speechEnabled = on) }
    fun setTts(on: Boolean) = prefs.update { it.copy(ttsEnabled = on) }
    fun setWifiOnly(on: Boolean) = prefs.update { it.copy(wifiOnlyDownload = on) }

    /** Only ever called from the user's tap on the download button. */
    fun downloadModel() {
        if (download?.isActive == true) return
        download = viewModelScope.launch { models.download(prefs.settings.value.wifiOnlyDownload) }
    }

    fun cancelDownload() { download?.cancel(); models.refresh() }
    fun deleteModel() { download?.cancel(); models.delete() }
}

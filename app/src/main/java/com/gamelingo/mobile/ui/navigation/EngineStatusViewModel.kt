package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import com.gamelingo.mobile.audio.AsrModelManager
import com.gamelingo.mobile.audio.AudioPrefs
import com.gamelingo.mobile.core.engine.EngineStatus
import com.gamelingo.mobile.core.engine.EngineStatusBuilder
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.translate.local.LocalEngineChain
import com.gamelingo.mobile.translate.local.LocalModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@HiltViewModel
class EngineStatusViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val models: LocalModelManager,
    private val chain: LocalEngineChain,
    private val asr: AsrModelManager,
    private val audio: AudioPrefs,
) : ViewModel() {
    suspend fun snapshot(): EngineStatus {
        val s = settings.settings.first()
        val a = audio.settings.value
        return EngineStatusBuilder.build(
            plannedEngineId = chain.plannedLocalEngineId(),
            gemmaOptIn = s.gemmaHdOptIn,
            gemma = models.stateOf(LocalModelManager.MODEL_TRANSLATEGEMMA),
            marian = models.stateOf(LocalModelManager.MODEL_MARIAN),
            asr = asr.state.value,
            speechEnabled = a.speechEnabled,
            ttsEnabled = a.ttsEnabled,
        )
    }
}

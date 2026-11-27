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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    suspend fun snapshot(): EngineStatus {
        val s = settings.settings.first()
        val a = audio.settings.value
        val built = EngineStatusBuilder.build(
            plannedEngineId = chain.plannedLocalEngineId(),
            gemmaOptIn = s.gemmaHdOptIn,
            gemma = models.stateOf(LocalModelManager.MODEL_TRANSLATEGEMMA),
            marian = models.stateOf(LocalModelManager.MODEL_MARIAN),
            asr = asr.state.value,
            speechEnabled = a.speechEnabled,
            ttsEnabled = a.ttsEnabled,
        )
        val mem = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mem)
        fun loadedMb(key: com.gamelingo.mobile.core.engine.EngineKey) =
            built.rows.firstOrNull { it.key == key && it.health == com.gamelingo.mobile.core.engine.EngineHealth.READY }
                ?.sizeBytes?.div(1_000_000)
        return built.copy(
            advice = com.gamelingo.mobile.core.engine.ResourceAdvisor.advise(
                availableMb = mem.availMem / 1_000_000,
                totalMb = mem.totalMem / 1_000_000,
                systemLowMemory = mem.lowMemory,
                appPssMb = runCatching {
                    val info = android.os.Debug.MemoryInfo()
                    android.os.Debug.getMemoryInfo(info)
                    info.totalPss / 1_000L
                }.getOrNull(),
                gemmaFilesMb = loadedMb(com.gamelingo.mobile.core.engine.EngineKey.GEMMA),
                marianFilesMb = loadedMb(com.gamelingo.mobile.core.engine.EngineKey.MARIAN),
                speechOn = a.speechEnabled,
            ),
        )
    }
}

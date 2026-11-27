package com.gamelingo.mobile.core.engine

import com.gamelingo.mobile.audio.AsrModelState
import com.gamelingo.mobile.translate.local.LocalAiModelState

enum class EngineKey { GEMMA, MARIAN, ASR, TTS }

enum class EngineHealth { READY, OFF, NOT_INSTALLED, DOWNLOADING, VERIFYING, FAILED }

/** [detail] is a size in bytes for READY, a percentage for DOWNLOADING, a reason for FAILED; null otherwise. */
data class EngineRow(val key: EngineKey, val health: EngineHealth, val sizeBytes: Long? = null, val percent: Int? = null, val reason: String? = null)

data class EngineStatus(val plannedEngineId: String, val rows: List<EngineRow>, val advice: ResourceAdvisor.Advice? = null)

/** Pure read-only summary of what each engine is doing. It never starts a download or changes a setting. */
object EngineStatusBuilder {
    fun build(
        plannedEngineId: String,
        gemmaOptIn: Boolean,
        gemma: LocalAiModelState,
        marian: LocalAiModelState,
        asr: AsrModelState,
        speechEnabled: Boolean,
        ttsEnabled: Boolean,
    ): EngineStatus = EngineStatus(
        plannedEngineId,
        listOf(
            local(EngineKey.GEMMA, gemma, enabled = gemmaOptIn),
            local(EngineKey.MARIAN, marian, enabled = true),
            asrRow(asr, speechEnabled),
            EngineRow(EngineKey.TTS, if (ttsEnabled) EngineHealth.READY else EngineHealth.OFF),
        ),
    )

    private fun pct(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

    private fun local(key: EngineKey, s: LocalAiModelState, enabled: Boolean): EngineRow = when (s) {
        LocalAiModelState.NotInstalled -> EngineRow(key, EngineHealth.NOT_INSTALLED)
        is LocalAiModelState.Downloading -> EngineRow(key, EngineHealth.DOWNLOADING, percent = pct(s.downloadedBytes, s.totalBytes))
        LocalAiModelState.Verifying -> EngineRow(key, EngineHealth.VERIFYING)
        is LocalAiModelState.Installed ->
            EngineRow(key, if (enabled) EngineHealth.READY else EngineHealth.OFF, sizeBytes = s.sizeBytes)
        is LocalAiModelState.Failed -> EngineRow(key, EngineHealth.FAILED, reason = s.reason)
    }

    private fun asrRow(s: AsrModelState, speechEnabled: Boolean): EngineRow = when (s) {
        AsrModelState.NotInstalled -> EngineRow(EngineKey.ASR, EngineHealth.NOT_INSTALLED)
        is AsrModelState.Downloading -> EngineRow(EngineKey.ASR, EngineHealth.DOWNLOADING, percent = pct(s.downloadedBytes, s.totalBytes))
        AsrModelState.Verifying -> EngineRow(EngineKey.ASR, EngineHealth.VERIFYING)
        is AsrModelState.Installed ->
            EngineRow(EngineKey.ASR, if (speechEnabled) EngineHealth.READY else EngineHealth.OFF, sizeBytes = s.sizeBytes)
        is AsrModelState.Failed -> EngineRow(EngineKey.ASR, EngineHealth.FAILED, reason = s.reason)
    }
}

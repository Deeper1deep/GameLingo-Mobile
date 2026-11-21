package com.gamelingo.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.status.EngineReadiness
import com.gamelingo.mobile.core.status.SessionPhase
import com.gamelingo.mobile.translate.GeminiTranslationEngine
import com.gamelingo.mobile.translate.MlKitTranslationEngine
import com.gamelingo.mobile.ui.components.StatusTone

/** Shared text/tone mapping for the status UI, so every screen words a state the same way. */

@Composable
fun phaseLabel(phase: SessionPhase): String = stringResource(
    when (phase) {
        SessionPhase.READY -> R.string.phase_ready
        SessionPhase.CAPTURING -> R.string.phase_capturing
        SessionPhase.TRANSLATING -> R.string.phase_translating
        SessionPhase.ERROR -> R.string.phase_error
    },
)

@Composable
fun phaseDescription(phase: SessionPhase): String = stringResource(
    when (phase) {
        SessionPhase.READY -> R.string.phase_ready_desc
        SessionPhase.CAPTURING -> R.string.phase_capturing_desc
        SessionPhase.TRANSLATING -> R.string.phase_translating_desc
        SessionPhase.ERROR -> R.string.phase_error_desc
    },
)

fun phaseTone(phase: SessionPhase): StatusTone = when (phase) {
    SessionPhase.READY -> StatusTone.Info
    SessionPhase.CAPTURING -> StatusTone.Success
    SessionPhase.TRANSLATING -> StatusTone.Accent
    SessionPhase.ERROR -> StatusTone.Error
}

/** Live phases get a pulsing dot. */
fun phaseIsLive(phase: SessionPhase): Boolean =
    phase == SessionPhase.CAPTURING || phase == SessionPhase.TRANSLATING

@Composable
fun engineReadinessShort(readiness: EngineReadiness): String = stringResource(
    when (readiness) {
        EngineReadiness.LOCAL -> R.string.value_ai_local
        EngineReadiness.ONLINE_READY -> R.string.value_ai_online
        EngineReadiness.ONLINE_NOT_CONFIGURED -> R.string.value_ai_not_ready
        EngineReadiness.AUTO_ONLINE -> R.string.value_ai_auto_online
        EngineReadiness.AUTO_LOCAL -> R.string.value_ai_auto_local
    },
)

@Composable
fun engineReadinessLong(readiness: EngineReadiness): String = stringResource(
    when (readiness) {
        EngineReadiness.LOCAL -> R.string.engine_local
        EngineReadiness.ONLINE_READY -> R.string.engine_online_ready
        EngineReadiness.ONLINE_NOT_CONFIGURED -> R.string.engine_online_not_configured
        EngineReadiness.AUTO_ONLINE -> R.string.engine_auto_online
        EngineReadiness.AUTO_LOCAL -> R.string.engine_auto_local
    },
)

fun engineReadinessTone(readiness: EngineReadiness): StatusTone = when (readiness) {
    EngineReadiness.LOCAL -> StatusTone.Info
    EngineReadiness.ONLINE_READY -> StatusTone.Success
    EngineReadiness.ONLINE_NOT_CONFIGURED -> StatusTone.Error
    EngineReadiness.AUTO_ONLINE -> StatusTone.Success
    EngineReadiness.AUTO_LOCAL -> StatusTone.Info
}

/** Human name of the engine id recorded with a translation. */
@Composable
fun engineIdLabel(engineId: String): String = when (engineId) {
    GeminiTranslationEngine.ENGINE_ID -> stringResource(R.string.engine_label_online)
    MlKitTranslationEngine.ENGINE_ID -> stringResource(R.string.engine_label_local)
    else -> stringResource(R.string.engine_label_other, engineId)
}

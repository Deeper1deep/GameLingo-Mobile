package com.gamelingo.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.status.EngineReadiness
import com.gamelingo.mobile.core.status.SessionPhase
import com.gamelingo.mobile.translate.GeminiTranslationEngine
import com.gamelingo.mobile.translate.baseEngineId
import com.gamelingo.mobile.translate.local.LocalAiTranslationEngine
import com.gamelingo.mobile.translate.local.TranslateGemmaEngine
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
fun engineIdLabel(engineId: String): String = when (baseEngineId(engineId)) {
    GeminiTranslationEngine.ENGINE_ID -> stringResource(R.string.engine_label_online)
    MlKitTranslationEngine.ENGINE_ID -> stringResource(R.string.engine_label_local)
    LocalAiTranslationEngine.ENGINE_ID -> stringResource(R.string.engine_label_marian)
    TranslateGemmaEngine.ENGINE_ID -> stringResource(R.string.engine_label_gemma)
    else -> stringResource(R.string.engine_label_other, engineId)
}

/** User-facing explanation of why a session ended, shared by every screen that shows it. */
@androidx.annotation.StringRes
fun sessionErrorMessageRes(error: com.gamelingo.mobile.core.session.SessionError): Int = when (error) {
    com.gamelingo.mobile.core.session.SessionError.MISSING_CONSENT_DATA ->
        R.string.session_error_missing_consent
    com.gamelingo.mobile.core.session.SessionError.FOREGROUND_START_FAILED ->
        R.string.session_error_foreground_failed
    com.gamelingo.mobile.core.session.SessionError.PROJECTION_REJECTED ->
        R.string.session_error_projection_rejected
    com.gamelingo.mobile.core.session.SessionError.CAPTURE_PERSISTENT_FAILURE ->
        R.string.session_error_capture_persistent
    com.gamelingo.mobile.core.session.SessionError.SETTINGS_UNAVAILABLE ->
        R.string.session_error_settings_unavailable
    com.gamelingo.mobile.core.session.SessionError.OVERLAY_PERMISSION_LOST ->
        R.string.session_error_overlay_permission
    com.gamelingo.mobile.core.session.SessionError.OVERLAY_WINDOW_FAILED ->
        R.string.session_error_overlay_window
    com.gamelingo.mobile.core.session.SessionError.PROJECTION_ENDED ->
        R.string.session_error_projection_ended
}

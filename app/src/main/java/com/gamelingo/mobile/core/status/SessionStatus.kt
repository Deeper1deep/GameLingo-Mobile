package com.gamelingo.mobile.core.status

import com.gamelingo.mobile.core.session.OcrLine
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslatedLine
import com.gamelingo.mobile.data.settings.AiMode

/**
 * What the translation system is doing right now, derived only from the real
 * signals the capture service already publishes. Pure (no Android imports) so
 * the mapping is unit-testable.
 */
enum class SessionPhase {
    /** No session is running. */
    READY,

    /** Session running and waiting for the next subtitle line from the screen. */
    CAPTURING,

    /** A line was recognized and its translation has not arrived yet. */
    TRANSLATING,

    /** The session errored, or the last line could not be translated. */
    ERROR,
}

fun resolveSessionPhase(
    state: SessionState,
    latestLine: OcrLine?,
    translatedLine: TranslatedLine?,
    translationFailure: String?,
): SessionPhase = when (state) {
    SessionState.ERROR -> SessionPhase.ERROR
    SessionState.STOPPED -> SessionPhase.READY
    SessionState.RUNNING -> when {
        translationFailure != null -> SessionPhase.ERROR
        latestLine != null &&
            (translatedLine == null || translatedLine.capturedAtMillis < latestLine.capturedAtMillis) ->
            SessionPhase.TRANSLATING
        else -> SessionPhase.CAPTURING
    }
}

/** Which engine the next translation would use under the current settings. */
enum class EngineReadiness {
    /** LOCAL mode: on-device engine, no network. */
    LOCAL,

    /** ONLINE mode with a key and cloud consent. */
    ONLINE_READY,

    /** ONLINE mode but the key or the cloud consent is missing: translation fails visibly. */
    ONLINE_NOT_CONFIGURED,

    /** AUTO mode with a key and consent: online first, on-device as fallback. */
    AUTO_ONLINE,

    /** AUTO mode without a key or consent: effectively on-device. */
    AUTO_LOCAL,
}

/** Mirrors the routing rules of the hybrid engine without touching it. */
fun resolveEngineReadiness(
    mode: AiMode,
    cloudOptIn: Boolean,
    hasApiKey: Boolean,
): EngineReadiness {
    val cloudAllowed = cloudOptIn && hasApiKey
    return when (mode) {
        AiMode.LOCAL -> EngineReadiness.LOCAL
        AiMode.ONLINE ->
            if (cloudAllowed) EngineReadiness.ONLINE_READY else EngineReadiness.ONLINE_NOT_CONFIGURED
        AiMode.AUTO ->
            if (cloudAllowed) EngineReadiness.AUTO_ONLINE else EngineReadiness.AUTO_LOCAL
    }
}

/** Formats a session duration as m:ss or h:mm:ss. */
fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

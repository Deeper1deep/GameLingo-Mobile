package com.gamelingo.mobile.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a screen-capture/translation session. */
enum class SessionState {
    STOPPED,
    RUNNING,
    ERROR,
}

/**
 * A user-visible session notice the UI maps to an Arabic string. Distinct
 * from failures: the session keeps running, but the user must know the
 * translation path is not what they picked.
 */
enum class SessionNotice {
    /** ONLINE was selected without an API key or cloud consent. */
    ONLINE_NOT_CONFIGURED,

    /** Online translation failed and AUTO quietly used the on-device engine. */
    FELL_BACK_TO_LOCAL,
}

/** One accepted OCR line, ready for the translation engine (Phase 4). */
data class OcrLine(
    val text: String,
    val capturedAtMillis: Long,
)

/** One finished translation, ready for the floating window (Phase 6). */
data class TranslatedLine(
    val original: String,
    val translated: String,
    val engineId: String,
    val fromCache: Boolean,
    val capturedAtMillis: Long,
)

/**
 * Process-wide bus connecting the capture service, the (future) translation engine
 * and the UI. The capture service publishes; the overlay and home screen subscribe.
 */
object TranslationSessionBus {
    private val _state = MutableStateFlow(SessionState.STOPPED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _latestLine = MutableStateFlow<OcrLine?>(null)
    val latestLine: StateFlow<OcrLine?> = _latestLine.asStateFlow()

    private val _translatedLine = MutableStateFlow<TranslatedLine?>(null)
    val translatedLine: StateFlow<TranslatedLine?> = _translatedLine.asStateFlow()

    /** Original text of the last line whose translation failed, or null. */
    private val _translationFailure = MutableStateFlow<String?>(null)
    val translationFailure: StateFlow<String?> = _translationFailure.asStateFlow()

    /** Latest user-visible notice (mode mismatch, silent fallback), or null. */
    private val _notice = MutableStateFlow<SessionNotice?>(null)
    val notice: StateFlow<SessionNotice?> = _notice.asStateFlow()

    fun onSessionStarted() {
        _state.value = SessionState.RUNNING
    }

    fun onSessionStopped() {
        _state.value = SessionState.STOPPED
        _latestLine.value = null
        _translatedLine.value = null
        _translationFailure.value = null
        _notice.value = null
    }

    fun onSessionError() {
        _state.value = SessionState.ERROR
    }

    fun onNewLine(text: String, capturedAtMillis: Long) {
        _latestLine.value = OcrLine(text = text, capturedAtMillis = capturedAtMillis)
        _translationFailure.value = null
    }

    fun onTranslated(
        original: String,
        translated: String,
        engineId: String,
        fromCache: Boolean,
        capturedAtMillis: Long,
    ) {
        _translatedLine.value = TranslatedLine(
            original = original,
            translated = translated,
            engineId = engineId,
            fromCache = fromCache,
            capturedAtMillis = capturedAtMillis,
        )
        _translationFailure.value = null
    }

    fun onTranslationFailed(original: String) {
        _translationFailure.value = original
    }

    fun onNotice(notice: SessionNotice) {
        _notice.value = notice
    }
}

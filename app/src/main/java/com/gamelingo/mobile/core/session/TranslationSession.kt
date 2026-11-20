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

/** One accepted OCR line, ready for the translation engine (Phase 4). */
data class OcrLine(
    val text: String,
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

    fun onSessionStarted() {
        _state.value = SessionState.RUNNING
    }

    fun onSessionStopped() {
        _state.value = SessionState.STOPPED
        _latestLine.value = null
    }

    fun onSessionError() {
        _state.value = SessionState.ERROR
    }

    fun onNewLine(text: String, capturedAtMillis: Long) {
        _latestLine.value = OcrLine(text = text, capturedAtMillis = capturedAtMillis)
    }
}

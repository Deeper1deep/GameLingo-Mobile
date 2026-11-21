package com.gamelingo.mobile.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * The reason a session went to ERROR. Kept apart from the session state
 * itself: the teardown that follows a failure flips the state back to
 * STOPPED, but the user must still see WHY the session died, so the reason
 * survives until the next start or an explicit dismiss.
 */
enum class SessionError {
    /** The consent intent extras never reached the capture service. */
    MISSING_CONSENT_DATA,

    /** startForeground failed before the projection handoff. */
    FOREGROUND_START_FAILED,

    /** getMediaProjection rejected the consent token (reused or revoked). */
    PROJECTION_REJECTED,

    /** Frame reads or OCR failed persistently, not once. */
    CAPTURE_PERSISTENT_FAILURE,

    /** The settings store could not be read inside the capture loop. */
    SETTINGS_UNAVAILABLE,

    /** The overlay permission was missing or revoked while running. */
    OVERLAY_PERMISSION_LOST,

    /** WindowManager refused the overlay view. */
    OVERLAY_WINDOW_FAILED,

    /**
     * The system ended the MediaProjection itself (token expired or consent
     * revoked). A genuine expiry: the session is over and needs a fresh
     * consent grant, not a silent retry.
     */
    PROJECTION_ENDED,
}

/**
 * Explicit per-request translation states. Replaces any single boolean: the
 * UI and the watchdog can always tell a queued line from a translating one,
 * a rate limit from an auth failure, and a recovery in progress.
 */
enum class RequestState {
    IDLE,
    QUEUED,
    TRANSLATING,
    SUCCESS,
    NETWORK_ERROR,
    RATE_LIMITED,
    AUTH_ERROR,
    TIMEOUT,
    LOCAL_FALLBACK,
    RECOVERING,
}

/** Observable status of the translation worker (see TranslationWorker). */
data class WorkerStatus(
    val state: RequestState = RequestState.IDLE,
    val activeRequestId: Long? = null,
    val queueDepth: Int = 0,
    val lastSuccessAtMillis: Long = -1,
    val lastError: String? = null,
    val recoveryCount: Int = 0,
)

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

<<<<<<< redesign/premium-ui
    /** Wall-clock start of the running session, or null when stopped. UI-only statistic. */
    private val _startedAtMillis = MutableStateFlow<Long?>(null)
    val startedAtMillis: StateFlow<Long?> = _startedAtMillis.asStateFlow()

    /** Lines translated since the session started. UI-only statistic. */
    private val _translatedCount = MutableStateFlow(0)
    val translatedCount: StateFlow<Int> = _translatedCount.asStateFlow()

    fun onSessionStarted() {
        if (_state.value != SessionState.RUNNING) {
            _startedAtMillis.value = System.currentTimeMillis()
            _translatedCount.value = 0
        }
=======
    /**
     * Why the session last failed, or null. Survives [onSessionStopped]: the
     * teardown after a failure must not erase the explanation the user needs.
     * Cleared by the next [onSessionStarted] or by [dismissError].
     */
    private val _error = MutableStateFlow<SessionError?>(null)
    val error: StateFlow<SessionError?> = _error.asStateFlow()

    fun onSessionStarted() {
        _error.value = null
>>>>>>> main
        _state.value = SessionState.RUNNING
    }

    fun onSessionStopped() {
        _state.value = SessionState.STOPPED
        _startedAtMillis.value = null
        _translatedCount.value = 0
        _latestLine.value = null
        _translatedLine.value = null
        _translationFailure.value = null
        _notice.value = null
        _workerStatus.value = WorkerStatus()
    }

    fun onSessionError(reason: SessionError) {
        _error.value = reason
        _state.value = SessionState.ERROR
    }

    /** Hides the current error reason; the state settles back to STOPPED. */
    fun dismissError() {
        _error.value = null
        if (_state.value == SessionState.ERROR) {
            _state.value = SessionState.STOPPED
        }
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
        _translatedCount.update { it + 1 }
    }

    fun onTranslationFailed(original: String) {
        _translationFailure.value = original
    }

    fun onNotice(notice: SessionNotice) {
        _notice.value = notice
    }

    /** Latest translation-worker status (state, queue depth, recoveries). */
    private val _workerStatus = MutableStateFlow(WorkerStatus())
    val workerStatus: StateFlow<WorkerStatus> = _workerStatus.asStateFlow()

    fun onWorkerStatus(status: WorkerStatus) {
        _workerStatus.value = status
    }
}

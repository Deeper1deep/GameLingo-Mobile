package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import com.gamelingo.mobile.core.session.OcrLine
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslatedLine
import com.gamelingo.mobile.core.session.TranslationSessionBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Exposes the capture/translation session state published by the capture service. */
@HiltViewModel
class SessionViewModel @Inject constructor() : ViewModel() {
    val sessionState: StateFlow<SessionState> = TranslationSessionBus.state
    val latestLine: StateFlow<OcrLine?> = TranslationSessionBus.latestLine
    val translatedLine: StateFlow<TranslatedLine?> = TranslationSessionBus.translatedLine
    val translationFailure: StateFlow<String?> = TranslationSessionBus.translationFailure
    val notice: StateFlow<SessionNotice?> = TranslationSessionBus.notice
}

package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.translate.AiConnectionTester
import com.gamelingo.mobile.translate.AiTestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AiTestUiState {
    data object Idle : AiTestUiState
    data object Running : AiTestUiState
    data class Done(val result: AiTestResult) : AiTestUiState
}

/** Runs the user-initiated connection test of the AI Engine screen. */
@HiltViewModel
class AiEngineViewModel @Inject constructor(
    private val tester: AiConnectionTester,
) : ViewModel() {

    private val _test = MutableStateFlow<AiTestUiState>(AiTestUiState.Idle)
    val test: StateFlow<AiTestUiState> = _test.asStateFlow()

    private var job: Job? = null

    fun runTest() {
        if (_test.value is AiTestUiState.Running) return
        _test.value = AiTestUiState.Running
        job = viewModelScope.launch {
            _test.value = AiTestUiState.Done(tester.test())
        }
    }

    fun clearResult() {
        if (_test.value is AiTestUiState.Done) _test.value = AiTestUiState.Idle
    }
}

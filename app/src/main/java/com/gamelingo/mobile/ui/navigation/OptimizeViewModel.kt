package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.core.optimize.OptimizationProfile
import com.gamelingo.mobile.data.optimize.OptimizationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OptimizeViewModel @Inject constructor(
    private val manager: OptimizationManager,
) : ViewModel() {
    val state = manager.state
    val hasBackup = manager.hasBackup

    fun optimize(profile: OptimizationProfile) {
        viewModelScope.launch { runCatching { manager.optimize(profile) } }
    }

    fun restore() {
        viewModelScope.launch { manager.restore() }
    }
}

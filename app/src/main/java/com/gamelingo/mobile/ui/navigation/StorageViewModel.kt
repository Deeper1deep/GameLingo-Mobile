package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.translate.TranslationCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Storage and cache actions for the Settings screen. */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val cache: TranslationCache,
    private val historyDao: TranslationHistoryDao,
) : ViewModel() {

    val historyCount: StateFlow<Int> = historyDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Empties the in-memory translation cache. History is never touched. */
    fun clearTranslationCache() = cache.clear()

    fun clearHistory() = viewModelScope.launch { historyDao.clear() }
}

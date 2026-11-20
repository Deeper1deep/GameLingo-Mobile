package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HistoryViewModel @Inject constructor(
    historyDao: TranslationHistoryDao,
) : ViewModel() {

    val history: StateFlow<List<TranslationHistoryEntity>> = historyDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyCount: StateFlow<Int> = historyDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

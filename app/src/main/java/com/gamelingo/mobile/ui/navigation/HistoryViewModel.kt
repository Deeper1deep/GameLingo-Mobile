package com.gamelingo.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamelingo.mobile.data.local.TranslationHistoryDao
import com.gamelingo.mobile.data.local.TranslationHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyDao: TranslationHistoryDao,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Current search text; blank means "show everything". */
    val searchQuery: StateFlow<String> = query

    val history: StateFlow<List<TranslationHistoryEntity>> = query
        .flatMapLatest { text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                historyDao.observeRecent()
            } else {
                historyDao.search(trimmed)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyCount: StateFlow<Int> = historyDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(text: String) {
        query.value = text
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { historyDao.deleteById(id) }

    fun clearAll() = viewModelScope.launch { historyDao.clear() }
}

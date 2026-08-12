package com.galleryorganizer.ui.suggestions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galleryorganizer.data.db.dao.SuggestionGroup
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SuggestionsViewModel(private val container: AppContainer) : ViewModel() {

    val groups: StateFlow<List<SuggestionGroup>> =
        container.database.suggestionDao().observePendingGroups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val enabled: Flow<Boolean> = container.settings.autoTagEnabled

    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining.asStateFlow()

    init {
        refreshRemaining()
    }

    fun refreshRemaining() {
        viewModelScope.launch {
            _remaining.value = container.database.mediaDao().unanalysedCount()
        }
    }

    fun accept(label: String) {
        viewModelScope.launch { container.autoTagger.acceptLabel(label) }
    }

    fun reject(label: String) {
        viewModelScope.launch { container.autoTagger.rejectLabel(label) }
    }

    fun enableAndStart() {
        viewModelScope.launch {
            container.settings.setAutoTagEnabled(true)
            WorkScheduler.enqueueAutoTag(container.appContext)
            refreshRemaining()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SuggestionsViewModel(container) as T
    }
}

package com.galleryorganizer.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.galleryorganizer.di.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class GalleryViewModel(
    private val container: AppContainer,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    val itemCount: StateFlow<Int> = container.mediaRepository.observePresentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The grid stream, with date headers spliced in by `insertSeparators` so sections
     * exist without ever materialising the full list.
     *
     * `cachedIn` keeps loaded pages across configuration changes; without it, rotating
     * the phone re-queries from the top and throws away the user's scroll position.
     */
    val entries: Flow<PagingData<GridEntry>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            // A grid shows ~4 rows of 4 at a time; prefetching two screens keeps flinging
            // smooth without holding much.
            prefetchDistance = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE * 2,
            enablePlaceholders = false,
            maxSize = PAGE_SIZE * 10,
        ),
        pagingSourceFactory = { container.database.mediaDao().pagingSourceAll(includeMissing = false) },
    ).flow
        .withDateHeaders(zone) { it.format(HEADER_FORMAT) }
        .cachedIn(viewModelScope)

    fun toggle(id: Long) {
        _selection.value = _selection.value.toggle(id)
    }

    fun beginDrag(index: Int, id: Long) {
        _selection.value = _selection.value.beginDrag(index, id)
    }

    fun extendDrag(ids: Collection<Long>) {
        _selection.value = _selection.value.extendDrag(ids)
    }

    fun endDrag() {
        _selection.value = _selection.value.endDrag()
    }

    fun clearSelection() {
        _selection.value = _selection.value.clear()
    }

    fun setSelection(ids: Collection<Long>) {
        _selection.value = _selection.value.replaceWith(ids)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GalleryViewModel(container) as T
    }

    companion object {
        const val PAGE_SIZE = 120
        private val HEADER_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    }
}

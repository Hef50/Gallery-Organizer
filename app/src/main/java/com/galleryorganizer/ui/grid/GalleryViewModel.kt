package com.galleryorganizer.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.sqlite.db.SimpleSQLiteQuery
import com.galleryorganizer.data.repo.SavedSearch
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel(
    private val container: AppContainer,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val search = container.searchRepository

    private val _query = MutableStateFlow(SearchQuery())
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    /** Which saved search, if any, the current query came from. */
    private val _activeSavedSearch = MutableStateFlow<SavedSearch?>(null)
    val activeSavedSearch: StateFlow<SavedSearch?> = _activeSavedSearch.asStateFlow()

    val savedSearches: StateFlow<List<SavedSearch>> = search.observeSaved()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val buckets = container.mediaRepository.observeBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Typing debounces before it reaches the database. Without this, every keystroke
     * cancels a Pager and starts a new FTS query over 150k rows.
     */
    private val debouncedQuery: Flow<SearchQuery> = _query
        .debounce { if (it.text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        // Hidden folders are folded in here rather than into _query, so they never end up
        // baked into a saved search — hiding a folder is a view preference, not part of
        // what the user asked for.
        .combine(container.settings.hiddenBucketIds) { query, hidden ->
            query.copy(excludedBucketIds = hidden.toList())
        }
        .distinctUntilChanged()

    val itemCount: StateFlow<Int> = debouncedQuery
        .flatMapLatest { search.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The grid stream. Date headers are only spliced in for date-ordered results — a
     * "largest first" list broken up by date headings would be nonsense.
     *
     * `cachedIn` keeps loaded pages across configuration changes; without it, rotating the
     * phone re-queries from the top and throws away the scroll position.
     */
    val entries: Flow<PagingData<GridEntry>> = debouncedQuery
        .flatMapLatest { query ->
            val statement = search.statementFor(query)
            val pager = Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    // A grid shows a handful of rows at a time; prefetching a screen ahead
                    // keeps flinging smooth without holding much.
                    prefetchDistance = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE * 2,
                    enablePlaceholders = false,
                    maxSize = PAGE_SIZE * 10,
                ),
                pagingSourceFactory = {
                    container.database.mediaDao().pagingSourceRaw(
                        SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()),
                    )
                },
            )
            if (query.sort.isChronological) {
                pager.flow.withDateHeaders(zone) { it.format(HEADER_FORMAT) }
            } else {
                pager.flow.withoutHeaders()
            }
        }
        .cachedIn(viewModelScope)

    // --- Query -------------------------------------------------------------------------

    fun setText(text: String) {
        _query.value = _query.value.copy(text = text)
        _activeSavedSearch.value = null
    }

    fun setQuery(query: SearchQuery) {
        _query.value = query
        _activeSavedSearch.value = null
        clearSelection()
    }

    fun clearQuery() {
        _query.value = SearchQuery()
        _activeSavedSearch.value = null
        clearSelection()
    }

    fun openSavedSearch(saved: SavedSearch) {
        _query.value = saved.query
        _activeSavedSearch.value = saved
        clearSelection()
    }

    fun saveCurrentSearch(name: String) {
        viewModelScope.launch { search.save(name, _query.value) }
    }

    fun deleteSavedSearch(id: Long) {
        viewModelScope.launch {
            search.delete(id)
            if (_activeSavedSearch.value?.id == id) _activeSavedSearch.value = null
        }
    }

    fun setSavedSearchPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch { search.setPinned(id, pinned) }
    }

    /** The quick-filter chips above the grid. */
    fun applyQuickFilter(filter: QuickFilter) {
        _activeSavedSearch.value = null
        _query.value = filter.apply(_query.value)
        clearSelection()
    }

    /** Selects everything the current filter matches, up to a bounded ceiling. */
    fun selectAllResults() {
        viewModelScope.launch {
            _selection.value = _selection.value.replaceWith(search.idsMatching(_query.value))
        }
    }

    // --- Selection ---------------------------------------------------------------------

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

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GalleryViewModel(container) as T
    }

    companion object {
        const val PAGE_SIZE = 120
        const val SEARCH_DEBOUNCE_MS = 250L

        private val HEADER_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

        private val SortOrder.isChronological: Boolean
            get() = this == SortOrder.NewestFirst || this == SortOrder.OldestFirst
    }
}

package com.galleryorganizer.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.sqlite.db.SimpleSQLiteQuery
import com.galleryorganizer.data.db.entity.MediaEntity
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
import kotlinx.coroutines.flow.filterNotNull
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
            hiddenBuckets = hidden.toList()
            query.copy(excludedBucketIds = hiddenBuckets)
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
                    /*
                     * Deliberately unbounded — there was a `maxSize` here and it did real
                     * damage, which `GridPagingWindowTest` now pins down.
                     *
                     * With placeholders off, a cap does not simply bound memory: pages
                     * dropped from the front cancel out pages appended at the back, so the
                     * list never grows and every position in it slides as you scroll. Two
                     * things followed. A long fling turned into a continuous load-and-drop
                     * cycle, re-querying photos it had just discarded, which is the deep
                     * scrolling that felt worst. And the index the grid handed the viewer
                     * stopped being an offset into the query, so once you had scrolled far
                     * enough, tapping a photo opened a different one.
                     *
                     * The cost of dropping the cap is memory: a row is a few hundred bytes,
                     * so flinging through the whole of a 150k library in one uninterrupted
                     * sitting would hold tens of megabytes. Any write to the library
                     * invalidates the source and releases it again, and the realistic case
                     * is a few thousand rows. Bounding this properly means turning
                     * placeholders on, which trades away the date headers — see
                     * OPEN_QUESTIONS.md.
                     */
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

    // --- Viewer -------------------------------------------------------------------------

    private val _viewer = MutableStateFlow<ViewerRequest?>(null)
    val viewer: StateFlow<ViewerRequest?> = _viewer.asStateFlow()

    /**
     * The viewer's own paged stream: the same query, without date headers.
     *
     * A second Pager rather than reusing the grid's, because the grid's stream has header
     * rows spliced into it and swiping onto one would land on a blank page. `initialKey`
     * is what makes opening the 40,000th photo instant — Room's paging source keys on
     * offset, so the first load is the page around that item rather than page zero
     * followed by a very long scroll.
     */
    val viewerEntries: Flow<PagingData<MediaEntity>> = _viewer
        .filterNotNull()
        .flatMapLatest { request ->
            val factory = pagingSourceFactoryFor(request.source)
            Pager(
                config = PagingConfig(
                    pageSize = VIEWER_PAGE_SIZE,
                    prefetchDistance = VIEWER_PAGE_SIZE,
                    initialLoadSize = VIEWER_PAGE_SIZE,
                    enablePlaceholders = false,
                ),
                initialKey = request.mediaIndex,
                pagingSourceFactory = factory,
            ).flow
        }
        .cachedIn(viewModelScope)

    /**
     * Swiping in the viewer has to stay inside whatever the user was looking at.
     *
     * Opening a photo from an album and then swiping into the rest of the library would be
     * a small betrayal of the album: the point of one is that it is a bounded, ordered set.
     * So the viewer pages over the *same* source the screen behind it did.
     *
     * Suspending, and returning a *factory*, because building the library's statement reads
     * the tag hierarchy — Paging calls the factory again on every invalidation and cannot
     * suspend when it does, so the query is resolved once, here.
     */
    private suspend fun pagingSourceFactoryFor(
        source: ViewerSource,
    ): () -> PagingSource<Int, MediaEntity> = when (source) {
        is ViewerSource.Library -> {
            val statement =
                search.statementFor(_query.value.copy(excludedBucketIds = hiddenBuckets));
            {
                container.database.mediaDao().pagingSourceRaw(
                    SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()),
                )
            }
        }

        is ViewerSource.Album -> {
            { container.database.albumDao().pagingSourceFor(source.albumId) }
        }

        is ViewerSource.Ids -> {
            { container.database.mediaDao().pagingSourceForIds(source.ids) }
        }
    }

    private var hiddenBuckets: List<Long> = emptyList()

    /**
     * @param mediaIndex position among *photos only*, which is the grid index minus the
     *   date headers before it. The viewer's pager has no headers, so this is the page.
     */
    fun openViewer(mediaId: Long, mediaIndex: Int) {
        _viewer.value = ViewerRequest(mediaId, mediaIndex, ViewerSource.Library)
    }

    fun openAlbumViewer(albumId: Long, mediaId: Long, mediaIndex: Int) {
        _viewer.value = ViewerRequest(mediaId, mediaIndex, ViewerSource.Album(albumId))
    }

    /** For a set the user is looking at that has no query behind it — a map cluster. */
    fun openIdsViewer(ids: List<Long>, mediaId: Long) {
        val index = ids.indexOf(mediaId).coerceAtLeast(0)
        _viewer.value = ViewerRequest(mediaId, index, ViewerSource.Ids(ids))
    }

    fun closeViewer() {
        _viewer.value = null
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
        const val VIEWER_PAGE_SIZE = 12
        const val SEARCH_DEBOUNCE_MS = 250L

        private val HEADER_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

        private val SortOrder.isChronological: Boolean
            get() = this == SortOrder.NewestFirst || this == SortOrder.OldestFirst
    }
}

/** Which photo the viewer opened on, and where it sits in [source]. */
data class ViewerRequest(
    val mediaId: Long,
    val mediaIndex: Int,
    val source: ViewerSource = ViewerSource.Library,
)

/** What the viewer swipes through. */
sealed interface ViewerSource {
    /** The library under the current search and filters — the ordinary case. */
    data object Library : ViewerSource

    /** One album, in the user's own order. */
    data class Album(val albumId: Long) : ViewerSource

    /**
     * An explicit, bounded set — a map cluster. Bounded on purpose: this goes into an
     * `IN (...)` clause, so the caller must keep it well under SQLite's variable ceiling.
     */
    data class Ids(val ids: List<Long>) : ViewerSource
}

package com.galleryorganizer.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.galleryorganizer.data.db.dao.AlbumSummary
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.repo.AlbumCheckState
import com.galleryorganizer.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the last album action did, so the snackbar can undo it. */
data class AlbumUndo(
    val albumId: Long,
    val albumName: String,
    val mediaIds: Set<Long>,
    val wasAdded: Boolean,
)

class AlbumViewModel(private val container: AppContainer) : ViewModel() {

    private val albums = container.albumRepository

    val shelf: StateFlow<List<AlbumSummary>> = albums.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openAlbumId = MutableStateFlow<Long?>(null)
    val openAlbumId: StateFlow<Long?> = _openAlbumId.asStateFlow()

    val openAlbum: StateFlow<AlbumSummary?> =
        combine(_openAlbumId, shelf) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The open album's contents, paged.
     *
     * `flatMapLatest` over the id rather than a Pager per album: opening a second album has
     * to abandon the first one's pages, and holding both would keep a few hundred decoded
     * thumbnails alive for a screen nobody is looking at.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val albumItems: Flow<PagingData<MediaEntity>> = _openAlbumId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = PAGE_SIZE,
                        enablePlaceholders = false,
                    ),
                    pagingSourceFactory = { albums.pagingSourceFor(id) },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    private val _coverage = MutableStateFlow<Map<Long, AlbumCheckState>>(emptyMap())
    val coverage: StateFlow<Map<Long, AlbumCheckState>> = _coverage.asStateFlow()

    private val _lastAction = MutableStateFlow<AlbumUndo?>(null)
    val lastAction: StateFlow<AlbumUndo?> = _lastAction.asStateFlow()

    fun open(albumId: Long) { _openAlbumId.value = albumId }

    fun close() { _openAlbumId.value = null }

    fun refreshCoverage(mediaIds: Set<Long>) {
        viewModelScope.launch { _coverage.value = albums.coverage(mediaIds) }
    }

    /**
     * Adds to an album, or removes when everything selected is already in it — the same
     * "tap means do the obvious thing" rule the tag sheet follows.
     */
    fun toggleMembership(albumId: Long, albumName: String, mediaIds: Set<Long>) {
        val current = _coverage.value[albumId] ?: AlbumCheckState.None
        setMembership(albumId, albumName, mediaIds, add = current != AlbumCheckState.All)
    }

    fun setMembership(albumId: Long, albumName: String, mediaIds: Set<Long>, add: Boolean) {
        if (mediaIds.isEmpty()) return
        viewModelScope.launch {
            albums.setMembership(albumId, mediaIds, add)
            _coverage.value = _coverage.value +
                (albumId to if (add) AlbumCheckState.All else AlbumCheckState.None)
            _lastAction.value = AlbumUndo(albumId, albumName, mediaIds, add)
        }
    }

    fun createAndAdd(name: String, mediaIds: Set<Long>) {
        viewModelScope.launch {
            val id = albums.ensureAlbum(name)
            albums.addTo(id, mediaIds)
            _coverage.value = _coverage.value + (id to AlbumCheckState.All)
            _lastAction.value = AlbumUndo(id, name.trim(), mediaIds, wasAdded = true)
        }
    }

    fun create(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch { onCreated(albums.ensureAlbum(name)) }
    }

    fun undoLast() {
        val action = _lastAction.value ?: return
        viewModelScope.launch {
            albums.setMembership(action.albumId, action.mediaIds, member = !action.wasAdded)
            _coverage.value = _coverage.value + (
                action.albumId to
                    if (action.wasAdded) AlbumCheckState.None else AlbumCheckState.All
                )
            _lastAction.value = null
        }
    }

    fun consumeUndo() { _lastAction.value = null }

    fun rename(albumId: Long, name: String) {
        viewModelScope.launch { albums.rename(albumId, name) }
    }

    fun setDescription(albumId: Long, description: String) {
        viewModelScope.launch { albums.setDescription(albumId, description) }
    }

    fun setCover(albumId: Long, mediaId: Long?) {
        viewModelScope.launch { albums.setCover(albumId, mediaId) }
    }

    fun removeFromOpenAlbum(mediaIds: Set<Long>) {
        val id = _openAlbumId.value ?: return
        viewModelScope.launch { albums.removeFrom(id, mediaIds) }
    }

    fun delete(albumId: Long) {
        viewModelScope.launch {
            if (_openAlbumId.value == albumId) _openAlbumId.value = null
            albums.delete(albumId)
        }
    }

    /** Ids in album order, for "select everything in this album". */
    suspend fun idsIn(albumId: Long): List<Long> = albums.mediaIdsIn(albumId)

    private companion object {
        const val PAGE_SIZE = 120
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumViewModel(container) as T
    }
}

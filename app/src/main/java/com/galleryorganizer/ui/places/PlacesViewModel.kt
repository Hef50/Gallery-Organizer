package com.galleryorganizer.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galleryorganizer.data.db.dao.MediaPoint
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.domain.places.Bounds
import com.galleryorganizer.domain.places.PlaceCluster
import com.galleryorganizer.domain.places.PlaceZoom
import com.galleryorganizer.domain.places.clusterPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlacesState(
    val loading: Boolean = true,
    val points: List<MediaPoint> = emptyList(),
    val clusters: List<PlaceCluster> = emptyList(),
    val zoom: PlaceZoom = PlaceZoom.City,
    val frame: Bounds = Bounds.World,
    /** Still to be examined by the EXIF pass, so the screen can say "still looking". */
    val pending: Int = 0,
)

class PlacesViewModel(private val container: AppContainer) : ViewModel() {

    private val locations = container.locationRepository

    private val _state = MutableStateFlow(PlacesState())
    val state: StateFlow<PlacesState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<PlaceCluster?>(null)
    val selected: StateFlow<PlaceCluster?> = _selected.asStateFlow()

    private val _selectedItems = MutableStateFlow<List<MediaEntity>>(emptyList())
    val selectedItems: StateFlow<List<MediaEntity>> = _selectedItems.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val points = locations.points()
            val pending = locations.remaining()
            val frame = Bounds.around(points).expandedBy(0.12)
            val zoom = PlaceZoom.forSpan(frame.latSpan)
            // Clustering a whole library is a tight loop over primitives, but "tight" at
            // 150k points is still not something to do on the frame the user is waiting on.
            val clusters = withContext(Dispatchers.Default) { clusterPoints(points, zoom) }
            _state.value = PlacesState(
                loading = false,
                points = points,
                clusters = clusters,
                zoom = zoom,
                frame = frame,
                pending = pending,
            )
        }
    }

    fun setZoom(zoom: PlaceZoom) {
        val current = _state.value
        viewModelScope.launch {
            val clusters = withContext(Dispatchers.Default) { clusterPoints(current.points, zoom) }
            _state.value = current.copy(zoom = zoom, clusters = clusters)
            _selected.value = null
            _selectedItems.value = emptyList()
        }
    }

    fun select(cluster: PlaceCluster?) {
        _selected.value = cluster
        _selectedItems.value = emptyList()
        if (cluster == null) return
        viewModelScope.launch { _selectedItems.value = locations.itemsIn(cluster, limit = 60) }
    }

    /**
     * Names the selected cluster, which tags everything in it. See
     * [com.galleryorganizer.data.repo.LocationRepository.nameCluster] for why naming is the
     * whole feature rather than a nicety.
     */
    fun nameSelected(name: String, onDone: (Int) -> Unit = {}) {
        val cluster = _selected.value ?: return
        viewModelScope.launch {
            val tagged = locations.nameCluster(
                cluster,
                name,
                com.galleryorganizer.data.db.entity.TagEntity.ROOT_PARENT_ID,
            )
            onDone(tagged)
        }
    }

    /** Every id in the selected cluster, for handing a whole place to the tag sheet. */
    suspend fun idsInSelected(): Set<Long> {
        val cluster = _selected.value ?: return emptySet()
        return locations.itemsIn(cluster, limit = 20_000).mapTo(HashSet()) { it.id }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlacesViewModel(container) as T
    }
}

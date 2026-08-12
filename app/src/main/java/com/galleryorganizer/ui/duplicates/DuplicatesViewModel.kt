package com.galleryorganizer.ui.duplicates

import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One set of byte-identical items.
 *
 * [original] is the oldest by `date_added` and is never offered for removal: it is the copy
 * whose path anything else on the device is most likely to reference.
 */
data class DuplicateGroup(
    val hash: String,
    val all: List<MediaEntity>,
) {
    val original: MediaEntity = all.minByOrNull { it.dateAdded } ?: all.first()
    val copies: List<MediaEntity> = all.filter { it.id != original.id }
    val reclaimable: Long = copies.sumOf { it.size }
}

data class DuplicatesState(
    val scanning: Boolean = true,
    val hashing: Boolean = false,
    val unhashed: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
)

class DuplicatesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val groups = container.mediaRepository.duplicateGroups()
                .map { DuplicateGroup(it.first().contentHash.orEmpty(), it) }
                .filter { it.copies.isNotEmpty() }
                .sortedByDescending { it.reclaimable }
            _state.value = DuplicatesState(
                scanning = false,
                unhashed = container.mediaRepository.unhashedCount(),
                groups = groups,
            )
        }
    }

    /**
     * Hashes the rest of the library on demand. The background worker does this anyway
     * while the phone is idle, but someone who has opened this screen is waiting.
     */
    fun hashEverything() {
        viewModelScope.launch {
            _state.value = _state.value.copy(hashing = true)
            while (container.mediaRepository.hashNextBatch(HASH_BATCH) > 0) {
                _state.value = _state.value.copy(
                    unhashed = container.mediaRepository.unhashedCount(),
                )
            }
            _state.value = _state.value.copy(hashing = false)
            refresh()
        }
    }

    /**
     * Asks Android to move the extra copies to the trash — recoverable for 30 days rather
     * than deleted. The system shows its own confirmation; this app cannot bypass it, and
     * would not want to.
     */
    fun requestTrash(group: DuplicateGroup, launch: (IntentSender) -> Unit) {
        val uris: List<Uri> = group.copies.map { Uri.parse(it.uri) }
        if (uris.isEmpty()) return
        pendingTrash = group
        launch(
            MediaStore.createTrashRequest(
                container.appContext.contentResolver,
                uris,
                true,
            ).intentSender,
        )
    }

    private var pendingTrash: DuplicateGroup? = null

    fun onTrashResult(granted: Boolean) {
        val group = pendingTrash ?: return
        pendingTrash = null
        if (!granted) return
        viewModelScope.launch {
            // The rows are flagged missing, not deleted: the tags on a trashed copy stay
            // in the app in case the user restores it. The next index pass would do this
            // anyway; doing it now keeps the UI honest.
            container.database.mediaDao().markMissing(group.copies.map { it.id })
            refresh()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DuplicatesViewModel(container) as T
    }

    private companion object {
        const val HASH_BATCH = 200
    }
}

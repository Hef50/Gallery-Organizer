package com.galleryorganizer.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.data.repo.TagCheckState
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.domain.model.TagNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the bulk sheet just did, so the snackbar can offer an undo. */
data class TagUndo(
    val mediaIds: Set<Long>,
    val tagId: Long,
    val tagName: String,
    /** True when the action added the tag; undo then removes it, and vice versa. */
    val wasApplied: Boolean,
)

class TagViewModel(private val container: AppContainer) : ViewModel() {

    private val tags = container.tagRepository

    val tree: StateFlow<List<TagNode>> = tags.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyUsed: StateFlow<List<TagEntity>> = tags.observeRecentlyUsed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _coverage = MutableStateFlow<Map<Long, TagCheckState>>(emptyMap())
    val coverage: StateFlow<Map<Long, TagCheckState>> = _coverage.asStateFlow()

    private val _lastAction = MutableStateFlow<TagUndo?>(null)
    val lastAction: StateFlow<TagUndo?> = _lastAction.asStateFlow()

    private val _expanded = MutableStateFlow<Set<Long>>(emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    /** Recomputed whenever the sheet opens on a different selection. */
    fun refreshCoverage(mediaIds: Set<Long>) {
        viewModelScope.launch { _coverage.value = tags.coverage(mediaIds) }
    }

    fun toggleExpanded(tagId: Long) {
        _expanded.value = _expanded.value.let { if (tagId in it) it - tagId else it + tagId }
    }

    fun expandAll(ids: Collection<Long>) {
        _expanded.value = _expanded.value + ids
    }

    /**
     * The two-tap path: tapping a tag in the sheet applies it to the whole selection
     * immediately, in one transaction, and offers an undo. No confirm step.
     */
    fun toggleTagOnSelection(mediaIds: Set<Long>, tag: TagNode) {
        val current = _coverage.value[tag.id] ?: TagCheckState.None
        // Partial coverage resolves upward: tapping a half-applied tag means "all of them".
        val apply = current != TagCheckState.All
        applyInternal(mediaIds, tag.id, tag.name, apply)
    }

    fun applyTag(mediaIds: Set<Long>, tagId: Long, tagName: String) =
        applyInternal(mediaIds, tagId, tagName, apply = true)

    fun removeTag(mediaIds: Set<Long>, tagId: Long, tagName: String) =
        applyInternal(mediaIds, tagId, tagName, apply = false)

    private fun applyInternal(mediaIds: Set<Long>, tagId: Long, tagName: String, apply: Boolean) {
        if (mediaIds.isEmpty()) return
        viewModelScope.launch {
            tags.setTag(mediaIds, tagId, apply)
            _coverage.value = _coverage.value +
                (tagId to if (apply) TagCheckState.All else TagCheckState.None)
            _lastAction.value = TagUndo(mediaIds, tagId, tagName, apply)

            // Identity only starts to matter once something is tagged, so this is where
            // hashing is worth paying for. It runs after the transaction rather than
            // inside it: opening a few hundred files is I/O, and the user's tap must not
            // wait on it.
            container.mediaRepository.ensureHashed(mediaIds.toList())
        }
    }

    /** Tag names on one item, for the viewer's details panel. */
    suspend fun tagNamesFor(mediaId: Long): List<String> =
        container.database.mediaTagDao().tagsFor(mediaId).map { it.name }

    fun undoLast() {
        val action = _lastAction.value ?: return
        viewModelScope.launch {
            tags.setTag(action.mediaIds, action.tagId, checked = !action.wasApplied)
            _coverage.value = _coverage.value +
                (action.tagId to if (action.wasApplied) TagCheckState.None else TagCheckState.All)
            _lastAction.value = null
        }
    }

    fun consumeUndo() {
        _lastAction.value = null
    }

    /** Create-and-apply from the sheet's text field, still a single gesture. */
    fun createAndApply(
        mediaIds: Set<Long>,
        name: String,
        parentId: Long,
        kind: TagKind? = null,
    ) {
        viewModelScope.launch {
            val id = tags.ensureTag(name, parentId, kind)
            _expanded.value = _expanded.value + parentId
            applyInternal(mediaIds, id, name.trim(), apply = true)
        }
    }

    fun createTag(name: String, parentId: Long, kind: TagKind? = null) {
        viewModelScope.launch { tags.ensureTag(name, parentId, kind) }
    }

    fun setKind(tagId: Long, kind: TagKind) {
        viewModelScope.launch { tags.setKind(tagId, kind) }
    }

    fun rename(tagId: Long, name: String) {
        viewModelScope.launch { tags.rename(tagId, name) }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch { tags.deleteTag(tagId) }
    }

    fun move(tagId: Long, newParentId: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(tags.move(tagId, newParentId)) }
    }

    fun setColor(tagId: Long, color: Int?) {
        viewModelScope.launch { tags.setColor(tagId, color) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TagViewModel(container) as T
    }
}

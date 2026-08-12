package com.galleryorganizer.ui.grid

/**
 * Which items are selected, and the in-flight drag that is extending the selection.
 *
 * Selection is by database id, not by grid index, so it survives paging, scrolling away
 * and back, and the list changing under it while an index-based selection would silently
 * point at different photos.
 */
data class SelectionState(
    val selected: Set<Long> = emptySet(),
    /** Ids selected before the current drag began, so the drag can be re-derived cleanly. */
    private val committed: Set<Long> = emptySet(),
    /** Grid index where the long-press landed, or null when no drag is in flight. */
    val anchorIndex: Int? = null,
    /** True while a drag is extending the selection; the grid disables scroll then. */
    val dragging: Boolean = false,
) {
    val active: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size

    fun isSelected(id: Long): Boolean = id in selected

    fun toggle(id: Long): SelectionState {
        val next = if (id in selected) selected - id else selected + id
        return copy(selected = next, committed = next)
    }

    fun clear(): SelectionState = SelectionState()

    fun replaceWith(ids: Collection<Long>): SelectionState =
        copy(selected = ids.toSet(), committed = ids.toSet())

    fun beginDrag(anchorIndex: Int, anchorId: Long): SelectionState {
        val next = selected + anchorId
        return copy(
            selected = next,
            committed = next,
            anchorIndex = anchorIndex,
            dragging = true,
        )
    }

    /**
     * Recomputes the drag from the anchor every time the pointer moves, rather than
     * accumulating. Dragging back towards the anchor therefore *deselects* the items you
     * are reversing over, which is what makes an overshoot correctable without lifting a
     * finger.
     */
    fun extendDrag(idsInRange: Collection<Long>): SelectionState =
        copy(selected = committed + idsInRange)

    fun endDrag(): SelectionState =
        copy(committed = selected, anchorIndex = null, dragging = false)
}

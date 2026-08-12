package com.galleryorganizer.ui.grid

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Long-press-to-drag range selection over a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * Compose has no built-in for this. The gesture is attached to the grid rather than to
 * each cell, because a per-cell drag handler stops receiving events the moment the finger
 * leaves that cell — which is the entire gesture.
 */
fun Modifier.dragToSelect(
    state: LazyGridState,
    /** Grid index under the pointer, or null for headers and gaps. */
    indexAt: (Offset) -> Int?,
    onLongPress: (index: Int) -> Unit,
    onDragTo: (index: Int) -> Unit,
    onDragEnd: () -> Unit,
    /** Height of the auto-scroll hot zone at the top and bottom edges, in pixels. */
    autoScrollEdge: Float,
): Modifier = pointerInput(state, autoScrollEdge) {
    // Auto-scroll runs in its own loop rather than off drag events: once the finger stops
    // moving inside the hot zone there are no more events, and the list would stall
    // halfway through the selection the user is clearly still making.
    val scrollSpeed = MutableStateFlow(0f)
    coroutineScope {
        launch {
            scrollSpeed.collectLatest { speed ->
                if (speed == 0f) return@collectLatest
                while (isActive) {
                    state.scrollBy(speed)
                    delay(AUTO_SCROLL_FRAME_MS)
                }
            }
        }

        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> indexAt(offset)?.let(onLongPress) },
            onDrag = { change, _ ->
                val position = change.position
                indexAt(position)?.let(onDragTo)
                scrollSpeed.value = autoScrollSpeed(position.y, size.height.toFloat(), autoScrollEdge)
            },
            onDragEnd = {
                scrollSpeed.value = 0f
                onDragEnd()
            },
            onDragCancel = {
                scrollSpeed.value = 0f
                onDragEnd()
            },
        )
    }
}

private suspend fun LazyGridState.scrollBy(pixels: Float) {
    scroll { scrollBy(pixels) }
}

/**
 * How fast to scroll when the pointer is within [edge] pixels of the top or bottom.
 * Ramps with depth into the zone so a small overshoot creeps and a hard push flies.
 */
internal fun autoScrollSpeed(y: Float, height: Float, edge: Float): Float = when {
    edge <= 0f -> 0f
    y < edge -> -((edge - y) / edge) * MAX_AUTO_SCROLL_PX
    y > height - edge -> ((y - (height - edge)) / edge) * MAX_AUTO_SCROLL_PX
    else -> 0f
}

/**
 * The grid index whose cell contains [offset], or null if it is a header or empty space.
 *
 * Only visible items are considered — which is exactly right, since the pointer can only
 * ever be over one of them.
 */
internal fun LazyGridState.indexAtOffset(offset: Offset, isSelectable: (Int) -> Boolean): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        offset.x >= item.offset.x && offset.x <= item.offset.x + item.size.width &&
            offset.y >= item.offset.y && offset.y <= item.offset.y + item.size.height
    }?.takeIf { isSelectable(it.index) }?.index

/**
 * The nearest selectable item when the pointer is between cells — dragging diagonally
 * across a grid spends a lot of time in the gaps, and losing the drag there feels broken.
 */
internal fun LazyGridState.nearestIndexAtOffset(
    offset: Offset,
    isSelectable: (Int) -> Boolean,
): Int? {
    indexAtOffset(offset, isSelectable)?.let { return it }
    return layoutInfo.visibleItemsInfo
        .filter { isSelectable(it.index) }
        .minByOrNull { it.distanceTo(offset) }
        ?.index
}

private fun LazyGridItemInfo.distanceTo(offset: Offset): Float {
    val centerX = offset.x - (this.offset.x + size.width / 2f)
    val centerY = offset.y - (this.offset.y + size.height / 2f)
    return abs(centerX) + abs(centerY)
}

private const val MAX_AUTO_SCROLL_PX = 28f
private const val AUTO_SCROLL_FRAME_MS = 16L

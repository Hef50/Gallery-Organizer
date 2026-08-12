package com.galleryorganizer.ui.grid

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.theme.Motion
import com.galleryorganizer.ui.viewer.ViewerSharedKey

/**
 * The paged, date-sectioned grid.
 *
 * Index-addressed through [LazyPagingItems] throughout, so the composable never sees more
 * than the loaded window. `peek` is used wherever an index is inspected for selection or
 * for opening the viewer — `get` tells Paging to load around that index, and doing that
 * for every cell a drag passes over would trigger loads nobody asked for.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryGrid(
    entries: LazyPagingItems<GridEntry>,
    selection: SelectionState,
    zoomState: GridZoomState,
    gridState: LazyGridState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onToggle: (Long) -> Unit,
    onOpen: (media: MediaEntity, mediaIndex: Int) -> Unit,
    onDragStart: (index: Int, id: Long) -> Unit,
    onDragRange: (Collection<Long>) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val density = zoomState.density
    val autoScrollEdge = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val currentSelection by rememberUpdatedState(selection)

    val isSelectable: (Int) -> Boolean = remember(entries) {
        { index -> entries.peek(index) is GridEntry.Item }
    }

    fun mediaIdAt(index: Int): Long? = (entries.peek(index) as? GridEntry.Item)?.media?.id

    fun idsBetween(from: Int, to: Int): List<Long> {
        val range = if (from <= to) from..to else to..from
        return range.mapNotNull(::mediaIdAt)
    }

    /**
     * Position among photos only. The viewer's pager has no date headers, so opening the
     * grid's index directly would land on the wrong photo — off by one per heading above
     * it, which at the bottom of a year is a lot.
     *
     * The walk is bounded by however much Paging is holding, not by how far down the
     * library the tap was, so its cost does not grow as you scroll.
     */
    fun mediaIndexOf(gridIndex: Int): Int {
        var headers = 0
        for (i in 0 until gridIndex) {
            if (entries.peek(i) is GridEntry.DateHeader) headers++
        }
        return gridIndex - headers
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(density.columns),
        state = gridState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(density.spacing),
        horizontalArrangement = Arrangement.spacedBy(density.spacing),
        // Scrolling is off during a drag-select so the gesture cannot fight the list; the
        // drag modifier runs its own auto-scroll instead.
        userScrollEnabled = !selection.dragging,
        modifier = modifier
            .fillMaxSize()
            // The live pinch scale. Anchored at the top centre rather than the middle so
            // the row under the finger stays put instead of sliding up the screen.
            .graphicsLayer {
                scaleX = zoomState.scale.value
                scaleY = zoomState.scale.value
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .pinchToZoom(zoomState, gridState, enabled = !selection.dragging)
            .dragToSelect(
                state = gridState,
                indexAt = { offset: Offset -> gridState.nearestIndexAtOffset(offset, isSelectable) },
                onLongPress = { index -> mediaIdAt(index)?.let { onDragStart(index, it) } },
                onDragTo = { index ->
                    currentSelection.anchorIndex?.let { anchor ->
                        onDragRange(idsBetween(anchor, index))
                    }
                },
                onDragEnd = onDragEnd,
                autoScrollEdge = autoScrollEdge,
            ),
    ) {
        items(
            count = entries.itemCount,
            key = { index ->
                when (val entry = entries.peek(index)) {
                    is GridEntry.Item -> entry.key
                    is GridEntry.DateHeader -> entry.key
                    null -> "placeholder-$index"
                }
            },
            span = { index ->
                if (entries.peek(index) is GridEntry.DateHeader) {
                    GridItemSpan(maxLineSpan)
                } else {
                    GridItemSpan(1)
                }
            },
        ) { index ->
            when (val entry = entries[index]) {
                is GridEntry.DateHeader -> DateHeaderRow(entry, compact = density.columns >= 6)

                is GridEntry.Item -> {
                    val media = entry.media
                    with(sharedScope) {
                        MediaCell(
                            media = media,
                            selected = selection.isSelected(media.id),
                            selectionActive = selection.active,
                            corner = density.corner,
                            modifier = Modifier
                                // Keyed by media id so the cell and the viewer page match
                                // no matter how the list has re-flowed underneath.
                                .sharedElement(
                                    rememberSharedContentState(key = ViewerSharedKey(media.id)),
                                    animatedVisibilityScope = animatedScope,
                                    boundsTransform = { _, _ -> Motion.heroBounds },
                                )
                                .combinedClickable(
                                    onClick = {
                                        // Once a selection exists, a plain tap extends it —
                                        // otherwise a mis-tap opens a photo and loses the
                                        // whole selection.
                                        if (selection.active) {
                                            onToggle(media.id)
                                        } else {
                                            onOpen(media, mediaIndexOf(index))
                                        }
                                    },
                                    onLongClick = { onToggle(media.id) },
                                ),
                        )
                    }
                }

                null -> Box(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A date heading.
 *
 * Set in the app's title style rather than as a small label: at four columns these are the
 * only typography on screen, and treating them as section titles is what turns an
 * undifferentiated wall of thumbnails into something with rhythm.
 */
@Composable
private fun DateHeaderRow(header: GridEntry.DateHeader, compact: Boolean) {
    Text(
        text = header.label,
        style = if (compact) {
            MaterialTheme.typography.labelLarge
        } else {
            MaterialTheme.typography.titleMedium
        },
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (compact) 14.dp else 26.dp,
                bottom = if (compact) 5.dp else 10.dp,
            ),
    )
}

private val AUTO_SCROLL_EDGE = 96.dp

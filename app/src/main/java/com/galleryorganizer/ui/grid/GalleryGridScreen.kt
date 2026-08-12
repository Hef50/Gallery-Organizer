package com.galleryorganizer.ui.grid

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity

/**
 * The paged, date-sectioned grid.
 *
 * Everything here is index-addressed through [LazyPagingItems], so the composable never
 * sees more than the loaded window. `peek` is used rather than `get` wherever an index is
 * inspected for selection purposes — `get` signals Paging to load around that index, and
 * doing that for every item a drag passes over would trigger loads the user never asked
 * for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGrid(
    entries: LazyPagingItems<GridEntry>,
    selection: SelectionState,
    onToggle: (Long) -> Unit,
    onOpen: (MediaEntity) -> Unit,
    onDragStart: (index: Int, id: Long) -> Unit,
    onDragRange: (Collection<Long>) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val gridState = rememberLazyGridState()
    val autoScrollEdge = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val currentSelection by rememberUpdatedState(selection)

    val isSelectable: (Int) -> Boolean = remember(entries) {
        { index -> entries.peek(index) is GridEntry.Item }
    }

    fun mediaIdAt(index: Int): Long? = (entries.peek(index) as? GridEntry.Item)?.media?.id

    /** Ids between two grid indices, skipping headers. */
    fun idsBetween(from: Int, to: Int): List<Long> {
        val range = if (from <= to) from..to else to..from
        return range.mapNotNull(::mediaIdAt)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        // Scrolling is disabled during a drag so the gesture cannot fight the list; the
        // drag modifier does its own auto-scroll instead.
        userScrollEnabled = !selection.dragging,
        modifier = modifier
            .fillMaxSize()
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
                is GridEntry.DateHeader -> DateHeaderRow(entry)

                is GridEntry.Item -> MediaCell(
                    media = entry.media,
                    selected = selection.isSelected(entry.media.id),
                    selectionActive = selection.active,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            // Once a selection exists, plain taps extend it rather than
                            // opening — otherwise every mis-tap loses the whole selection.
                            if (selection.active) onToggle(entry.media.id) else onOpen(entry.media)
                        },
                        onLongClick = { onToggle(entry.media.id) },
                    ),
                )

                null -> Box(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DateHeaderRow(header: GridEntry.DateHeader) {
    Text(
        text = header.label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

private val AUTO_SCROLL_EDGE = 96.dp

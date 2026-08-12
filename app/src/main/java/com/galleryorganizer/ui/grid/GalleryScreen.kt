package com.galleryorganizer.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.theme.Motion
import com.galleryorganizer.work.IndexingStatus

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    indexing: IndexingStatus,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    banner: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    onOpen: (MediaEntity, Int) -> Unit = { _, _ -> },
    selectionActions: @Composable (Set<Long>) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val entries = viewModel.entries.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val total by viewModel.itemCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filtered = !query.isEmpty

    val gridState = rememberLazyGridState()
    val zoomState = rememberGridZoomState()

    // No snackbar host here: the shell owns the one snackbar, so an undo raised from an
    // album or the map lands in the same place as one raised from the grid.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))
                banner()
                header()
                if (indexing.queued) IndexingStrip(indexing.scanned)

                Box(Modifier.weight(1f)) {
                    when {
                        entries.loadState.refresh is LoadState.Loading && entries.itemCount == 0 ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center))

                        entries.itemCount == 0 ->
                            EmptyGrid(indexing = indexing.queued, filtered = filtered)

                        else -> GalleryGrid(
                            entries = entries,
                            selection = selection,
                            zoomState = zoomState,
                            gridState = gridState,
                            sharedScope = sharedScope,
                            animatedScope = animatedScope,
                            onToggle = viewModel::toggle,
                            onOpen = onOpen,
                            onDragStart = viewModel::beginDrag,
                            onDragRange = viewModel::extendDrag,
                            onDragEnd = viewModel::endDrag,
                            contentPadding = PaddingValues(
                                start = 2.dp,
                                end = 2.dp,
                                bottom = padding.calculateBottomPadding() +
                                    contentPadding.calculateBottomPadding() + 24.dp,
                            ),
                        )
                    }
                }
            }

            // The selection bar floats over the grid rather than replacing the header, so
            // the photos never jump when a selection starts.
            SelectionBar(
                visible = selection.active,
                count = selection.count,
                onClear = viewModel::clearSelection,
                actions = { selectionActions(selection.selected) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    visible: Boolean,
    count: Int,
    onClear: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { -it },
        exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { -it },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
            }
            Text(
                "%,d selected".format(count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

@Composable
private fun IndexingStrip(scanned: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (scanned > 0) "Finding photos · %,d scanned".format(scanned) else "Finding photos",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyGrid(indexing: Boolean, filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            when {
                indexing -> "Finding your photos"
                filtered -> "Nothing matches"
                else -> "No photos yet"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                indexing ->
                    "This takes a few minutes the first time. You can leave the app — it carries on."
                filtered -> "Try removing a filter, or search for something else."
                else -> "Anything in your gallery shows up here once it has been indexed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

package com.galleryorganizer.ui.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.entity.MediaEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    indexing: com.galleryorganizer.work.IndexingStatus,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    banner: @Composable () -> Unit = {},
    searchBar: @Composable () -> Unit = {},
    onOpen: (MediaEntity) -> Unit = {},
    selectionActions: @Composable (Set<Long>) -> Unit = {},
    topBarActions: @Composable () -> Unit = {},
) {
    val entries = viewModel.entries.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val total by viewModel.itemCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filtered = !query.isEmpty

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selection.active) {
                TopAppBar(
                    title = { Text("${selection.count} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = { selectionActions(selection.selected) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (filtered) "Results" else "Gallery")
                            if (total > 0) {
                                Text(
                                    "%,d items".format(total),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = { topBarActions() },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            banner()
            searchBar()
            if (indexing.queued) {
                IndexingStrip(indexing.scanned)
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    entries.loadState.refresh is LoadState.Loading && entries.itemCount == 0 ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    entries.itemCount == 0 -> EmptyGrid(
                        indexing = indexing.queued,
                        filtered = filtered,
                    )

                    else -> GalleryGrid(
                        entries = entries,
                        selection = selection,
                        onToggle = viewModel::toggle,
                        onOpen = onOpen,
                        onDragStart = viewModel::beginDrag,
                        onDragRange = viewModel::extendDrag,
                        onDragEnd = viewModel::endDrag,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexingStrip(scanned: Int) {
    Column {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        Text(
            if (scanned > 0) "Indexing… %,d items scanned".format(scanned) else "Indexing…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyGrid(indexing: Boolean, filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                indexing -> "Finding your photos…"
                filtered -> "Nothing matches those filters"
                else -> "No photos or videos yet"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                indexing ->
                    "This takes a few minutes the first time. You can leave the app; it carries on."
                filtered -> "Try removing a filter, or search for something else."
                else -> "Anything in your gallery will show up here once it has been indexed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

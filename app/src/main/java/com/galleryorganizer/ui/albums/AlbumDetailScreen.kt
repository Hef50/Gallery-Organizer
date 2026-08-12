package com.galleryorganizer.ui.albums

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.grid.MediaCell
import com.galleryorganizer.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * One album's contents.
 *
 * Deliberately *not* date-sectioned like the main grid. An album's order is the user's
 * order — that is what distinguishes it from a saved search — so imposing date headers on
 * it would both fight that order and imply the app knows better.
 */
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumViewModel,
    onBack: () -> Unit,
    onOpenItem: (MediaEntity, Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val album by viewModel.openAlbum.collectAsStateWithLifecycle()
    val items = viewModel.albumItems.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val current = album

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 3.dp,
                end = 3.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AlbumHeader(
                    name = current?.name ?: "Album",
                    count = current?.itemCount ?: 0,
                    description = current?.description.orEmpty(),
                    onBack = onBack,
                    onRename = { renaming = true },
                    onDelete = { confirmingDelete = true },
                    onSelectAll = {
                        val id = current?.id ?: return@AlbumHeader
                        scope.launch { selection = viewModel.idsIn(id).toSet() }
                    },
                )
            }

            items(items.itemCount, key = { index -> items.peek(index)?.id ?: -index.toLong() }) { index ->
                val media = items[index] ?: return@items
                MediaCell(
                    media = media,
                    selected = media.id in selection,
                    selectionActive = selection.isNotEmpty(),
                    corner = 10.dp,
                    modifier = Modifier.clickable {
                        if (selection.isEmpty()) {
                            onOpenItem(media, index)
                        } else {
                            selection = if (media.id in selection) {
                                selection - media.id
                            } else {
                                selection + media.id
                            }
                        }
                    },
                )
            }

            if (items.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Nothing in here yet. Select photos in the library and use " +
                            "“Add to album”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = fadeIn(Motion.effects()) + scaleIn(Motion.expressiveSpatial(), initialScale = 0.9f),
            exit = fadeOut(Motion.fastEffects()) + scaleOut(Motion.spatial(), targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = contentPadding.calculateBottomPadding() + 18.dp),
        ) {
            AlbumSelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                onRemove = {
                    viewModel.removeFromOpenAlbum(selection)
                    selection = emptySet()
                },
            )
        }
    }

    if (renaming && current != null) {
        NameAlbumDialog(
            title = "Rename album",
            initial = current.name,
            confirmLabel = "Rename",
            onConfirm = { name -> viewModel.rename(current.id, name); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete “${current.name}”?") },
            text = {
                Text(
                    "Only the album goes away. Every photo in it stays on the phone, with " +
                        "its tags, exactly where it was.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(current.id)
                        confirmingDelete = false
                        onBack()
                    },
                ) { Text("Delete album") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun AlbumHeader(
    name: String,
    count: Int,
    description: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.weight(1f))
            if (count > 0) HeaderIcon(Icons.Rounded.SelectAll, "Select everything", onSelectAll)
            HeaderIcon(Icons.Rounded.DriveFileRenameOutline, "Rename album", onRename)
            HeaderIcon(Icons.Rounded.Delete, "Delete album", onDelete)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 17.dp),
        )
        Text(
            if (count == 0) "Empty" else "%,d %s".format(count, if (count == 1) "photo" else "photos"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 17.dp),
        )
        if (description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 17.dp),
            )
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun AlbumSelectionBar(count: Int, onClear: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderIcon(Icons.Rounded.Close, "Clear selection", onClear)
        Text(
            "%,d selected".format(count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TextButton(onClick = onRemove) { Text("Remove from album") }
    }
}

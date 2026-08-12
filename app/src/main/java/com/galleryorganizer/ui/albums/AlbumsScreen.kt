package com.galleryorganizer.ui.albums

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galleryorganizer.data.db.dao.AlbumSummary
import com.galleryorganizer.ui.theme.Motion

/**
 * The album shelf.
 *
 * Two columns of large covers rather than a list of rows: an album's identity is its
 * photograph, and a 40 dp thumbnail next to a line of text is a filing cabinet. The title
 * sits *on* the cover under a gradient rather than beneath it, so the card is one object
 * instead of a picture with a caption stapled to it.
 */
@Composable
fun AlbumsScreen(
    viewModel: AlbumViewModel,
    onOpen: (AlbumSummary) -> Unit,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            header()
        }

        item(key = "new-album") {
            NewAlbumCard(onClick = { creating = true })
        }

        items(shelf, key = { it.id }) { album ->
            AlbumCard(album = album, onClick = { onOpen(album) })
        }

        if (shelf.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "An album is a sequence you put together on purpose — the twelve shots " +
                        "from the trip worth showing someone, in the order you want them " +
                        "seen. Tags describe photos; albums arrange them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                )
            }
        }
    }

    if (creating) {
        NameAlbumDialog(
            title = "New album",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name -> viewModel.create(name); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun AlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    // The card yields under the finger and springs back. It is a small thing, and it is the
    // difference between a surface that responds and a picture that happens to be tappable.
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Motion.spatial(), label = "albumScale")

    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
    ) {
        if (album.coverUri != null) {
            AsyncImage(
                model = remember(album.coverUri) {
                    ImageRequest.Builder(context).data(album.coverUri).crossfade(true).build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().drawWithCache {
                    // The scrim is part of the cover, not a bar underneath it. Two stops
                    // rather than a linear ramp so the top two thirds of the photograph
                    // stay completely untouched.
                    val brush = Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                    onDrawWithContent { drawContent(); drawRect(brush) }
                },
            )
        } else {
            Icon(
                Icons.Rounded.PhotoAlbum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.align(Alignment.Center).size(38.dp),
            )
        }

        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (album.coverUri != null) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (album.itemCount == 0) "Empty" else "%,d".format(album.itemCount),
                style = MaterialTheme.typography.labelSmall,
                color = if (album.coverUri != null) {
                    Color.White.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun NewAlbumCard(onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "New album",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NameAlbumDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    supporting: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A single row of an album's first few photos, used by the "add to album" sheet. */
@Composable
fun AlbumStrip(uris: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        uris.take(4).forEach { uri ->
            AsyncImage(
                model = remember(uri) { ImageRequest.Builder(context).data(uri).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

package com.galleryorganizer.ui.albums

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galleryorganizer.data.repo.AlbumCheckState
import com.galleryorganizer.ui.theme.Motion

/**
 * "Add to album" for the current selection.
 *
 * Same contract as the tag sheet: one tap on a row commits, tri-state against the
 * selection, undo from the snackbar. Each row shows the album's cover so the choice is made
 * on the picture rather than on a remembered name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    selection: Set<Long>,
    viewModel: AlbumViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(selection) { viewModel.refreshCoverage(selection) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Add %,d %s to an album".format(
                    selection.size,
                    if (selection.size == 1) "photo" else "photos",
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.height(14.dp))

            NewAlbumField(
                text = newName,
                onTextChange = { newName = it },
                onCreate = {
                    viewModel.createAndAdd(newName, selection)
                    newName = ""
                },
            )

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(shelf, key = { it.id }) { album ->
                    AlbumRow(
                        name = album.name,
                        count = album.itemCount,
                        coverUri = album.coverUri,
                        state = coverage[album.id] ?: AlbumCheckState.None,
                        onClick = { viewModel.toggleMembership(album.id, album.name, selection) },
                    )
                }
                if (shelf.isEmpty()) {
                    item {
                        Text(
                            "No albums yet. Type a name above to make one from this selection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewAlbumField(text: String, onTextChange: (String) -> Unit, onCreate: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        "New album from this selection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = text.isNotBlank(),
            enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
            exit = fadeOut(Motion.fastEffects()) + shrinkVertically(Motion.spatial()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                com.galleryorganizer.ui.components.TagChip(
                    label = "Create “${text.trim()}” and add",
                    selected = true,
                    onClick = onCreate,
                )
            }
        }
    }
}

@Composable
private fun AlbumRow(
    name: String,
    count: Int,
    coverUri: String?,
    state: AlbumCheckState,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = remember(coverUri) { ImageRequest.Builder(context).data(coverUri).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.PhotoAlbum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (count == 0) "Empty" else "%,d".format(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MembershipMark(state)
    }
}

@Composable
private fun MembershipMark(state: AlbumCheckState) {
    val accent = MaterialTheme.colorScheme.primary
    val target = when (state) {
        AlbumCheckState.All -> 1f
        AlbumCheckState.Some -> 0.55f
        AlbumCheckState.None -> 0f
    }
    val fill by animateFloatAsState(target, Motion.spatial(), label = "albumMark")

    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (state == AlbumCheckState.None) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        accent.copy(alpha = 0.25f + 0.75f * fill)
                    },
                ),
        )
        if (state != AlbumCheckState.None) {
            Icon(
                if (state == AlbumCheckState.All) Icons.Rounded.Check else Icons.Rounded.Remove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { scaleX = 0.6f + 0.4f * fill; scaleY = 0.6f + 0.4f * fill },
            )
        }
    }
}

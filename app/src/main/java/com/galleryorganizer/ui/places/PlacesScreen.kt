package com.galleryorganizer.ui.places

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.domain.places.PlaceZoom
import com.galleryorganizer.ui.albums.NameAlbumDialog
import com.galleryorganizer.ui.components.TagChip
import com.galleryorganizer.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * Where the photos were taken.
 *
 * The screen's real job is not "look at a map" — it is **turning a coordinate into a name**.
 * Without a network there is no gazetteer to ask what 35.01, 135.77 is called, so the map
 * finds the photos from one place and the person who was there supplies the name once.
 * Naming a cluster creates a Place tag and applies it to every photo in it, which is why
 * the button is the loudest thing on the screen.
 */
@Composable
fun PlacesScreen(
    viewModel: PlacesViewModel,
    header: @Composable () -> Unit,
    /** The tapped photo, plus every id in its cluster so the viewer can swipe through them. */
    onOpenItem: (MediaEntity, List<Long>) -> Unit,
    onTagCluster: (Set<Long>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val items by viewModel.selectedItems.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            header()

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaceZoom.entries.forEach { zoom ->
                    TagChip(
                        label = zoom.label,
                        selected = zoom == state.zoom,
                        onClick = { viewModel.setZoom(zoom) },
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    state.loading -> Hint("Reading where your photos were taken…")

                    state.points.isEmpty() -> Hint(
                        if (state.pending > 0) {
                            "Still checking %,d photos for a location. Come back in a bit."
                                .format(state.pending)
                        } else {
                            "None of your photos carry a location. Most screenshots and " +
                                "downloads never do, and neither does anything shot with " +
                                "location switched off in the camera."
                        },
                    )

                    else -> PlaceMap(
                        frame = state.frame,
                        points = state.points,
                        clusters = state.clusters,
                        selectedId = selected?.id,
                        onSelect = viewModel::select,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { it / 2 },
            exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.spatial()) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            val cluster = selected
            ClusterCard(
                count = cluster?.count ?: 0,
                latitude = cluster?.latitude ?: 0.0,
                longitude = cluster?.longitude ?: 0.0,
                items = items,
                onName = { naming = true },
                onTag = {
                    scope.launch { onTagCluster(viewModel.idsInSelected()) }
                },
                onOpenItem = onOpenItem,
            )
        }
    }

    if (naming) {
        NameAlbumDialog(
            title = "Name this place",
            initial = "",
            confirmLabel = "Name it",
            supporting = "This makes a Place tag and puts it on every photo taken here, so " +
                "you can search for it by name from then on.",
            onConfirm = { name -> viewModel.nameSelected(name); naming = false },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * The card for a tapped cluster: a strip of what is there, the coordinate, and the two
 * things worth doing with a place — name it, or tag it by hand.
 */
@Composable
private fun ClusterCard(
    count: Int,
    latitude: Double,
    longitude: Double,
    items: List<MediaEntity>,
    onName: () -> Unit,
    onTag: () -> Unit,
    onOpenItem: (MediaEntity, List<Long>) -> Unit,
) {
    val clusterIds = remember(items) { items.map { it.id } }
    val context = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "%,d %s here".format(count, if (count == 1) "photo" else "photos"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "%.4f, %.4f".format(latitude, longitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (items.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { media ->
                    AsyncImage(
                        model = remember(media.uri) {
                            ImageRequest.Builder(context).data(media.uri).build()
                        },
                        contentDescription = media.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onOpenItem(media, clusterIds) },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip(label = "Name this place", selected = true, leadingIcon = Icons.Rounded.Place, onClick = onName)
            TagChip(label = "Tag these", leadingIcon = Icons.Rounded.Sell, onClick = onTag)
        }
    }
}

package com.galleryorganizer.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.theme.Motion
import java.util.concurrent.TimeUnit

/**
 * One photo in the grid.
 *
 * The selection treatment is the interesting part. The obvious approach — a border or a
 * coloured overlay — either hides the photo or makes the grid look like a form. This
 * insets the photo instead, so the selected item visibly *steps back* into its cell and
 * the accent shows only in the ring behind it. Nothing covers the subject, and a screen
 * full of selected items still reads as photographs rather than as checkboxes.
 */
@Composable
fun MediaCell(
    media: MediaEntity,
    selected: Boolean,
    selectionActive: Boolean,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 8.dp,
    dimmed: Boolean = false,
) {
    // The overwhelmingly common case — no selection anywhere on screen — draws the plainest
    // thing that works, because at ten columns there are a couple of hundred of these on
    // screen at once and everything below is paid for by every one of them.
    if (!selectionActive && !dimmed) {
        PlainMediaCell(media, modifier, corner)
        return
    }

    val context = LocalContext.current
    val scale by animateFloatAsState(if (selected) 0.82f else 1f, Motion.spatial(), label = "cellScale")
    val radius by animateDpAsState(if (selected) corner + 6.dp else corner, Motion.spatial(), label = "cellRadius")
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        Motion.effects(),
        label = "cellRing",
    )
    val contentAlpha by animateFloatAsState(if (dimmed) 0.35f else 1f, Motion.effects(), label = "cellAlpha")

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            .background(ring)
            .semantics {
                this.selected = selected
                contentDescription = media.displayName
            },
    ) {
        AsyncImage(
            model = remember(media.uri) {
                ImageRequest.Builder(context)
                    .data(media.uri)
                    // No crossfade: at a fling of sixty rows a second a fade is a grey
                    // shimmer, and it costs an extra layer on every cell.
                    .crossfade(false)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = contentAlpha
                    shape = RoundedCornerShape(radius)
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )

        if (media.isVideo) {
            VideoAffordance(
                duration = media.duration,
                compact = corner < 6.dp,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }

        // The tick only exists once a selection is under way. Showing empty checkboxes on
        // every cell all the time turns a gallery into a spreadsheet.
        AnimatedVisibility(
            visible = selectionActive,
            enter = fadeIn(Motion.fastEffects()) + scaleIn(Motion.spatial(), initialScale = 0.6f),
            exit = fadeOut(Motion.fastEffects()) + scaleOut(Motion.spatial(), targetScale = 0.6f),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            SelectionTick(selected)
        }
    }
}

/**
 * A photo with nothing else going on: no selection anywhere, nothing dimmed.
 *
 * This exists purely for the zoomed-out grid. The full cell above allocates four running
 * animations and, worse, a `graphicsLayer` that clips to a freshly built `RoundedCornerShape`
 * — which is a separate clipped render node per tile. That is affordable for the forty tiles
 * of a four-column grid and not at all affordable for the couple of hundred of a ten-column
 * one, where it was the difference between a smooth fling and a slideshow.
 *
 * Here the clip happens once on the container, there are no animations, and the tile is
 * essentially an image in a rounded box. Switching between this and the full cell costs one
 * frame when a selection starts, which is a gesture, not a fling.
 */
@Composable
private fun PlainMediaCell(
    media: MediaEntity,
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            // Near-black rather than a mid grey, so an unloaded tile reads as absence
            // instead of as a placeholder — the same thing Samsung Gallery does, and what
            // makes a half-loaded zoomed-out grid look intentional.
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .semantics { contentDescription = media.displayName },
    ) {
        AsyncImage(
            model = remember(media.uri) {
                ImageRequest.Builder(context).data(media.uri).crossfade(false).build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (media.isVideo) {
            VideoAffordance(
                duration = media.duration,
                compact = corner < 6.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SelectionTick(selected: Boolean) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.28f),
        Motion.fastEffects(),
        label = "tickBg",
    )
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(Motion.fastEffects()) + scaleIn(Motion.expressiveSpatial(), initialScale = 0.3f),
            exit = fadeOut(Motion.fastEffects()),
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * A gradient at the foot of the cell rather than a badge on top of it. A pill floating in
 * the middle of a thumbnail is the single most dated thing a gallery can do; a scrim that
 * darkens only the bottom eighth reads as part of the photograph.
 */
@Composable
private fun VideoAffordance(duration: Long, compact: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val brush = Brush.verticalGradient(
                0.62f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.45f),
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush)
            }
        },
    ) {
        Row(
            Modifier.align(Alignment.BottomEnd).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            )
            if (!compact) {
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

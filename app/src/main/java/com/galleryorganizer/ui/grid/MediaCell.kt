package com.galleryorganizer.ui.grid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galleryorganizer.data.db.entity.MediaEntity
import java.util.concurrent.TimeUnit

@Composable
fun MediaCell(
    media: MediaEntity,
    selected: Boolean,
    selectionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Shrinking the whole cell rather than drawing a border keeps the checkmark from
    // covering the part of the photo you are trying to identify.
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "cellScale")

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics {
                this.selected = selected
                contentDescription = media.displayName
            },
    ) {
        AsyncImage(
            model = remember(media.uri) {
                ImageRequest.Builder(context)
                    .data(media.uri)
                    // crossfade off: at a fling of 60 rows a second the fade is just a
                    // grey shimmer, and it costs an extra layer per cell.
                    .crossfade(false)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        if (media.isVideo) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatDuration(media.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }

        if (selectionActive) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp),
            )
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

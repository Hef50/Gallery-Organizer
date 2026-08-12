package com.galleryorganizer.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.ui.components.TagChip
import java.util.Locale

/**
 * The details panel.
 *
 * Deliberately a short, scannable set of facts rather than an EXIF dump. Someone opening
 * this wants to know when and where a photo was taken and what it is tagged — resolution
 * and file size are secondary, and shutter speed is not what this app is for.
 */
@Composable
fun MediaInfoPanel(media: MediaEntity, tags: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                media.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                media.relativePath.trimEnd('/').ifBlank { "Unknown folder" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Fact("Size", formatBytes(media.size))
                if (media.width > 0 && media.height > 0) {
                    Fact("Dimensions", "${media.width} × ${media.height}", megapixels(media))
                }
                if (media.isVideo && media.duration > 0) {
                    Fact("Length", com.galleryorganizer.ui.grid.formatDuration(media.duration))
                }
            }

            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Tags",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { TagChip(label = it) }
                }
            }

            if (media.contentHash == null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Not yet fingerprinted — tag it and its tags become permanent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, secondary: String? = null) {
    Column {
        Text(
            label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
        if (secondary != null) {
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun megapixels(media: MediaEntity): String =
    "%.1f MP".format(media.width.toLong() * media.height.toLong() / 1_000_000.0)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

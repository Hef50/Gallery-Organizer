package com.galleryorganizer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The app's four places. Deliberately four: a fifth would push each target under a thumb. */
enum class GallerySection(val label: String, val icon: ImageVector, val route: String) {
    Library("Library", Icons.Rounded.Photo, "gallery"),
    Albums("Albums", Icons.Rounded.PhotoAlbum, "albums"),
    Places("Places", Icons.Rounded.Place, "places"),
    Tags("Tags", Icons.Rounded.Sell, "tags"),
}

/**
 * A floating pill rather than Material's full-width navigation bar.
 *
 * A `NavigationBar` paints an opaque 80 dp band across the bottom of the screen, which on a
 * gallery means permanently hiding a row of photographs. This floats clear of the edge, is
 * only as wide as its contents, and lets the grid scroll visibly underneath — so the app
 * reads as photographs first and chrome second. The selected item expands to show its
 * label; the others stay as icons, which keeps the pill narrow enough to sit inside the
 * thumb arc.
 */
@Composable
fun GalleryNavBar(
    selected: GallerySection,
    onSelect: (GallerySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GallerySection.entries.forEach { section ->
            NavItem(
                section = section,
                selected = section == selected,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun NavItem(section: GallerySection, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        com.galleryorganizer.ui.theme.Motion.effects(),
        label = "navContainer",
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        com.galleryorganizer.ui.theme.Motion.effects(),
        label = "navTint",
    )
    val lift by animateFloatAsState(
        if (selected) 1.06f else 1f,
        com.galleryorganizer.ui.theme.Motion.expressiveSpatial(),
        label = "navLift",
    )
    val interactions = remember { MutableInteractionSource() }

    Row(
        Modifier
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon,
            contentDescription = section.label,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(com.galleryorganizer.ui.theme.Motion.effects()) +
                expandHorizontally(com.galleryorganizer.ui.theme.Motion.spatial()),
            exit = fadeOut(com.galleryorganizer.ui.theme.Motion.fastEffects()) +
                shrinkHorizontally(com.galleryorganizer.ui.theme.Motion.spatial()),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(
                    section.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
    }
}

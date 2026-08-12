package com.galleryorganizer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galleryorganizer.ui.theme.Motion

/**
 * The app's one chip.
 *
 * Material's own chips are built for forms: a fixed 32 dp height, an outline, and a
 * selected state that fills with the container colour. Against photographs that reads as
 * heavy and generic. This one is quieter when unselected, fills with the *tag's own*
 * colour when selected, and springs very slightly when the state changes so a tap feels
 * acknowledged rather than merely registered.
 */
@Composable
fun TagChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    partial: Boolean = false,
    accent: Color? = null,
    leadingIcon: ImageVector? = null,
    count: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    val container by animateColorAsState(
        when {
            selected -> tint.copy(alpha = 0.22f)
            partial -> tint.copy(alpha = 0.10f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        Motion.effects(),
        label = "chipContainer",
    )
    val outline by animateColorAsState(
        when {
            selected -> tint.copy(alpha = 0.85f)
            partial -> tint.copy(alpha = 0.45f)
            else -> Color.Transparent
        },
        Motion.effects(),
        label = "chipOutline",
    )
    val scale by animateFloatAsState(if (selected) 1.03f else 1f, Motion.expressiveSpatial(), label = "chipScale")

    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(1.dp, outline, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        } else if (accent != null) {
            // A colour dot rather than an icon: it identifies the tag without pretending to
            // describe it.
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(1.dp))
            Text(
                "%,d".format(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

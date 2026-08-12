package com.galleryorganizer.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galleryorganizer.data.repo.SavedSearch
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.ui.components.TagChip
import com.galleryorganizer.ui.grid.QuickFilter
import com.galleryorganizer.ui.theme.Motion

/**
 * Everything above the grid: title, search, quick filters and saved albums.
 *
 * There is no app bar. A Material `TopAppBar` would eat 64 dp of vertical space to show a
 * word the user already knows, and on a photo screen every row of chrome is a row of
 * photographs you cannot see. Instead the title doubles as the item count, the search
 * field is the only permanent control, and the filter rows collapse away the moment a
 * search is active.
 */
@Composable
fun GalleryHeader(
    query: SearchQuery,
    total: Int,
    savedSearches: List<SavedSearch>,
    activeSavedId: Long?,
    onTextChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onQuickFilter: (QuickFilter) -> Unit,
    onOpenSaved: (SavedSearch) -> Unit,
    onSaveSearch: (String) -> Unit,
    onDeleteSaved: (SavedSearch) -> Unit,
    onTogglePin: (SavedSearch) -> Unit,
    onSelectAll: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<SavedSearch?>(null) }
    val filtering = !query.isEmpty

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (filtering) "Results" else "Library",
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (total > 0) {
                    Text(
                        "%,d %s".format(total, if (total == 1) "photo" else "photos"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (filtering) {
                HeaderAction(Icons.Rounded.SelectAll, "Select all results", onSelectAll)
            }
            HeaderAction(Icons.Rounded.Sell, "Tags", onOpenTags)
            HeaderAction(Icons.Rounded.Settings, "Settings", onOpenSettings)
        }

        SearchField(
            text = query.text,
            activeFilters = query.activeFilterCount - if (query.text.isBlank()) 0 else 1,
            onTextChange = onTextChange,
            onOpenFilters = onOpenFilters,
        )

        // Quick filters disappear while a search is running: they would compete with the
        // results for the user's attention and none of them apply to what was typed.
        AnimatedVisibility(
            visible = query.text.isBlank(),
            enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
            exit = fadeOut(Motion.fastEffects()) + shrinkVertically(Motion.spatial()),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickFilter.entries.forEach { filter ->
                    TagChip(
                        label = filter.label,
                        selected = filter.matches(query),
                        onClick = { onQuickFilter(filter) },
                    )
                }

                if (savedSearches.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(width = 1.dp, height = 26.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.width(4.dp))
                }

                savedSearches.forEach { saved ->
                    TagChip(
                        label = saved.name,
                        selected = saved.id == activeSavedId,
                        leadingIcon = if (saved.pinned) Icons.Rounded.PushPin else null,
                        onClick = {
                            if (saved.id == activeSavedId) confirmingDelete = saved
                            else onOpenSaved(saved)
                        },
                    )
                }

                if (filtering && activeSavedId == null) {
                    TagChip(label = "Save this search", onClick = { naming = true })
                }
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as a smart album") },
            text = {
                Column {
                    Text(
                        "It stays live — new photos that match show up in it automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onSaveSearch(name); naming = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    confirmingDelete?.let { saved ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(saved.name) },
            text = { Text("Only the saved album goes away. Your photos and tags are untouched.") },
            confirmButton = {
                TextButton(onClick = { onDeleteSaved(saved); confirmingDelete = null }) {
                    Text("Delete album")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onTogglePin(saved); confirmingDelete = null }) {
                        Text(if (saved.pinned) "Unpin" else "Pin")
                    }
                    TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * A single soft-filled field rather than Material's outlined text field.
 *
 * An outline plus a floating label is form furniture; against a wall of photographs a
 * filled pill reads as a control without drawing a box around itself. The filter count
 * lives inside the field so there is never a badge floating over the grid.
 */
@Composable
private fun SearchField(
    text: String,
    activeFilters: Int,
    onTextChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    "Search names, tags, text in photos",
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        if (text.isNotEmpty()) {
            HeaderAction(Icons.Rounded.Close, "Clear search", { onTextChange("") }, size = 36.dp)
        }
        FilterButton(activeFilters, onOpenFilters)
    }
}

@Composable
private fun FilterButton(activeFilters: Int, onClick: () -> Unit) {
    val active = activeFilters > 0
    val scale by animateFloatAsState(if (active) 1f else 0.94f, Motion.expressiveSpatial(), label = "filterScale")
    Row(
        Modifier
            .padding(2.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (active) 12.dp else 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Rounded.Tune,
            contentDescription = "Filters",
            tint = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        if (active) {
            Text(
                activeFilters.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
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

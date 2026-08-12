package com.galleryorganizer.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galleryorganizer.data.repo.SavedSearch
import com.galleryorganizer.domain.search.SearchQuery

/** Search field plus the filter button, sitting above the grid. */
@Composable
fun GallerySearchBar(
    query: SearchQuery,
    onTextChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query.text,
            onValueChange = onTextChange,
            singleLine = true,
            placeholder = { Text("Search names, tags and text in photos") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.text.isNotEmpty()) {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        BadgedBox(
            badge = {
                // The count is the honest answer to "why am I not seeing everything?" —
                // a filter left on is the most confusing state this screen can be in.
                val active = query.activeFilterCount - if (query.text.isBlank()) 0 else 1
                if (active > 0) Badge { Text(active.toString()) }
            },
        ) {
            IconButton(onClick = onOpenFilters) {
                Icon(Icons.Filled.FilterList, contentDescription = "Filters")
            }
        }
    }
}

/** Saved smart albums, shown as a chip row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedSearchRow(
    saved: List<SavedSearch>,
    activeId: Long?,
    canSave: Boolean,
    onOpen: (SavedSearch) -> Unit,
    onSave: (String) -> Unit,
    onTogglePin: (SavedSearch) -> Unit,
    onDelete: (SavedSearch) -> Unit,
    modifier: Modifier = Modifier,
) {
    var naming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<SavedSearch?>(null) }

    if (saved.isEmpty() && !canSave) return

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            saved.forEach { item ->
                ElevatedFilterChip(
                    selected = item.id == activeId,
                    onClick = { onOpen(item) },
                    label = {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = if (item.pinned) {
                        { Icon(Icons.Filled.PushPin, contentDescription = null) }
                    } else {
                        null
                    },
                    trailingIcon = if (item.id == activeId) {
                        {
                            IconButton(onClick = { confirmingDelete = item }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Delete ${item.name}")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            if (canSave) {
                AssistChip(onClick = { naming = true }, label = { Text("Save this search") })
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this search") },
            text = {
                Column {
                    Text(
                        "It becomes a smart album — it stays live, so new photos that match " +
                            "show up in it automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onSave(name); naming = false },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    confirmingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete \"${item.name}\"?") },
            text = {
                Text("Only the saved search goes away. Your photos and tags are untouched.")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(item); confirmingDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onTogglePin(item); confirmingDelete = null }) {
                        Text(if (item.pinned) "Unpin" else "Pin")
                    }
                    TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
                }
            },
        )
    }
}

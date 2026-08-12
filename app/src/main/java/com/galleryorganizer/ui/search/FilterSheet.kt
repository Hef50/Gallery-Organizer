package com.galleryorganizer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.dao.BucketSummary
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.filterTree
import com.galleryorganizer.domain.model.flattenVisible
import com.galleryorganizer.domain.model.index
import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SortOrder
import com.galleryorganizer.ui.tags.TagViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Where a tag sits in the current filter. Tapping cycles through these in order. */
enum class TagFilterState { Off, Include, Exclude }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    query: SearchQuery,
    buckets: List<BucketSummary>,
    tagViewModel: TagViewModel,
    onApply: (SearchQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tree by tagViewModel.tree.collectAsStateWithLifecycle()
    var draft by remember(query) { mutableStateOf(query) }
    var tagQuery by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val filteredTree = remember(tree, tagQuery) { tree.filterTree(tagQuery) }
    val visibleTags = remember(filteredTree) {
        filteredTree.flattenVisible(filteredTree.index().keys)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { draft = SearchQuery(text = draft.text) }) { Text("Reset") }
            }

            Section("Media type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaTypeFilter.entries.forEach { type ->
                    FilterChip(
                        selected = draft.mediaType == type,
                        onClick = { draft = draft.copy(mediaType = type) },
                        label = { Text(type.label) },
                    )
                }
            }

            Section("Sort")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { order ->
                    FilterChip(
                        selected = draft.sort == order,
                        onClick = { draft = draft.copy(sort = order) },
                        label = { Text(order.label) },
                    )
                }
            }

            Section("Date taken")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(draft.dateRangeLabel()) },
                )
                if (draft.takenFrom != null || draft.takenTo != null) {
                    TextButton(
                        onClick = { draft = draft.copy(takenFrom = null, takenTo = null) },
                    ) { Text("Any date") }
                }
            }

            if (buckets.isNotEmpty()) {
                Section("Folder")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buckets.take(MAX_FOLDER_CHIPS).forEach { bucket ->
                        val selected = bucket.bucketId in draft.bucketIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                draft = draft.copy(
                                    bucketIds = if (selected) {
                                        draft.bucketIds - bucket.bucketId
                                    } else {
                                        draft.bucketIds + bucket.bucketId
                                    },
                                )
                            },
                            label = {
                                Text(
                                    "${bucket.bucketName} (${bucket.itemCount})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            Section("Tags")
            Text(
                "Tap once to require a tag, twice to exclude it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include sub-tags", Modifier.weight(1f))
                Switch(
                    checked = draft.expandSubtrees,
                    onCheckedChange = { draft = draft.copy(expandSubtrees = it) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only untagged items", Modifier.weight(1f))
                Switch(
                    checked = draft.untaggedOnly,
                    onCheckedChange = { draft = draft.copy(untaggedOnly = it) },
                )
            }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(visibleTags, key = { it.id }) { node ->
                    TagFilterRow(
                        node = node,
                        state = draft.stateOf(node.id),
                        onClick = { draft = draft.cycleTag(node.id) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onApply(draft); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show results") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = draft.takenFrom,
            initialSelectedEndDateMillis = draft.takenTo,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft = draft.copy(
                            takenFrom = state.selectedStartDateMillis,
                            // The picker returns midnight UTC for the end day; without
                            // pushing it to the end of that day, a range of "12th to 12th"
                            // would match nothing taken after midnight.
                            takenTo = state.selectedEndDateMillis?.plus(DAY_MILLIS - 1),
                        )
                        showDatePicker = false
                    },
                ) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(state = state, modifier = Modifier.heightIn(max = 520.dp))
        }
    }
}

@Composable
private fun TagFilterRow(node: TagNode, state: TagFilterState, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 20).dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = when (state) {
                TagFilterState.Include -> Icons.Filled.CheckCircle
                TagFilterState.Exclude -> Icons.Filled.Block
                TagFilterState.Off -> Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = when (state) {
                TagFilterState.Include -> MaterialTheme.colorScheme.primary
                TagFilterState.Exclude -> MaterialTheme.colorScheme.error
                TagFilterState.Off -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            "%,d".format(node.subtreeCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

// --- pure helpers, unit-tested ---------------------------------------------------------

internal fun SearchQuery.stateOf(tagId: Long): TagFilterState = when (tagId) {
    in allTags -> TagFilterState.Include
    in noneTags -> TagFilterState.Exclude
    else -> TagFilterState.Off
}

/**
 * Off → include → exclude → off. Required and excluded are mutually exclusive, so the
 * cycle removes from one list as it adds to the other; leaving a tag in both would produce
 * a query that can never match anything.
 */
internal fun SearchQuery.cycleTag(tagId: Long): SearchQuery = when (stateOf(tagId)) {
    TagFilterState.Off -> copy(allTags = allTags + tagId, noneTags = noneTags - tagId)
    TagFilterState.Include -> copy(allTags = allTags - tagId, noneTags = noneTags + tagId)
    TagFilterState.Exclude -> copy(allTags = allTags - tagId, noneTags = noneTags - tagId)
}

internal fun SearchQuery.dateRangeLabel(): String {
    val from = takenFrom?.let(::formatDay)
    val to = takenTo?.let(::formatDay)
    return when {
        from != null && to != null && from == to -> from
        from != null && to != null -> "$from – $to"
        from != null -> "From $from"
        to != null -> "Until $to"
        else -> "Any date"
    }
}

private fun formatDay(millis: Long): String =
    DAY_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private val MediaTypeFilter.label: String
    get() = when (this) {
        MediaTypeFilter.Any -> "Everything"
        MediaTypeFilter.Photos -> "Photos"
        MediaTypeFilter.Videos -> "Videos"
    }

private val SortOrder.label: String
    get() = when (this) {
        SortOrder.NewestFirst -> "Newest"
        SortOrder.OldestFirst -> "Oldest"
        SortOrder.RecentlyAdded -> "Recently added"
        SortOrder.Largest -> "Largest"
    }

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_FOLDER_CHIPS = 24

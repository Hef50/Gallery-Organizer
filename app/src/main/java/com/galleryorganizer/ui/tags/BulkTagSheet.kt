package com.galleryorganizer.ui.tags

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.repo.TagCheckState
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.filterTree
import com.galleryorganizer.domain.model.flattenVisible
import com.galleryorganizer.domain.model.index

/**
 * Bulk tagging. This is the feature the app exists for, so the interaction is as short as
 * it can be: with a selection already made, **tapping the tag button and then tapping a
 * tag is the whole gesture** — two taps, one database transaction, undoable from the
 * snackbar. There is no Apply button to hunt for and no confirmation step.
 *
 * Checkboxes are tri-state against the selection: filled when every selected item carries
 * the tag, dashed when only some do. Tapping a dashed one applies it to all of them,
 * which is what "some are tagged, I want them all tagged" almost always means.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkTagSheet(
    selection: Set<Long>,
    viewModel: TagViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val recent by viewModel.recentlyUsed.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(selection) { viewModel.refreshCoverage(selection) }

    val filtered = remember(tree, query) { tree.filterTree(query) }
    // Searching expands everything, or matches nested three levels down would be invisible
    // behind a collapsed parent.
    val effectiveExpanded = remember(filtered, expanded, query) {
        if (query.isBlank()) expanded else filtered.index().keys
    }
    val visible = remember(filtered, effectiveExpanded) { filtered.flattenVisible(effectiveExpanded) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).heightIn(max = 560.dp)) {
            Text(
                "Tag ${selection.size} ${if (selection.size == 1) "item" else "items"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (recent.isNotEmpty() && query.isBlank()) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { tag ->
                        AssistChip(
                            onClick = { viewModel.applyTag(selection, tag.id, tag.name) },
                            label = { Text(tag.name) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Find or create a tag") },
                modifier = Modifier.fillMaxWidth(),
            )

            val exactExists = remember(filtered, query) {
                query.isNotBlank() &&
                    filtered.index().values.any { it.name.equals(query.trim(), ignoreCase = true) }
            }
            if (query.isNotBlank() && !exactExists) {
                TextButton(
                    onClick = {
                        viewModel.createAndApply(selection, query, com.galleryorganizer.data.db.entity.TagEntity.ROOT_PARENT_ID)
                        query = ""
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create \"${query.trim()}\" and apply")
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.fillMaxWidth()) {
                items(visible, key = { it.id }) { node ->
                    TagRow(
                        node = node,
                        state = coverage[node.id] ?: TagCheckState.None,
                        expanded = node.id in effectiveExpanded,
                        onToggleExpand = { viewModel.toggleExpanded(node.id) },
                        onToggleTag = { viewModel.toggleTagOnSelection(selection, node) },
                        onAddChild = { name -> viewModel.createAndApply(selection, name, node.id) },
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) {
                                "No tags yet. Type a name above to create your first one."
                            } else {
                                "No tag matches \"$query\"."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TagRow(
    node: TagNode,
    state: TagCheckState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleTag: () -> Unit,
    onAddChild: (String) -> Unit,
) {
    var addingChild by remember { mutableStateOf(false) }
    var childName by remember { mutableStateOf("") }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleTag)
                .padding(start = (node.depth * 20).dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (node.hasChildren) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.width(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            } else {
                Spacer(Modifier.width(32.dp))
            }

            Icon(
                imageVector = when (state) {
                    TagCheckState.All -> Icons.Filled.CheckBox
                    TagCheckState.Some -> Icons.Filled.IndeterminateCheckBox
                    TagCheckState.None -> Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = null,
                tint = when (state) {
                    TagCheckState.None -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> node.color?.let(::Color) ?: MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (node.subtreeCount > 0) {
                Text(
                    "%,d".format(node.subtreeCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { addingChild = !addingChild }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a tag under ${node.name}")
            }
        }

        if (addingChild) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = (node.depth * 20 + 44).dp, bottom = 8.dp),
            ) {
                OutlinedTextField(
                    value = childName,
                    onValueChange = { childName = it },
                    singleLine = true,
                    label = { Text("Under ${node.name}") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (childName.isNotBlank()) onAddChild(childName)
                        childName = ""
                        addingChild = false
                    },
                ) { Text("Add") }
            }
        }
    }
}

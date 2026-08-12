package com.galleryorganizer.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.flattenVisible

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(viewModel: TagViewModel, onBack: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val visible = remember(tree, expanded) { tree.flattenVisible(expanded) }

    var creatingUnder by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<TagNode?>(null) }
    var deleting by remember { mutableStateOf<TagNode?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creatingUnder = TagEntity.ROOT_PARENT_ID }) {
                Icon(Icons.Filled.Add, contentDescription = "New tag")
            }
        },
    ) { padding ->
        if (visible.isEmpty()) {
            EmptyTags(Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(visible, key = { it.id }) { node ->
                    ManagerRow(
                        node = node,
                        expanded = node.id in expanded,
                        onToggleExpand = { viewModel.toggleExpanded(node.id) },
                        onAddChild = { creatingUnder = node.id },
                        onRename = { renaming = node },
                        onDelete = { deleting = node },
                    )
                }
            }
        }
    }

    creatingUnder?.let { parentId ->
        TagNameDialog(
            title = if (parentId == TagEntity.ROOT_PARENT_ID) "New tag" else "New tag under this one",
            initial = "",
            onConfirm = { viewModel.createTag(it, parentId); creatingUnder = null },
            onDismiss = { creatingUnder = null },
        )
    }

    renaming?.let { node ->
        TagNameDialog(
            title = "Rename tag",
            initial = node.name,
            onConfirm = { viewModel.rename(node.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { node ->
        val affected = node.subtreeCount
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${node.name}\"?") },
            text = {
                Text(
                    buildString {
                        if (node.hasChildren) {
                            append("This also deletes every tag underneath it. ")
                        }
                        if (affected > 0) {
                            append("%,d item%s will lose this tag. ".format(affected, if (affected == 1) "" else "s"))
                        }
                        // Worth being explicit: this app's whole promise is that it does
                        // not touch the user's files.
                        append("Your photos and videos are not affected.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteTag(node.id); deleting = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ManagerRow(
    node: TagNode,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = node.hasChildren, onClick = onToggleExpand)
            .padding(start = (8 + node.depth * 20).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        if (node.hasChildren) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.width(28.dp),
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Icon(
            Icons.Filled.Sell,
            contentDescription = null,
            tint = node.color?.let(::Color) ?: MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (node.hasChildren && node.subtreeCount != node.ownCount) {
                    "%,d here · %,d including sub-tags".format(node.ownCount, node.subtreeCount)
                } else {
                    "%,d item%s".format(node.ownCount, if (node.ownCount == 1) "" else "s")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAddChild) {
            Icon(Icons.Filled.Add, contentDescription = "Add a tag under ${node.name}")
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename ${node.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${node.name}")
        }
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyTags(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Sell,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("No tags yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Select some photos in the gallery and tag them, or create a structure here " +
                "first — tags can nest, like Travel / Japan / Kyoto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

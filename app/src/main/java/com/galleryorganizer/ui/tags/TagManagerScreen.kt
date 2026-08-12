package com.galleryorganizer.ui.tags

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.flattenVisible
import com.galleryorganizer.ui.components.TagChip
import com.galleryorganizer.ui.components.accent
import com.galleryorganizer.ui.components.icon
import com.galleryorganizer.ui.components.tagAccent
import com.galleryorganizer.ui.theme.Motion
import com.galleryorganizer.ui.theme.TagPalette

/**
 * The tag structure, as a place rather than a settings page.
 *
 * Tags are now a top-level section, so there is no back arrow and no app bar — the same
 * header the other sections use, then the tags themselves. Each row shows what kind of
 * thing it names, and the kind row at the top narrows the list to People, Places, Events or
 * Things, which is what makes two hundred tags navigable.
 */
@Composable
fun TagManagerScreen(
    viewModel: TagViewModel,
    header: @Composable () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()

    var kindFilter by remember { mutableStateOf<TagKind?>(null) }
    var creatingUnder by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<TagNode?>(null) }
    var deleting by remember { mutableStateOf<TagNode?>(null) }
    var styling by remember { mutableStateOf<TagNode?>(null) }

    val roots = remember(tree, kindFilter) {
        if (kindFilter == null) tree else tree.filter { it.kind == kindFilter }
    }
    val visible = remember(roots, expanded) { roots.flattenVisible(expanded) }

    Column(Modifier.fillMaxSize()) {
        header()

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip(label = "All", selected = kindFilter == null, onClick = { kindFilter = null })
            TagKind.entries.forEach { kind ->
                TagChip(
                    label = kind.label,
                    selected = kindFilter == kind,
                    accent = kind.accent,
                    leadingIcon = kind.icon,
                    onClick = { kindFilter = if (kindFilter == kind) null else kind },
                )
            }
            TagChip(
                label = "New tag",
                leadingIcon = Icons.Rounded.Add,
                onClick = { creatingUnder = TagEntity.ROOT_PARENT_ID },
            )
        }

        if (visible.isEmpty()) {
            EmptyTags(kindFilter)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(visible, key = { it.id }) { node ->
                    ManagerRow(
                        node = node,
                        expanded = node.id in expanded,
                        onToggleExpand = { viewModel.toggleExpanded(node.id) },
                        onAddChild = { creatingUnder = node.id },
                        onRename = { renaming = node },
                        onStyle = { styling = node },
                        onDelete = { deleting = node },
                    )
                }
            }
        }
    }

    creatingUnder?.let { parentId ->
        val underRoot = parentId == TagEntity.ROOT_PARENT_ID
        TagNameDialog(
            title = if (underRoot) "New tag" else "New tag underneath",
            initial = "",
            // A new root tag needs a kind; a child inherits its parent's, which the
            // repository resolves, so the picker only appears where the answer is unknown.
            kind = if (underRoot) (kindFilter ?: TagKind.Note) else null,
            onConfirm = { name, kind ->
                viewModel.createTag(name, parentId, kind)
                creatingUnder = null
            },
            onDismiss = { creatingUnder = null },
        )
    }

    renaming?.let { node ->
        TagNameDialog(
            title = "Rename tag",
            initial = node.name,
            kind = null,
            onConfirm = { name, _ -> viewModel.rename(node.id, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    styling?.let { node ->
        StyleDialog(
            node = node,
            onKind = { viewModel.setKind(node.id, it) },
            onColor = { viewModel.setColor(node.id, it) },
            onDismiss = { styling = null },
        )
    }

    deleting?.let { node ->
        val affected = node.subtreeCount
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${node.name}”?") },
            text = {
                Text(
                    buildString {
                        if (node.hasChildren) append("This also deletes every tag underneath it. ")
                        if (affected > 0) {
                            append(
                                "%,d item%s will lose this tag. "
                                    .format(affected, if (affected == 1) "" else "s"),
                            )
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
    onStyle: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = tagAccent(node.color, node.kind)
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, Motion.spatial(), label = "chevron")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = node.hasChildren, onClick = onToggleExpand)
            .padding(start = (10 + node.depth * 18).dp, end = 6.dp),
    ) {
        if (node.hasChildren) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(26.dp).size(18.dp).graphicsLayer { rotationZ = chevron },
            )
        } else {
            Spacer(Modifier.width(26.dp))
        }

        Box(
            Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                node.kind.icon,
                contentDescription = node.kind.label,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
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
        RowIcon(Icons.Rounded.Add, "Add a tag under ${node.name}", onAddChild)
        RowIcon(Icons.Rounded.Edit, "Rename ${node.name}", onRename)
        RowIcon(Icons.Rounded.Palette, "Change how ${node.name} looks", onStyle)
        RowIcon(Icons.Rounded.Delete, "Delete ${node.name}", onDelete)
    }
}

@Composable
private fun RowIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    initial: String,
    kind: TagKind?,
    onConfirm: (String, TagKind?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var chosen by remember { mutableStateOf(kind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind != null) {
                    Spacer(Modifier.height(14.dp))
                    KindPicker(chosen ?: TagKind.Note) { chosen = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name, chosen) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StyleDialog(
    node: TagNode,
    onKind: (TagKind) -> Unit,
    onColor: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(node.kind) }
    var color by remember { mutableStateOf(node.color) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name) },
        text = {
            Column {
                Text("What it names", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                KindPicker(kind) { kind = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Everything underneath moves with it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                ColorPicker(color, kind) { color = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (kind != node.kind) onKind(kind)
                    if (color != node.color) onColor(color)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindPicker(selected: TagKind, onSelect: (TagKind) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TagKind.entries.forEach { kind ->
            TagChip(
                label = kind.label,
                selected = kind == selected,
                accent = kind.accent,
                leadingIcon = kind.icon,
                onClick = { onSelect(kind) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: Int?, kind: TagKind, onSelect: (Int?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // "Follow the kind" is the default and has to be reachable again after a colour has
        // been picked, or the choice is one-way.
        Swatch(
            color = kind.accent,
            selected = selected == null,
            description = "Match ${kind.label}",
            onClick = { onSelect(null) },
        )
        TagPalette.Swatches.forEach { swatch ->
            val argb = swatch.toArgb()
            Swatch(
                color = swatch,
                selected = selected == argb,
                description = "Custom colour",
                onClick = { onSelect(argb) },
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, description: String, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.15f else 1f, Motion.expressiveSpatial(), label = "swatch")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(30.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (selected) 1f else 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyTags(kind: TagKind?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            (kind ?: TagKind.Note).icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (kind == null) "No tags yet" else "No ${kind.label.lowercase()} yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Select photos in the library and tag them, or build the structure here first " +
                "— tags nest, like Travel / Japan / Kyoto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

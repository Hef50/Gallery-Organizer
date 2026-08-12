package com.galleryorganizer.ui.tags

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.data.repo.TagCheckState
import com.galleryorganizer.domain.model.TagNode
import com.galleryorganizer.domain.model.filterTree
import com.galleryorganizer.domain.model.flattenVisible
import com.galleryorganizer.domain.model.index
import com.galleryorganizer.ui.components.TagChip
import com.galleryorganizer.ui.components.accent
import com.galleryorganizer.ui.components.hint
import com.galleryorganizer.ui.components.icon
import com.galleryorganizer.ui.components.tagAccent
import com.galleryorganizer.ui.theme.Motion

/**
 * Bulk tagging. This is the feature the app exists for, so the interaction is as short as
 * it can be: with a selection already made, **tapping the tag button and then tapping a
 * tag is the whole gesture** — two taps, one database transaction, undoable from the
 * snackbar. There is no Apply button to hunt for and no confirmation step.
 *
 * The sheet has two halves, and the split is the point. The top is a wall of chips — recent
 * tags and, once a kind is chosen, that kind's tags — sized so the tags someone actually
 * uses are reachable with a thumb and never more than one tap away. The bottom is the full
 * tree, for the rarer case of "it is in here somewhere". A flat alphabetical list would
 * make the common case as slow as the rare one.
 *
 * Checkboxes are tri-state against the selection: filled when every selected item carries
 * the tag, dashed when only some do. Tapping a dashed one applies it to all of them, which
 * is what "some are tagged, I want them all tagged" almost always means.
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
    /** Null means "everything"; picking a kind narrows the chips and pre-kinds new tags. */
    var kindFilter by remember { mutableStateOf<TagKind?>(null) }

    LaunchedEffect(selection) { viewModel.refreshCoverage(selection) }

    val searching = query.isNotBlank()
    val byKind = remember(tree, kindFilter) {
        if (kindFilter == null) tree else tree.filter { it.kind == kindFilter }
    }
    val filtered = remember(byKind, query) { byKind.filterTree(query) }
    val flatIndex = remember(filtered) { filtered.index() }
    // Searching expands everything, or matches nested three levels down would be invisible
    // behind a collapsed parent.
    val effectiveExpanded = remember(flatIndex, expanded, searching) {
        if (searching) flatIndex.keys else expanded
    }
    val visible = remember(filtered, effectiveExpanded) { filtered.flattenVisible(effectiveExpanded) }

    val exactExists = remember(flatIndex, query) {
        searching && flatIndex.values.any { it.name.equals(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetGrip() },
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Tag %,d %s".format(selection.size, if (selection.size == 1) "photo" else "photos"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 2.dp),
            )
            Text(
                "Tap a tag to apply it to all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.height(14.dp))

            TagSearchField(
                text = query,
                onTextChange = { query = it },
                placeholder = kindFilter?.hint ?: "Find or create a tag",
            )

            Spacer(Modifier.height(10.dp))

            KindFilterRow(selected = kindFilter, onSelect = { kindFilter = it })

            // Create-and-apply, one tap, kinded by whatever section is showing.
            AnimatedVisibility(
                visible = searching && !exactExists,
                enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
                exit = fadeOut(Motion.fastEffects()) + shrinkVertically(Motion.spatial()),
            ) {
                CreateTagRow(
                    name = query.trim(),
                    kind = kindFilter ?: TagKind.Note,
                    onCreate = {
                        viewModel.createAndApply(
                            selection,
                            query,
                            TagEntity.ROOT_PARENT_ID,
                            kindFilter ?: TagKind.Note,
                        )
                        query = ""
                    },
                )
            }

            AnimatedVisibility(
                visible = recent.isNotEmpty() && !searching && kindFilter == null,
                enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
                exit = fadeOut(Motion.fastEffects()) + shrinkVertically(Motion.spatial()),
            ) {
                Column {
                    SectionLabel("Recent")
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recent.forEach { tag ->
                            val state = coverage[tag.id] ?: TagCheckState.None
                            TagChip(
                                label = tag.name,
                                selected = state == TagCheckState.All,
                                partial = state == TagCheckState.Some,
                                accent = tagAccent(tag.color, tag.kind),
                                leadingIcon = tag.kind.icon,
                                onClick = {
                                    if (state == TagCheckState.All) {
                                        viewModel.removeTag(selection, tag.id, tag.name)
                                    } else {
                                        viewModel.applyTag(selection, tag.id, tag.name)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            SectionLabel(
                when {
                    searching -> "Matches"
                    kindFilter != null -> kindFilter!!.label
                    else -> "All tags"
                },
            )

            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
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
                            when {
                                searching -> "Nothing called \"${query.trim()}\" yet."
                                kindFilter != null -> "No ${kindFilter!!.label.lowercase()} yet."
                                else -> "No tags yet. Type a name above to make your first one."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A grip rather than Material's default bar: narrower, dimmer, less of a handle-shaped UI. */
@Composable
private fun SheetGrip() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 8.dp),
    )
}

/**
 * The kind row.
 *
 * This is what makes a two-hundred-tag library navigable: four taps of context — who,
 * where, when, what — before a single letter is typed. It also decides the kind of anything
 * created while the filter is on, so the tag lands in the right section without a second
 * decision.
 */
@Composable
private fun KindFilterRow(selected: TagKind?, onSelect: (TagKind?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TagChip(
            label = "All",
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        TagKind.entries.forEach { kind ->
            TagChip(
                label = kind.label,
                selected = selected == kind,
                accent = kind.accent,
                leadingIcon = kind.icon,
                onClick = { onSelect(if (selected == kind) null else kind) },
            )
        }
    }
}

@Composable
private fun CreateTagRow(name: String, kind: TagKind, onCreate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(kind.accent.copy(alpha = 0.14f))
            .clickable(onClick = onCreate)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = kind.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Create “$name”",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "in ${kind.label} · applies to the selection straight away",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(kind.icon, contentDescription = null, tint = kind.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TagSearchField(text: String, onTextChange: (String) -> Unit, placeholder: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    placeholder,
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }
}

/**
 * One row of the tree.
 *
 * The whole row is the tap target for applying the tag; only the chevron toggles children
 * and only the plus adds one. Making the row itself do the common thing is what keeps
 * tagging to two taps even four levels down.
 */
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
    val accent = tagAccent(node.color, node.kind)
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, Motion.spatial(), label = "chevron")

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleTag)
                .padding(start = 18.dp + (node.depth * 18).dp, end = 12.dp),
        ) {
            TriStateMark(state, accent)
            Spacer(Modifier.width(14.dp))
            Icon(
                node.kind.icon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.9f),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            if (node.subtreeCount > 0) {
                Text(
                    "%,d".format(node.subtreeCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            RowAction(Icons.Rounded.Add, "Add a tag under ${node.name}") {
                addingChild = !addingChild
            }
            if (node.hasChildren) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevron },
                    )
                }
            } else {
                Spacer(Modifier.width(38.dp))
            }
        }

        AnimatedVisibility(
            visible = addingChild,
            enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
            exit = fadeOut(Motion.fastEffects()) + shrinkVertically(Motion.spatial()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = 44.dp + (node.depth * 18).dp,
                    end = 18.dp,
                    bottom = 10.dp,
                ),
            ) {
                TagSearchField(
                    text = childName,
                    onTextChange = { childName = it },
                    placeholder = "New tag under ${node.name}",
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(end = 18.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TagChip(
                    label = "Add",
                    selected = childName.isNotBlank(),
                    accent = accent,
                    onClick = {
                        if (childName.isNotBlank()) onAddChild(childName)
                        childName = ""
                        addingChild = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The tri-state mark.
 *
 * A round filled disc rather than a square checkbox: a checkbox is form furniture and reads
 * as "tick these, then press OK", which is exactly the wrong mental model here — every tap
 * has already been committed.
 */
@Composable
private fun TriStateMark(state: TagCheckState, accent: Color) {
    val target = when (state) {
        TagCheckState.All -> 1f
        TagCheckState.Some -> 0.55f
        TagCheckState.None -> 0f
    }
    val fill by animateFloatAsState(target, Motion.spatial(), label = "tagMark")

    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (state == TagCheckState.None) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        accent.copy(alpha = 0.25f + 0.75f * fill)
                    },
                ),
        )
        if (state != TagCheckState.None) {
            Icon(
                if (state == TagCheckState.All) Icons.Rounded.Check else Icons.Rounded.Remove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { scaleX = 0.6f + 0.4f * fill; scaleY = 0.6f + 0.4f * fill },
            )
        }
    }
}

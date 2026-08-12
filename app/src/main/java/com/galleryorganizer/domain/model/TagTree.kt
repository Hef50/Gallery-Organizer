package com.galleryorganizer.domain.model

import com.galleryorganizer.data.db.dao.TagWithCount
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagKind

/** A tag plus its place in the hierarchy, ready to render. */
data class TagNode(
    val id: Long,
    val name: String,
    val parentId: Long,
    val color: Int?,
    val kind: TagKind = TagKind.Note,
    /** Items carrying this exact tag. */
    val ownCount: Int,
    /** Items carrying this tag or anything under it — what the user actually expects. */
    val subtreeCount: Int,
    val depth: Int,
    val children: List<TagNode>,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()

    /** `Travel / Japan / Kyoto`, for chips and search summaries. */
    fun pathWithin(index: Map<Long, TagNode>): String {
        val parts = ArrayDeque<String>()
        var node: TagNode? = this
        var guard = 0
        while (node != null && guard++ < MAX_DEPTH) {
            parts.addFirst(node.name)
            node = index[node.parentId]
        }
        return parts.joinToString(" / ")
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

/**
 * Builds the tag forest from the flat table.
 *
 * A pure function over a flat list rather than a recursive query, because the whole tag
 * table is at most a few thousand rows and rebuilding the tree in memory on every change
 * is far cheaper than a CTE per node. Siblings are ordered case-insensitively by name.
 *
 * Orphans — a tag whose parent no longer exists, which a bad import could produce — are
 * promoted to roots rather than dropped. Losing a user's tags to a dangling pointer is
 * not an acceptable failure mode.
 */
fun buildTagTree(tags: List<TagWithCount>): List<TagNode> {
    if (tags.isEmpty()) return emptyList()

    val byParent = tags.groupBy { it.parentId }
    val knownIds = tags.mapTo(HashSet()) { it.id }
    // Doubles as the cycle guard: a tag already placed in the forest is never placed
    // again, so a corrupt parent chain terminates instead of recursing forever.
    val placed = HashSet<Long>()

    fun build(tag: TagWithCount, depth: Int): TagNode {
        placed += tag.id
        val children = byParent[tag.id].orEmpty()
            .filter { it.id !in placed }
            .sortedBy { it.name.lowercase() }
            .map { build(it, depth + 1) }
        return TagNode(
            id = tag.id,
            name = tag.name,
            parentId = tag.parentId,
            color = tag.color,
            kind = tag.kind,
            ownCount = tag.itemCount,
            subtreeCount = tag.itemCount + children.sumOf { it.subtreeCount },
            depth = depth,
            children = children,
        )
    }

    // A real root, an orphan whose parent is gone, or a tag that is its own parent.
    val roots = tags.filter {
        it.parentId == TagEntity.ROOT_PARENT_ID || it.parentId !in knownIds || it.parentId == it.id
    }
    val forest = roots.sortedBy { it.name.lowercase() }.map { build(it, 0) }.toMutableList()

    // Anything a cycle has cut off from every root. Rendering it at the top level is
    // wrong-ish, but invisible tags the user cannot delete would be worse: they would
    // still hold assignments and still block name reuse.
    tags.filter { it.id !in placed }
        .sortedBy { it.name.lowercase() }
        .forEach { if (it.id !in placed) forest += build(it, 0) }

    return forest.sortedBy { it.name.lowercase() }
}

/**
 * Roots split into the picker's sections, in [TagKind] declaration order, with empty
 * sections dropped.
 *
 * Only roots are grouped: a child inherits its section from the branch it is in, so
 * `People / Anna / 2019` stays under People even if someone re-kinds the leaf by hand.
 */
fun List<TagNode>.groupedByKind(): List<Pair<TagKind, List<TagNode>>> =
    TagKind.entries.mapNotNull { kind ->
        val section = filter { it.kind == kind }
        if (section.isEmpty()) null else kind to section
    }

/** Depth-first flattening, skipping the children of anything collapsed. */
fun List<TagNode>.flattenVisible(expanded: Set<Long>): List<TagNode> {
    val out = ArrayList<TagNode>()
    fun walk(nodes: List<TagNode>) {
        for (node in nodes) {
            out += node
            if (node.id in expanded) walk(node.children)
        }
    }
    walk(this)
    return out
}

/** Every node in the forest, keyed by id. */
fun List<TagNode>.index(): Map<Long, TagNode> {
    val out = HashMap<Long, TagNode>()
    fun walk(nodes: List<TagNode>) {
        for (node in nodes) {
            out[node.id] = node
            walk(node.children)
        }
    }
    walk(this)
    return out
}

/**
 * Matches [query] against a node or any of its descendants, keeping ancestors of a hit so
 * the result still reads as a tree rather than a pile of leaves.
 */
fun List<TagNode>.filterTree(query: String): List<TagNode> {
    if (query.isBlank()) return this
    val needle = query.trim().lowercase()

    fun prune(node: TagNode): TagNode? {
        // A node that matches keeps its whole branch: searching "Travel" should show what
        // is under Travel, not strip it down to the one row whose name matched.
        if (node.name.lowercase().contains(needle)) return node
        val keptChildren = node.children.mapNotNull(::prune)
        return if (keptChildren.isEmpty()) null else node.copy(children = keptChildren)
    }
    return mapNotNull(::prune)
}

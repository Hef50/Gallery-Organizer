package com.galleryorganizer.domain.model

import com.galleryorganizer.data.db.dao.TagWithCount
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagTreeTest {

    private fun tag(id: Long, name: String, parent: Long = 0, count: Int = 0) =
        TagWithCount(id = id, name = name, parentId = parent, color = null, itemCount = count)

    @Test
    fun `the forest nests and sorts siblings case-insensitively`() {
        val tree = buildTagTree(
            listOf(
                tag(1, "travel"),
                tag(2, "Food"),
                tag(3, "Japan", parent = 1),
                tag(4, "argentina", parent = 1),
            ),
        )

        assertThat(tree.map { it.name }).containsExactly("Food", "travel").inOrder()
        assertThat(tree.last().children.map { it.name })
            .containsExactly("argentina", "Japan").inOrder()
    }

    @Test
    fun `subtree counts roll up while own counts stay exact`() {
        val tree = buildTagTree(
            listOf(
                tag(1, "Travel", count = 2),
                tag(2, "Japan", parent = 1, count = 5),
                tag(3, "Kyoto", parent = 2, count = 7),
            ),
        )

        val travel = tree.single()
        assertThat(travel.ownCount).isEqualTo(2)
        assertThat(travel.subtreeCount).isEqualTo(14)
        assertThat(travel.children.single().subtreeCount).isEqualTo(12)
    }

    @Test
    fun `an orphan is promoted to a root rather than being dropped`() {
        // A tag whose parent is gone — which a bad import could produce. Losing the user's
        // tags to a dangling pointer is not an acceptable failure mode.
        val tree = buildTagTree(listOf(tag(1, "Travel"), tag(9, "Stranded", parent = 404)))

        assertThat(tree.map { it.name }).containsExactly("Stranded", "Travel")
    }

    @Test
    fun `a cycle does not hang the tree builder`() {
        val tree = buildTagTree(listOf(tag(1, "A", parent = 2), tag(2, "B", parent = 1)))
        // Neither is reachable from a real root, so both are treated as roots and the
        // recursion terminates instead of spinning forever.
        assertThat(tree).isNotEmpty()
    }

    @Test
    fun `a self-parented tag does not become its own child`() {
        val tree = buildTagTree(listOf(tag(1, "Loop", parent = 1)))
        assertThat(tree.single().children).isEmpty()
    }

    @Test
    fun `flattening respects what is expanded`() {
        val tree = buildTagTree(
            listOf(
                tag(1, "Travel"),
                tag(2, "Japan", parent = 1),
                tag(3, "Kyoto", parent = 2),
            ),
        )

        assertThat(tree.flattenVisible(emptySet()).map { it.name }).containsExactly("Travel")
        assertThat(tree.flattenVisible(setOf(1L)).map { it.name })
            .containsExactly("Travel", "Japan").inOrder()
        assertThat(tree.flattenVisible(setOf(1L, 2L)).map { it.name })
            .containsExactly("Travel", "Japan", "Kyoto").inOrder()
    }

    @Test
    fun `depth is carried through for indentation`() {
        val tree = buildTagTree(
            listOf(tag(1, "Travel"), tag(2, "Japan", parent = 1), tag(3, "Kyoto", parent = 2)),
        )
        assertThat(tree.flattenVisible(setOf(1L, 2L)).map { it.depth })
            .containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `filtering keeps the ancestors of a match so the result still reads as a tree`() {
        val tree = buildTagTree(
            listOf(
                tag(1, "Travel"),
                tag(2, "Japan", parent = 1),
                tag(3, "Kyoto", parent = 2),
                tag(4, "Food"),
            ),
        )

        val filtered = tree.filterTree("kyo")

        assertThat(filtered.map { it.name }).containsExactly("Travel")
        assertThat(filtered.single().children.single().children.single().name).isEqualTo("Kyoto")
    }

    @Test
    fun `filtering on a parent keeps its whole branch`() {
        val tree = buildTagTree(
            listOf(tag(1, "Travel"), tag(2, "Japan", parent = 1), tag(3, "Food")),
        )
        val filtered = tree.filterTree("travel")
        assertThat(filtered.single().children.map { it.name }).containsExactly("Japan")
    }

    @Test
    fun `paths read as breadcrumbs`() {
        val tree = buildTagTree(
            listOf(tag(1, "Travel"), tag(2, "Japan", parent = 1), tag(3, "Kyoto", parent = 2)),
        )
        val index = tree.index()
        assertThat(index.getValue(3).pathWithin(index)).isEqualTo("Travel / Japan / Kyoto")
    }

    @Test
    fun `an empty tag table is an empty forest, not a crash`() {
        assertThat(buildTagTree(emptyList())).isEmpty()
    }
}

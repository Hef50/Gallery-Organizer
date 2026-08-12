package com.galleryorganizer.data.repo

import com.galleryorganizer.data.db.DbTest
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.TagEntity.Companion.ROOT_PARENT_ID
import com.galleryorganizer.data.db.entity.TagSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TagRepositoryTest : DbTest() {

    private val repo by lazy { TagRepository(db, FtsMaintenance(db), now = { 42L }) }

    private suspend fun seedMedia(count: Int): List<Long> =
        media.insertAll((1L..count).map { sampleMedia(it) })

    @Test
    fun `ensureTag is idempotent and case-insensitive`() = runTest {
        val first = repo.ensureTag("Travel")
        val again = repo.ensureTag("travel")
        assertThat(again).isEqualTo(first)
        assertThat(tags.count()).isEqualTo(1)
    }

    @Test
    fun `ensurePath creates every missing level and returns the leaf`() = runTest {
        val kyoto = repo.ensurePath(listOf("Travel", "Japan", "Kyoto"))

        assertThat(tags.ancestorChain(kyoto).map { it.name })
            .containsExactly("Travel", "Japan", "Kyoto").inOrder()
        assertThat(tags.count()).isEqualTo(3)

        // Running it again reuses the existing levels rather than duplicating them.
        assertThat(repo.ensurePath(listOf("Travel", "Japan", "Kyoto"))).isEqualTo(kyoto)
        assertThat(tags.count()).isEqualTo(3)
    }

    @Test
    fun `bulk tagging writes every assignment and bumps usage once per item`() = runTest {
        val ids = seedMedia(200)
        val travel = repo.ensureTag("Travel")

        val inserted = repo.applyTags(ids, listOf(travel))

        assertThat(inserted).isEqualTo(200)
        assertThat(mediaTags.itemCountFor(travel)).isEqualTo(200)
        val tag = tags.byId(travel)!!
        assertThat(tag.usageCount).isEqualTo(200)
        assertThat(tag.lastUsedAt).isEqualTo(42L)
    }

    @Test
    fun `re-applying a tag is a harmless no-op`() = runTest {
        val ids = seedMedia(5)
        val travel = repo.ensureTag("Travel")
        repo.applyTags(ids, listOf(travel))

        val second = repo.applyTags(ids, listOf(travel))

        assertThat(second).isEqualTo(0)
        assertThat(mediaTags.itemCountFor(travel)).isEqualTo(5)
    }

    @Test
    fun `an automatic pass cannot downgrade a manually applied tag`() = runTest {
        val ids = seedMedia(3)
        val beach = repo.ensureTag("Beach")
        repo.applyTags(ids, listOf(beach), TagSource.Manual)

        repo.applyTags(ids, listOf(beach), TagSource.Auto)

        assertThat(mediaTags.rowsFor(ids.first()).single().source).isEqualTo(TagSource.Manual)
    }

    @Test
    fun `applying more tags than SQLite can bind at once still works`() = runTest {
        // 1200 items x 2 tags is 2400 rows; the naive single IN-clause version of this
        // blows SQLite's bound-variable limit.
        val ids = seedMedia(1_200)
        val a = repo.ensureTag("Alpha")
        val b = repo.ensureTag("Beta")

        val inserted = repo.applyTags(ids, listOf(a, b))

        assertThat(inserted).isEqualTo(2_400)
        assertThat(repo.coverage(ids)[a]).isEqualTo(TagCheckState.All)
        assertThat(fts.count()).isEqualTo(1_200)
    }

    @Test
    fun `coverage collapses into the tri-state the bulk sheet renders`() = runTest {
        val ids = seedMedia(4)
        val travel = repo.ensureTag("Travel")
        val food = repo.ensureTag("Food")
        val unused = repo.ensureTag("Unused")
        repo.applyTags(ids.take(2), listOf(travel))
        repo.applyTags(ids, listOf(food))

        val coverage = repo.coverage(ids)

        assertThat(coverage[travel]).isEqualTo(TagCheckState.Some)
        assertThat(coverage[food]).isEqualTo(TagCheckState.All)
        assertThat(coverage[unused]).isNull() // absent means None
    }

    @Test
    fun `removing a tag from a selection leaves everything else alone`() = runTest {
        val ids = seedMedia(5)
        val travel = repo.ensureTag("Travel")
        repo.applyTags(ids, listOf(travel))

        repo.removeTags(ids.take(2), listOf(travel))

        assertThat(mediaTags.itemCountFor(travel)).isEqualTo(3)
    }

    @Test
    fun `tagging makes an item findable by tag name and by its ancestors`() = runTest {
        val ids = seedMedia(1)
        val kyoto = repo.ensurePath(listOf("Travel", "Japan", "Kyoto"))
        repo.applyTags(ids, listOf(kyoto))

        // Searching a parent should find items tagged with a descendant, so the FTS row
        // carries the whole ancestor chain.
        assertThat(fts.matchIds("Kyoto")).containsExactly(ids.first())
        assertThat(fts.matchIds("Japan")).containsExactly(ids.first())
        assertThat(fts.matchIds("Travel")).containsExactly(ids.first())
    }

    @Test
    fun `renaming a tag updates the text index of everything carrying it`() = runTest {
        val ids = seedMedia(3)
        val tag = repo.ensureTag("Hoiday")
        repo.applyTags(ids, listOf(tag))
        assertThat(fts.matchIds("Hoiday")).hasSize(3)

        repo.rename(tag, "Holiday")

        assertThat(fts.matchIds("Hoiday")).isEmpty()
        assertThat(fts.matchIds("Holiday")).hasSize(3)
    }

    @Test
    fun `deleting a tag removes assignments and text but never media`() = runTest {
        val ids = seedMedia(3)
        val travel = repo.ensureTag("Travel")
        val japan = repo.ensureTag("Japan", travel)
        repo.applyTags(ids, listOf(japan))

        repo.deleteTag(travel)

        assertThat(tags.count()).isEqualTo(0)
        assertThat(mediaTags.count()).isEqualTo(0)
        assertThat(fts.matchIds("Japan")).isEmpty()
        assertThat(media.count()).isEqualTo(3)
    }

    @Test
    fun `a tag cannot be moved into its own subtree`() = runTest {
        val travel = repo.ensureTag("Travel")
        val japan = repo.ensureTag("Japan", travel)
        val kyoto = repo.ensureTag("Kyoto", japan)

        // Allowing this would detach Travel/Japan/Kyoto from the roots entirely: the rows
        // would still exist but nothing would ever render them.
        assertThat(repo.move(travel, kyoto)).isFalse()
        assertThat(repo.move(travel, travel)).isFalse()
        assertThat(tags.byId(travel)!!.parentId).isEqualTo(ROOT_PARENT_ID)
    }

    @Test
    fun `a legal move re-parents and refreshes the text index`() = runTest {
        val ids = seedMedia(2)
        val travel = repo.ensureTag("Travel")
        val food = repo.ensureTag("Food")
        val japan = repo.ensureTag("Japan", travel)
        repo.applyTags(ids, listOf(japan))
        assertThat(fts.matchIds("Travel")).hasSize(2)

        assertThat(repo.move(japan, food)).isTrue()

        assertThat(tags.byId(japan)!!.parentId).isEqualTo(food)
        assertThat(fts.matchIds("Travel")).isEmpty()
        assertThat(fts.matchIds("Food")).hasSize(2)
    }

    @Test
    fun `a move that would collide with a sibling is refused`() = runTest {
        val travel = repo.ensureTag("Travel")
        repo.ensureTag("Japan", travel)
        val orphanJapan = repo.ensureTag("Japan")

        assertThat(repo.move(orphanJapan, travel)).isFalse()
        assertThat(tags.byId(orphanJapan)!!.parentId).isEqualTo(ROOT_PARENT_ID)
    }

    @Test
    fun `setTag drives the tri-state checkbox in both directions`() = runTest {
        val ids = seedMedia(4)
        val travel = repo.ensureTag("Travel")
        db.mediaTagDao().upsert(listOf(MediaTagCrossRef(ids.first(), travel)))
        assertThat(repo.coverage(ids)[travel]).isEqualTo(TagCheckState.Some)

        repo.setTag(ids, travel, checked = true)
        assertThat(repo.coverage(ids)[travel]).isEqualTo(TagCheckState.All)

        repo.setTag(ids, travel, checked = false)
        assertThat(repo.coverage(ids)[travel]).isNull()
    }

    @Test
    fun `promoting auto tags to manual protects them from later automatic passes`() = runTest {
        val ids = seedMedia(2)
        val beach = repo.ensureTag("Beach")
        repo.applyTags(ids, listOf(beach), TagSource.Auto)

        repo.promoteAutoTagsToManual(ids)

        assertThat(mediaTags.rowsFor(ids.first()).single().source).isEqualTo(TagSource.Manual)
    }

    @Test
    fun `a blank tag name is refused rather than creating an unnameable tag`() = runTest {
        assertThat(runCatching { repo.ensureTag("   ") }.isFailure).isTrue()
        assertThat(tags.count()).isEqualTo(0)
    }
}

package com.galleryorganizer.data.db

import android.database.sqlite.SQLiteConstraintException
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagEntity.Companion.ROOT_PARENT_ID
import com.galleryorganizer.data.db.entity.TagSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TagDaoTest : DbTest() {

    private suspend fun tag(name: String, parent: Long = ROOT_PARENT_ID): Long =
        tags.insert(TagEntity(name = name, parentId = parent))

    @Test
    fun `two root tags cannot share a name`() = runTest {
        tag("Travel")
        val clash = runCatching { tag("Travel") }
        assertThat(clash.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `root uniqueness survives because parent_id is 0 rather than NULL`() = runTest {
        // This is the whole reason root tags use 0: with NULL parents SQLite would treat
        // every root as distinct and allow the duplicate above.
        tag("Travel")
        assertThat(tags.byNameUnder(ROOT_PARENT_ID, "Travel")).isNotNull()
        assertThat(tags.count()).isEqualTo(1)
    }

    @Test
    fun `tag names are case-insensitive for uniqueness but keep their capitalisation`() =
        runTest {
            tag("Travel")
            val clash = runCatching { tag("travel") }
            assertThat(clash.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(tags.byNameUnder(ROOT_PARENT_ID, "TRAVEL")?.name).isEqualTo("Travel")
        }

    @Test
    fun `the same name may appear under different parents`() = runTest {
        val travel = tag("Travel")
        val food = tag("Food")
        tag("Japan", travel)
        tag("Japan", food) // must not throw

        assertThat(tags.children(travel).map { it.name }).containsExactly("Japan")
        assertThat(tags.children(food).map { it.name }).containsExactly("Japan")
    }

    @Test
    fun `subtree ids walk the whole hierarchy`() = runTest {
        val travel = tag("Travel")
        val japan = tag("Japan", travel)
        val kyoto = tag("Kyoto", japan)
        val food = tag("Food")

        assertThat(tags.subtreeIds(travel)).containsExactly(travel, japan, kyoto)
        assertThat(tags.subtreeIds(kyoto)).containsExactly(kyoto)
        assertThat(tags.subtreeIds(food)).containsExactly(food)
    }

    @Test
    fun `ancestor chain is returned root first`() = runTest {
        val travel = tag("Travel")
        val japan = tag("Japan", travel)
        val kyoto = tag("Kyoto", japan)

        assertThat(tags.ancestorChain(kyoto).map { it.name })
            .containsExactly("Travel", "Japan", "Kyoto").inOrder()
    }

    @Test
    fun `deleting a subtree removes descendants and their assignments`() = runTest {
        val travel = tag("Travel")
        val japan = tag("Japan", travel)
        val kyoto = tag("Kyoto", japan)
        val keep = tag("Food")
        val mediaId = media.insert(sampleMedia(1))
        mediaTags.upsert(
            listOf(
                MediaTagCrossRef(mediaId, kyoto),
                MediaTagCrossRef(mediaId, keep),
            ),
        )

        tags.deleteSubtree(travel)

        assertThat(tags.allTags().map { it.name }).containsExactly("Food")
        // media_tag rows follow via ON DELETE CASCADE...
        assertThat(mediaTags.tagsFor(mediaId).map { it.name }).containsExactly("Food")
        // ...but the media row itself is untouched.
        assertThat(media.count()).isEqualTo(1)
    }

    @Test
    fun `deleting media cascades to its tag assignments but not to the tags`() = runTest {
        val travel = tag("Travel")
        val mediaId = media.insert(sampleMedia(1))
        mediaTags.upsert(listOf(MediaTagCrossRef(mediaId, travel)))

        media.deleteRow(mediaId)

        assertThat(mediaTags.count()).isEqualTo(0)
        assertThat(tags.count()).isEqualTo(1)
    }

    @Test
    fun `usage counters drive the recently-used row`() = runTest {
        val a = tag("Alpha")
        val b = tag("Beta")
        tags.touchUsage(listOf(a), now = 100, delta = 3)
        tags.touchUsage(listOf(b), now = 200, delta = 1)

        assertThat(tags.observeRecentlyUsed(10).first().map { it.name })
            .containsExactly("Beta", "Alpha").inOrder()
        assertThat(tags.observeMostUsed(10).first().map { it.name })
            .containsExactly("Alpha", "Beta").inOrder()
    }

    @Test
    fun `tag counts ignore missing media`() = runTest {
        val travel = tag("Travel")
        val present = media.insert(sampleMedia(1))
        val gone = media.insert(sampleMedia(2, isMissing = true))
        mediaTags.upsert(
            listOf(MediaTagCrossRef(present, travel), MediaTagCrossRef(gone, travel)),
        )

        val counts = tags.observeAllWithCounts().first()
        assertThat(counts.single().itemCount).isEqualTo(1)
    }

    @Test
    fun `an automatic pass never downgrades a manual tag`() = runTest {
        val travel = tag("Travel")
        val mediaId = media.insert(sampleMedia(1))
        mediaTags.upsert(listOf(MediaTagCrossRef(mediaId, travel, TagSource.Manual)))

        // The auto-tagger inserts with IGNORE, so the existing manual row wins.
        mediaTags.insertIgnoring(listOf(MediaTagCrossRef(mediaId, travel, TagSource.Auto)))

        assertThat(mediaTags.rowsFor(mediaId).single().source).isEqualTo(TagSource.Manual)
    }

    @Test
    fun `coverage reports partial application across a selection`() = runTest {
        val travel = tag("Travel")
        val food = tag("Food")
        val ids = media.insertAll((1L..4L).map { sampleMedia(it) })
        mediaTags.upsert(ids.take(3).map { MediaTagCrossRef(it, travel) })
        mediaTags.upsert(ids.map { MediaTagCrossRef(it, food) })

        val coverage = mediaTags.coverageFor(ids).associate { it.tagId to it.itemCount }

        assertThat(coverage[travel]).isEqualTo(3) // indeterminate
        assertThat(coverage[food]).isEqualTo(4) // fully checked
    }

    @Test
    fun `removing a tag from a selection leaves other selections alone`() = runTest {
        val travel = tag("Travel")
        val ids = media.insertAll((1L..3L).map { sampleMedia(it) })
        mediaTags.upsert(ids.map { MediaTagCrossRef(it, travel) })

        mediaTags.removeTagFrom(travel, ids.take(2))

        assertThat(mediaTags.itemCountFor(travel)).isEqualTo(1)
    }

    @Test
    fun `promoting auto tags to manual leaves manual ones untouched`() = runTest {
        val auto = tag("Beach")
        val manual = tag("Holiday")
        val mediaId = media.insert(sampleMedia(1))
        mediaTags.upsert(
            listOf(
                MediaTagCrossRef(mediaId, auto, TagSource.Auto),
                MediaTagCrossRef(mediaId, manual, TagSource.Manual),
            ),
        )

        mediaTags.changeSource(listOf(mediaId), from = TagSource.Auto, to = TagSource.Manual)

        assertThat(mediaTags.rowsFor(mediaId).map { it.source })
            .containsExactly(TagSource.Manual, TagSource.Manual)
    }
}

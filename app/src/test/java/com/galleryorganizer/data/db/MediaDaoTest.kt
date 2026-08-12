package com.galleryorganizer.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MediaDaoTest : DbTest() {

    @Test
    fun `two copies of the same file are both indexed and both resolvable by hash`() = runTest {
        // content_hash is indexed but NOT unique. A unique index would make the second
        // copy of a duplicated file un-insertable, hiding a real file from the grid and
        // making the duplicate finder impossible. See MediaEntity.contentHash.
        media.insert(sampleMedia(10, name = "IMG_0001.jpg", contentHash = "abc"))
        media.insert(sampleMedia(11, name = "IMG_0001 (1).jpg", contentHash = "abc"))

        assertThat(media.count()).isEqualTo(2)
        assertThat(media.byContentHashAll("abc").map { it.displayName })
            .containsExactly("IMG_0001.jpg", "IMG_0001 (1).jpg")
    }

    @Test
    fun `many rows may sit unhashed at once`() = runTest {
        // Lazy hashing means most of a 150k library is NULL-hashed most of the time.
        media.insertAll((1L..3L).map { sampleMedia(it) })
        assertThat(media.count()).isEqualTo(3)
        assertThat(media.unhashedCount()).isEqualTo(3)
    }

    @Test
    fun `index rows projection returns only what the indexer compares`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1), sampleMedia(2), sampleMedia(3)))

        val rows = media.indexRowsFor(listOf(1L, 3L, 999L))

        assertThat(rows.map { it.mediaStoreId }).containsExactly(1L, 3L)
        assertThat(rows.map { it.id }).containsExactly(ids[0], ids[2])
        assertThat(rows.first().contentHash).isNull()
    }

    @Test
    fun `lazy hashing marks a single row and shrinks the backlog`() = runTest {
        media.insertAll((1L..5L).map { sampleMedia(it) })
        assertThat(media.unhashedCount()).isEqualTo(5)

        val target = media.unhashedTargets(limit = 2)
        assertThat(target).hasSize(2)
        media.setContentHash(target.first().id, "hash-1")

        assertThat(media.unhashedCount()).isEqualTo(4)
        assertThat(media.byContentHash("hash-1")?.id).isEqualTo(target.first().id)
    }

    @Test
    fun `unhashed targets skip missing items`() = runTest {
        media.insertAll(listOf(sampleMedia(1), sampleMedia(2, isMissing = true)))
        assertThat(media.unhashedTargets(limit = 10).map { it.id }).hasSize(1)
    }

    @Test
    fun `marking missing keeps the row and is bounded by the watermark`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, dateModified = 100),
                sampleMedia(2, dateModified = 200),
                // Beyond the point the pass reached: must not be touched, because a
                // resumed pass has not looked at it yet.
                sampleMedia(3, dateModified = 900),
            ),
        )

        val flagged = media.markMissingOutside(seenMediaStoreIds = listOf(1L), throughDateModified = 500)

        assertThat(flagged).isEqualTo(1)
        assertThat(media.count()).isEqualTo(3) // nothing deleted
        assertThat(media.missingItems().map { it.mediaStoreId }).containsExactly(2L)
    }

    @Test
    fun `a returning file is un-flagged rather than re-inserted`() = runTest {
        val id = media.insert(sampleMedia(1, isMissing = true))
        media.markPresent(listOf(id))
        assertThat(media.byId(id)!!.isMissing).isFalse()
        assertThat(media.count()).isEqualTo(1)
    }

    @Test
    fun `forget-missing sweep only removes flagged rows`() = runTest {
        media.insertAll(listOf(sampleMedia(1), sampleMedia(2, isMissing = true)))
        val removed = media.forgetAllMissing()
        assertThat(removed).isEqualTo(1)
        assertThat(media.count()).isEqualTo(1)
    }

    @Test
    fun `duplicate finder groups by content hash and ignores singletons and missing rows`() =
        runTest {
            media.insertAll(
                listOf(
                    sampleMedia(1, contentHash = "same"),
                    sampleMedia(2, contentHash = "same-2"),
                    sampleMedia(3, contentHash = "lonely"),
                    sampleMedia(4, contentHash = "gone", isMissing = true),
                    sampleMedia(5, contentHash = "gone-2", isMissing = true),
                ),
            )
            // Two present rows sharing a hash is the only thing that counts as duplicate.
            media.setContentHash(media.byContentHash("same-2")!!.id, "same")
            media.setContentHash(media.byContentHash("gone-2")!!.id, "gone")

            assertThat(media.duplicateHashes()).containsExactly("same")
            assertThat(media.byContentHashAll("same")).hasSize(2)
        }

    @Test
    fun `bucket summary counts present items per folder`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, bucketId = 1, bucketName = "Camera"),
                sampleMedia(2, bucketId = 1, bucketName = "Camera"),
                sampleMedia(3, bucketId = 2, bucketName = "Screenshots"),
                sampleMedia(4, bucketId = 2, bucketName = "Screenshots", isMissing = true),
            ),
        )

        val buckets = media.observeBuckets().first()

        assertThat(buckets.map { it.bucketName to it.itemCount })
            .containsExactly("Camera" to 2, "Screenshots" to 1)
            .inOrder()
    }
}

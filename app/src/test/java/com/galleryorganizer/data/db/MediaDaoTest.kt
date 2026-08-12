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
    fun `the presence projection pages through the whole table`() = runTest {
        media.insertAll((1L..5L).map { sampleMedia(it, isMissing = it % 2L == 0L) })

        val first = media.presencePage(limit = 3, offset = 0)
        val second = media.presencePage(limit = 3, offset = 3)

        assertThat(first).hasSize(3)
        assertThat(second).hasSize(2)
        assertThat((first + second).map { it.mediaStoreId }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat((first + second).filter { it.isMissing }.map { it.mediaStoreId })
            .containsExactly(2L, 4L)
    }

    @Test
    fun `refreshing from mediastore never clobbers app-owned columns`() = runTest {
        val id = media.insert(sampleMedia(1, contentHash = "keep-me", ocrText = "receipt total"))

        media.updateFromMediaStore(
            id = id,
            mediaStoreId = 1,
            uri = "content://media/external/images/media/1",
            displayName = "renamed.jpg",
            relativePath = "Pictures/Moved/",
            bucketId = 9,
            bucketName = "Moved",
            mime = "image/jpeg",
            isVideo = false,
            size = 2_000_000,
            dateTaken = 1_800_000_000_000,
            dateModified = 1_800_000_000,
            dateAdded = 1_800_000_000,
            duration = 0,
            width = 100,
            height = 200,
            orientation = 90,
        )

        val row = media.byId(id)!!
        assertThat(row.displayName).isEqualTo("renamed.jpg")
        assertThat(row.bucketName).isEqualTo("Moved")
        // The whole point: a file move must not cost the user their identity hash or OCR.
        assertThat(row.contentHash).isEqualTo("keep-me")
        assertThat(row.ocrText).isEqualTo("receipt total")
        assertThat(row.dateFirstIndexed).isEqualTo(1_700_000_000_000L)
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

package com.galleryorganizer.data.repo

import com.galleryorganizer.data.db.DbTest
import com.galleryorganizer.data.db.IndexStateKeys
import com.galleryorganizer.data.media.MediaCollection
import com.galleryorganizer.data.media.MediaStoreItem
import com.galleryorganizer.data.media.MediaStoreSource
import com.galleryorganizer.data.media.ScanCursor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** An in-memory MediaStore, so the indexer's own logic is what is under test. */
class FakeMediaStoreSource(
    var items: MutableList<MediaStoreItem> = mutableListOf(),
) : MediaStoreSource {

    /** Set to make [scan] blow up partway through, standing in for the process dying. */
    var failAfterChunks: Int? = null
    var scanCalls = 0

    override suspend fun scan(
        collection: MediaCollection,
        after: ScanCursor,
        chunkSize: Int,
        onChunk: suspend (List<MediaStoreItem>) -> Unit,
    ) {
        scanCalls++
        val window = items
            .filter { it.isVideo == collection.isVideo }
            .filter {
                it.dateModified > after.dateModified ||
                    (it.dateModified == after.dateModified && it.mediaStoreId > after.mediaStoreId)
            }
            .sortedWith(compareBy({ it.dateModified }, { it.mediaStoreId }))

        var delivered = 0
        for (chunk in window.chunked(chunkSize)) {
            if (failAfterChunks != null && delivered >= failAfterChunks!!) {
                throw IllegalStateException("process died mid-pass")
            }
            onChunk(chunk)
            delivered++
        }
    }

    override suspend fun allIds(collection: MediaCollection): LongArray =
        items.filter { it.isVideo == collection.isVideo }
            .map { it.mediaStoreId }
            .sorted()
            .toLongArray()
}

class MediaIndexerTest : DbTest() {

    private val source = FakeMediaStoreSource()
    private var clock = 1_000L

    private fun indexer(chunkSize: Int = 2) =
        MediaIndexer(source, db, FtsMaintenance(db), now = { clock }, chunkSize = chunkSize)

    private fun item(
        id: Long,
        dateModified: Long = id,
        isVideo: Boolean = false,
        name: String = "IMG_$id.jpg",
        bucketId: Long = 1,
    ) = MediaStoreItem(
        mediaStoreId = id,
        uri = "content://media/external/images/media/$id",
        displayName = name,
        relativePath = "DCIM/Camera/",
        bucketId = bucketId,
        bucketName = "Camera",
        mime = if (isVideo) "video/mp4" else "image/jpeg",
        isVideo = isVideo,
        size = 1000 + id,
        dateTaken = dateModified * 1000,
        dateModified = dateModified,
        dateAdded = dateModified,
        duration = 0,
        width = 100,
        height = 100,
        orientation = 0,
    )

    @Test
    fun `a first pass inserts everything and records the watermark`() = runTest {
        source.items = (1L..5L).map { item(it) }.toMutableList()

        val result = indexer().runPass()

        assertThat(result.inserted).isEqualTo(5)
        assertThat(media.count()).isEqualTo(5)
        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("5:5")
        assertThat(indexer().hasCompletedInitialIndex()).isTrue()
    }

    @Test
    fun `a second pass over an unchanged library reads nothing`() = runTest {
        source.items = (1L..5L).map { item(it) }.toMutableList()
        indexer().runPass()

        val second = indexer().runPass()

        // This is the whole incremental promise: no full rescan on launch.
        assertThat(second.scanned).isEqualTo(0)
        assertThat(second.inserted).isEqualTo(0)
        assertThat(second.updated).isEqualTo(0)
    }

    @Test
    fun `only items past the watermark are picked up`() = runTest {
        source.items = (1L..3L).map { item(it) }.toMutableList()
        indexer().runPass()

        source.items += item(4)
        source.items += item(5)
        val second = indexer().runPass()

        assertThat(second.scanned).isEqualTo(2)
        assertThat(second.inserted).isEqualTo(2)
        assertThat(media.count()).isEqualTo(5)
    }

    @Test
    fun `a pass killed mid-way resumes from the last committed chunk`() = runTest {
        source.items = (1L..6L).map { item(it) }.toMutableList()
        source.failAfterChunks = 2 // two chunks of two commit, then the process "dies"

        runCatching { indexer(chunkSize = 2).runPass() }

        assertThat(media.count()).isEqualTo(4)
        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("4:4")

        source.failAfterChunks = null
        val resumed = indexer(chunkSize = 2).runPass()

        // Exactly the two remaining items — the four already committed are not re-read.
        assertThat(resumed.scanned).isEqualTo(2)
        assertThat(media.count()).isEqualTo(6)
    }

    @Test
    fun `a burst sharing one timestamp resumes without repeating or skipping`() = runTest {
        source.items = (1L..6L).map { item(it, dateModified = 500) }.toMutableList()
        source.failAfterChunks = 1

        runCatching { indexer(chunkSize = 3).runPass() }
        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("500:3")

        source.failAfterChunks = null
        indexer(chunkSize = 3).runPass()

        assertThat(media.count()).isEqualTo(6)
    }

    @Test
    fun `a modified file updates in place and keeps its identity and tags`() = runTest {
        source.items = mutableListOf(item(1, dateModified = 100))
        indexer().runPass()
        val row = media.byId(media.byIds(listOf(1)).firstOrNull()?.id ?: 1)!!
        media.setContentHash(row.id, "stable-identity")
        media.setOcrText(row.id, "some text")

        source.items[0] = item(1, dateModified = 200, name = "renamed.jpg")
        val second = indexer().runPass()

        assertThat(second.updated).isEqualTo(1)
        assertThat(media.count()).isEqualTo(1)
        val updated = media.byId(row.id)!!
        assertThat(updated.displayName).isEqualTo("renamed.jpg")
        // Identity, first-seen and OCR are the app's, not MediaStore's.
        assertThat(updated.contentHash).isEqualTo("stable-identity")
        assertThat(updated.ocrText).isEqualTo("some text")
        assertThat(updated.dateFirstIndexed).isEqualTo(1_000L)
    }

    @Test
    fun `a vanished file is flagged, never deleted, and its row comes back on return`() = runTest {
        source.items = (1L..3L).map { item(it) }.toMutableList()
        indexer().runPass()

        val removed = source.items.removeAt(1)
        val second = indexer().runPass()

        assertThat(second.markedMissing).isEqualTo(1)
        assertThat(media.count()).isEqualTo(3) // nothing deleted
        assertThat(media.missingItems().map { it.mediaStoreId }).containsExactly(2L)

        source.items += removed
        val third = indexer().runPass()

        assertThat(third.restored).isEqualTo(1)
        assertThat(media.missingItems()).isEmpty()
    }

    @Test
    fun `the presence sweep refuses to flag the library when the query comes back empty`() =
        runTest {
            source.items = (1L..3L).map { item(it) }.toMutableList()
            indexer().runPass()

            // Revoked permission or an unmounted volume looks exactly like "no media".
            source.items.clear()
            val second = indexer().runPass()

            assertThat(second.markedMissing).isEqualTo(0)
            assertThat(media.missingItems()).isEmpty()
        }

    @Test
    fun `skipping the sweep leaves presence alone`() = runTest {
        source.items = (1L..3L).map { item(it) }.toMutableList()
        indexer().runPass()
        source.items.removeAt(0)

        val second = indexer().runPass(sweepMissing = false)

        assertThat(second.markedMissing).isEqualTo(0)
        assertThat(media.missingItems()).isEmpty()
    }

    @Test
    fun `images and videos carry independent watermarks`() = runTest {
        source.items = mutableListOf(
            item(1, dateModified = 100),
            item(2, dateModified = 900, isVideo = true, name = "VID.mp4"),
        )

        indexer().runPass()

        assertThat(indexState.get(IndexStateKeys.IMAGE_WATERMARK)).isEqualTo("100:1")
        assertThat(indexState.get(IndexStateKeys.VIDEO_WATERMARK)).isEqualTo("900:2")
    }

    @Test
    fun `newly indexed items are immediately searchable by name and path`() = runTest {
        source.items = mutableListOf(item(1, name = "IMG_kyoto.jpg"))

        indexer().runPass()

        val id = media.byIds(listOf(1)).firstOrNull()?.id ?: media.presencePage(1, 0).single().id
        assertThat(fts.matchIds("kyoto")).containsExactly(id)
        assertThat(fts.matchIds("DCIM")).containsExactly(id)
    }

    @Test
    fun `resetting the watermarks re-reads the library without losing rows or tags`() = runTest {
        source.items = (1L..3L).map { item(it) }.toMutableList()
        indexer().runPass()
        val ids = media.presencePage(10, 0).map { it.id }
        media.setContentHash(ids.first(), "keep")

        indexer().resetWatermarks()
        val second = indexer().runPass()

        assertThat(second.scanned).isEqualTo(3)
        assertThat(second.inserted).isEqualTo(0) // matched by mediastore_id, not re-inserted
        assertThat(media.count()).isEqualTo(3)
        assertThat(media.byId(ids.first())!!.contentHash).isEqualTo("keep")
    }
}

package com.galleryorganizer.data.repo

import com.galleryorganizer.data.db.DbTest
import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Runs the generated SQL against a real database, which is the only way to know it works. */
class SearchRepositoryTest : DbTest() {

    private val search by lazy { SearchRepository(db, now = { 1L }) }
    private val tagRepo by lazy { TagRepository(db, FtsMaintenance(db), now = { 1L }) }

    private suspend fun results(query: SearchQuery): List<Long> = search.idsMatching(query)

    private suspend fun count(query: SearchQuery): Int = search.observeCount(query).first()

    @Test
    fun `an empty query returns everything present, newest first`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, dateTaken = 100),
                sampleMedia(2, dateTaken = 300),
                sampleMedia(3, dateTaken = 200),
                sampleMedia(4, isMissing = true),
            ),
        )

        val ids = results(SearchQuery())

        assertThat(ids).hasSize(3)
        val taken = ids.map { id -> media.byId(id)!!.dateTaken }
        assertThat(taken).isInOrder(compareByDescending<Long> { it })
        assertThat(count(SearchQuery())).isEqualTo(3)
    }

    @Test
    fun `required tags are ANDed, not ORed`() = runTest {
        val ids = media.insertAll((1L..3L).map { sampleMedia(it) })
        val travel = tagRepo.ensureTag("Travel")
        val food = tagRepo.ensureTag("Food")
        tagRepo.applyTags(ids.take(2), listOf(travel))
        tagRepo.applyTags(ids.drop(1), listOf(food))

        // Only the middle item carries both.
        assertThat(results(SearchQuery(allTags = listOf(travel, food)))).containsExactly(ids[1])
        // ...whereas any-of should return all three.
        assertThat(results(SearchQuery(anyTags = listOf(travel, food)))).hasSize(3)
    }

    @Test
    fun `excluded tags remove items even when another filter matched them`() = runTest {
        val ids = media.insertAll((1L..3L).map { sampleMedia(it) })
        val travel = tagRepo.ensureTag("Travel")
        val blurry = tagRepo.ensureTag("Blurry")
        tagRepo.applyTags(ids, listOf(travel))
        tagRepo.applyTags(listOf(ids.first()), listOf(blurry))

        val kept = results(SearchQuery(allTags = listOf(travel), noneTags = listOf(blurry)))

        assertThat(kept).hasSize(2)
        assertThat(kept).doesNotContain(ids.first())
    }

    @Test
    fun `searching a parent tag finds items tagged with a descendant`() = runTest {
        val ids = media.insertAll(listOf(sampleMedia(1), sampleMedia(2)))
        val travel = tagRepo.ensureTag("Travel")
        val kyoto = tagRepo.ensurePath(listOf("Travel", "Japan", "Kyoto"))
        tagRepo.applyTags(listOf(ids.first()), listOf(kyoto))

        assertThat(results(SearchQuery(allTags = listOf(travel)))).containsExactly(ids.first())

        // ...unless the user asked for the exact tag only.
        assertThat(results(SearchQuery(allTags = listOf(travel), expandSubtrees = false)))
            .isEmpty()
    }

    @Test
    fun `text search reaches filenames, paths, tags and ocr text`() = runTest {
        val kyotoPhoto = media.insert(sampleMedia(1, name = "IMG_kyoto.jpg"))
        val tagged = media.insert(sampleMedia(2, name = "IMG_0002.jpg"))
        val scanned = media.insert(
            sampleMedia(3, name = "IMG_0003.jpg", ocrText = "Shinkansen ticket"),
        )
        val tag = tagRepo.ensureTag("Holiday")
        tagRepo.applyTags(listOf(tagged), listOf(tag))
        // OCR is written by P9; the FTS row has to be rebuilt for it to be searchable.
        FtsMaintenance(db).rebuild(listOf(kyotoPhoto, scanned))

        assertThat(results(SearchQuery(text = "kyoto"))).containsExactly(kyotoPhoto)
        assertThat(results(SearchQuery(text = "holiday"))).containsExactly(tagged)
        assertThat(results(SearchQuery(text = "shinkansen"))).containsExactly(scanned)
        assertThat(results(SearchQuery(text = "shink"))).containsExactly(scanned) // prefix
    }

    @Test
    fun `a search box full of punctuation returns everything rather than nothing`() = runTest {
        media.insertAll((1L..3L).map { sampleMedia(it) })
        assertThat(results(SearchQuery(text = "  ???  "))).hasSize(3)
    }

    @Test
    fun `an apostrophe in the search box is data, not syntax`() = runTest {
        val id = media.insert(sampleMedia(1, name = "mum's birthday.jpg"))
        FtsMaintenance(db).rebuild(listOf(id))

        assertThat(results(SearchQuery(text = "mum's"))).containsExactly(id)
        assertThat(results(SearchQuery(text = "'; DROP TABLE media; --"))).isEmpty()
        assertThat(media.count()).isEqualTo(1) // the table is very much still there
    }

    @Test
    fun `filters combine across type, folder, date and tag`() = runTest {
        val ids = media.insertAll(
            listOf(
                sampleMedia(1, isVideo = true, bucketId = 1, dateTaken = 1_000),
                sampleMedia(2, isVideo = true, bucketId = 2, dateTaken = 2_000),
                sampleMedia(3, isVideo = false, bucketId = 1, dateTaken = 1_500),
            ),
        )
        val tag = tagRepo.ensureTag("Keep")
        tagRepo.applyTags(ids, listOf(tag))

        val query = SearchQuery(
            mediaType = MediaTypeFilter.Videos,
            bucketIds = listOf(1),
            takenFrom = 500,
            takenTo = 1_200,
            allTags = listOf(tag),
        )

        assertThat(results(query)).containsExactly(ids.first())
    }

    @Test
    fun `untagged-only is the what-still-needs-organising view`() = runTest {
        val ids = media.insertAll((1L..3L).map { sampleMedia(it) })
        tagRepo.applyTags(listOf(ids.first()), listOf(tagRepo.ensureTag("Done")))

        assertThat(results(SearchQuery(untaggedOnly = true))).hasSize(2)
        assertThat(results(SearchQuery(untaggedOnly = true))).doesNotContain(ids.first())
    }

    @Test
    fun `sorting changes the order without changing the result set`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, dateTaken = 100, size = 900),
                sampleMedia(2, dateTaken = 300, size = 100),
                sampleMedia(3, dateTaken = 200, size = 500),
            ),
        )

        val newest = results(SearchQuery(sort = SortOrder.NewestFirst))
        val oldest = results(SearchQuery(sort = SortOrder.OldestFirst))
        val largest = results(SearchQuery(sort = SortOrder.Largest))

        assertThat(newest).isEqualTo(oldest.reversed())
        assertThat(newest.toSet()).isEqualTo(largest.toSet())
        assertThat(media.byIds(largest).maxByOrNull { it.size }!!.id).isEqualTo(largest.first())
    }

    @Test
    fun `select-all is bounded so it cannot produce an unusable selection`() = runTest {
        media.insertAll((1L..50L).map { sampleMedia(it) })
        assertThat(search.idsMatching(SearchQuery(), limit = 10)).hasSize(10)
    }

    @Test
    fun `results stay live as tags change`() = runTest {
        val ids = media.insertAll((1L..3L).map { sampleMedia(it) })
        val tag = tagRepo.ensureTag("Travel")
        val query = SearchQuery(allTags = listOf(tag))
        assertThat(count(query)).isEqualTo(0)

        tagRepo.applyTags(ids, listOf(tag))

        assertThat(count(query)).isEqualTo(3)
    }

    // --- Saved searches ---------------------------------------------------------------

    @Test
    fun `a saved search round-trips through the database`() = runTest {
        val query = SearchQuery(text = "kyoto", allTags = listOf(4), sort = SortOrder.OldestFirst)
        val id = search.save("Japan trip", query)

        val loaded = search.byId(id)!!

        assertThat(loaded.name).isEqualTo("Japan trip")
        assertThat(loaded.query).isEqualTo(query)
    }

    @Test
    fun `pinned searches sort first`() = runTest {
        search.save("Zebras", SearchQuery(text = "z"))
        val aardvarks = search.save("Aardvarks", SearchQuery(text = "a"))
        search.setPinned(aardvarks, true)

        assertThat(search.observeSaved().first().map { it.name })
            .containsExactly("Aardvarks", "Zebras").inOrder()
    }

    @Test
    fun `deleting a saved search touches nothing else`() = runTest {
        media.insertAll((1L..3L).map { sampleMedia(it) })
        val id = search.save("Temporary", SearchQuery(text = "x"))

        search.delete(id)

        assertThat(search.all()).isEmpty()
        assertThat(media.count()).isEqualTo(3)
    }

    @Test
    fun `an unnamed saved search is refused`() = runTest {
        assertThat(runCatching { search.save("  ", SearchQuery()) }.isFailure).isTrue()
    }
}

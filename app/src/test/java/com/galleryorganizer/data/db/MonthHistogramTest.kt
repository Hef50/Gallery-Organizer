package com.galleryorganizer.data.db

import com.galleryorganizer.data.repo.FtsMaintenance
import com.galleryorganizer.data.repo.SearchRepository
import com.galleryorganizer.data.repo.TagRepository
import com.galleryorganizer.domain.scrub.MonthBucket
import com.galleryorganizer.domain.scrub.foldMonths
import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SearchSql
import com.galleryorganizer.domain.search.SortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The date slider's SQL. The model on top of it is tested purely in `ScrubberTest`; what is
 * checked here is that the histogram really counts the same items the grid would show.
 */
class MonthHistogramTest : DbTest() {

    private val noSubtrees: (Long) -> List<Long> = { listOf(it) }
    private val search by lazy { SearchRepository(db) }

    /** Local midday, so a time-zone shift of a few hours cannot move it into another month. */
    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** The real path: dates out of SQL, months folded in Kotlin. */
    private fun histogramFor(query: SearchQuery): List<MonthBucket> {
        val statement = SearchSql.buildDates(query, noSubtrees)
        val dates = db.query(statement.sql, statement.args.map { it.toString() }.toTypedArray())
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        return foldMonths(dates.asSequence(), ZoneId.systemDefault())
    }

    @Test
    fun `items are grouped into the calendar month they were taken`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, dateTaken = at(2024, 6, 3)),
                sampleMedia(2, dateTaken = at(2024, 6, 28)),
                sampleMedia(3, dateTaken = at(2024, 5, 15)),
                sampleMedia(4, dateTaken = at(2019, 12, 25)),
            ),
        )

        val buckets = histogramFor(SearchQuery())

        // Newest month first, matching the grid's own order.
        assertThat(buckets.map { "${it.year}-${it.month}" })
            .containsExactly("2024-6", "2024-5", "2019-12").inOrder()
        assertThat(buckets.first().itemCount).isEqualTo(2)
        assertThat(buckets.first().latest).isEqualTo(at(2024, 6, 28))
        assertThat(buckets.first().earliest).isEqualTo(at(2024, 6, 3))
    }

    @Test
    fun `the histogram counts exactly what the grid would show`() = runTest {
        media.insertAll((1L..40L).map { sampleMedia(it, dateTaken = at(2024, 1 + (it % 6).toInt(), 5)) })
        media.insertAll(listOf(sampleMedia(99, dateTaken = at(2024, 3, 5), isVideo = true)))

        // Whatever the filter, the slider must add up to the same number the header shows,
        // or dragging it to the bottom would stop short of the end of the grid.
        listOf(
            SearchQuery(),
            SearchQuery(mediaType = MediaTypeFilter.Photos),
            SearchQuery(mediaType = MediaTypeFilter.Videos),
        ).forEach { query ->
            val fromHistogram = histogramFor(query).sumOf { it.itemCount }
            val fromCount = SearchSql.buildCount(query, noSubtrees).let { statement ->
                db.query(statement.sql, statement.args.map { it.toString() }.toTypedArray())
                    .use { it.moveToFirst(); it.getInt(0) }
            }
            assertThat(fromHistogram).isEqualTo(fromCount)
        }
    }

    @Test
    fun `a filter narrows the slider to the months that still have photos`() = runTest {
        val ids = media.insertAll(
            listOf(
                sampleMedia(1, dateTaken = at(2024, 6, 3)),
                sampleMedia(2, dateTaken = at(2020, 2, 3)),
            ),
        )
        val tagRepo = TagRepository(db, FtsMaintenance(db))
        val kyoto = tagRepo.ensureTag("Kyoto")
        tagRepo.applyTags(listOf(ids.first()), listOf(kyoto))

        val buckets = histogramFor(SearchQuery(allTags = listOf(kyoto), expandSubtrees = false))

        // Dragging the slider inside a filtered result must not offer months the filter has
        // emptied.
        assertThat(buckets).hasSize(1)
        assertThat(buckets.single().year).isEqualTo(2024)
    }

    @Test
    fun `oldest first flips the histogram so the rail matches the grid`() = runTest {
        media.insertAll(
            listOf(
                sampleMedia(1, dateTaken = at(2024, 6, 3)),
                sampleMedia(2, dateTaken = at(2019, 2, 3)),
            ),
        )

        val buckets = histogramFor(SearchQuery(sort = SortOrder.OldestFirst))

        assertThat(buckets.first().year).isEqualTo(2019)
        assertThat(buckets.last().year).isEqualTo(2024)
    }

    @Test
    fun `a sort with no dates behind it offers no slider at all`() = runTest {
        media.insertAll((1L..20L).map { sampleMedia(it, dateTaken = at(2024, 6, 3)) })

        // "Largest first" and "recently added" are not date order, so a rail labelled with
        // years would be describing something the grid is not doing.
        assertThat(search.scrubberFor(SearchQuery(sort = SortOrder.Largest)).isUsable).isFalse()
        assertThat(search.scrubberFor(SearchQuery(sort = SortOrder.RecentlyAdded)).isUsable)
            .isFalse()
    }

    @Test
    fun `the model built from a real library can be dragged end to end`() = runTest {
        media.insertAll(
            (0L until 30L).map { i ->
                sampleMedia(i + 1, dateTaken = at(2024 - (i / 12).toInt(), 1 + (i % 12).toInt(), 5))
            },
        )

        val model = search.scrubberFor(SearchQuery())

        assertThat(model.isUsable).isTrue()
        assertThat(model.total).isEqualTo(30)
        assertThat(model.offsetAt(0f)).isEqualTo(0)
        assertThat(model.offsetAt(1f)).isEqualTo(29)
    }
}

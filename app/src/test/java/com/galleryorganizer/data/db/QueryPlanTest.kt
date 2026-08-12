package com.galleryorganizer.data.db

import com.galleryorganizer.data.repo.FtsMaintenance
import com.galleryorganizer.data.repo.TagRepository
import com.galleryorganizer.domain.search.MediaTypeFilter
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SearchSql
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the performance budget by checking *query plans*, not wall-clock time.
 *
 * A timing assertion on Robolectric's SQLite says nothing useful about a phone — the JVM
 * is faster, the data set is smaller, and the number would either be flaky or meaningless.
 * What actually decides whether a query is 5 ms or 5 seconds at 150,000 rows is whether
 * SQLite picks an index or a full table scan, and `EXPLAIN QUERY PLAN` answers that
 * exactly and deterministically.
 *
 * These tests are what catch a dropped index or a `WHERE` clause rewritten in a way SQLite
 * can no longer use one — the changes that silently make the app unusable on a large
 * library while every other test still passes.
 */
class QueryPlanTest : DbTest() {

    private fun planFor(sql: String, args: List<Any> = emptyList()): String {
        val bound = args.map { it.toString() }.toTypedArray()
        return db.query("EXPLAIN QUERY PLAN $sql", bound).use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    appendLine(cursor.getString(cursor.columnCount - 1))
                }
            }
        }
    }

    /** True when the plan reads `media` end to end instead of seeking an index. */
    private fun String.scansMediaTable(): Boolean =
        lineSequence().any { it.startsWith("SCAN media") && !it.contains("USING") }

    @Test
    fun `the default grid query seeks the date_taken index instead of scanning`() = runTest {
        media.insertAll((1L..200L).map { sampleMedia(it) })

        val statement = SearchSql.build(SearchQuery(), noSubtrees)
        val plan = planFor(statement.sql, statement.args)

        // The composite carries the sort: SQLite seeks is_missing = 0 and walks the range
        // already in date order, so LIMIT stops early. Before schema v4 it picked the
        // two-value index_media_is_missing and sorted the whole library in a temp B-tree.
        assertThat(plan).contains("index_media_is_missing_date_taken_id")
        assertThat(plan).doesNotContain("USE TEMP B-TREE FOR ORDER BY")
    }

    @Test
    fun `a tag filter drives off the media_tag index rather than scanning the join table`() =
        runTest {
            val ids = media.insertAll((1L..50L).map { sampleMedia(it) })
            val tags = TagRepository(db, FtsMaintenance(db))
            val travel = tags.ensureTag("Travel")
            tags.applyTags(ids, listOf(travel))

            val statement = SearchSql.build(SearchQuery(allTags = listOf(travel)), noSubtrees)
            val plan = planFor(statement.sql, statement.args)

            // media_tag is the largest table in the database; a scan of it per candidate
            // row would be quadratic.
            assertThat(plan.lineSequence().none { it.startsWith("SCAN mt") }).isTrue()
        }

    @Test
    fun `text search enters through the FTS index, not through media`() = runTest {
        val id = media.insert(sampleMedia(1, name = "kyoto.jpg"))
        FtsMaintenance(db).rebuild(listOf(id))

        val statement = SearchSql.build(SearchQuery(text = "kyoto"), noSubtrees)
        val plan = planFor(statement.sql, statement.args)

        assertThat(plan).contains("media_fts")
    }

    @Test
    fun `the folder filter neither scans nor sorts`() = runTest {
        media.insertAll((1L..50L).map { sampleMedia(it, bucketId = it % 5) })

        val statement = SearchSql.build(SearchQuery(bucketIds = listOf(1)), noSubtrees)
        val plan = planFor(statement.sql, statement.args)

        assertThat(plan.scansMediaTable()).isFalse()
        assertThat(plan).doesNotContain("USE TEMP B-TREE FOR ORDER BY")
    }

    @Test
    fun `restore's fallback identity lookup is an index seek`() = runTest {
        media.insertAll((1L..50L).map { sampleMedia(it) })

        // This runs once per backed-up item; unindexed it is a full scan every time, which
        // is the whole reason schema v2 exists.
        val plan = planFor(
            "SELECT * FROM media WHERE size = ? AND display_name = ?",
            listOf(1001, "IMG_1.jpg"),
        )

        assertThat(plan).contains("index_media_size_display_name")
    }

    @Test
    fun `the indexer's mediastore lookup is an index seek`() = runTest {
        media.insertAll((1L..50L).map { sampleMedia(it) })

        // Runs once per 500-item chunk across the whole library on every pass.
        val plan = planFor("SELECT id FROM media WHERE mediastore_id IN (?, ?)", listOf(1, 2))

        assertThat(plan).contains("index_media_mediastore_id")
    }

    @Test
    fun `the auto-tag work queue is an index seek, not a scan of the library`() = runTest {
        media.insertAll((1L..50L).map { sampleMedia(it) })

        val plan = planFor(
            "SELECT * FROM media WHERE auto_scan_state = 0 AND is_missing = 0 AND is_video = 0 " +
                "ORDER BY date_taken DESC LIMIT 20",
        )

        assertThat(plan.scansMediaTable()).isFalse()
    }

    @Test
    fun `a combined filter still avoids a full table scan`() = runTest {
        val ids = media.insertAll((1L..80L).map { sampleMedia(it, bucketId = it % 4) })
        val tags = TagRepository(db, FtsMaintenance(db))
        val tag = tags.ensureTag("Keep")
        tags.applyTags(ids, listOf(tag))

        val statement = SearchSql.build(
            SearchQuery(
                allTags = listOf(tag),
                bucketIds = listOf(1),
                mediaType = MediaTypeFilter.Photos,
                takenFrom = 0,
                takenTo = Long.MAX_VALUE,
            ),
            noSubtrees,
        )
        val plan = planFor(statement.sql, statement.args)

        assertThat(plan.scansMediaTable()).isFalse()
    }

    private val noSubtrees: (Long) -> List<Long> = { listOf(it) }
}

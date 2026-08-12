package com.galleryorganizer.domain.search

import com.galleryorganizer.ui.search.TagFilterState
import com.galleryorganizer.ui.search.cycleTag
import com.galleryorganizer.ui.search.dateRangeLabel
import com.galleryorganizer.ui.search.stateOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchSqlTest {

    private val noSubtrees: (Long) -> List<Long> = { listOf(it) }

    @Test
    fun `an empty query still hides missing items`() {
        val statement = SearchSql.build(SearchQuery(), noSubtrees)
        assertThat(statement.sql).contains("m.is_missing = 0")
        assertThat(statement.args).isEmpty()
    }

    @Test
    fun `nothing the user typed is ever interpolated into the sql`() {
        val nasty = SearchQuery(
            text = "'; DROP TABLE media; --",
            bucketIds = listOf(7),
            takenFrom = 100,
        )
        val statement = SearchSql.build(nasty, noSubtrees)

        assertThat(statement.sql).doesNotContain("DROP")
        assertThat(statement.sql).doesNotContain("7")
        // The text arrives as a bound argument, tokenised into harmless prefix terms.
        assertThat(statement.args).contains("DROP* TABLE* media*")
        assertThat(statement.args).contains(7L)
        assertThat(statement.args).contains(100L)
    }

    @Test
    fun `each required tag becomes its own EXISTS so they are genuinely ANDed`() {
        val statement = SearchSql.build(SearchQuery(allTags = listOf(1, 2)), noSubtrees)

        // A single EXISTS over both ids would be an OR wearing an AND's clothes.
        assertThat(statement.sql.split("EXISTS (SELECT 1 FROM media_tag").size - 1).isEqualTo(2)
        assertThat(statement.args).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `any-of tags collapse into one EXISTS over the union`() {
        val statement = SearchSql.build(SearchQuery(anyTags = listOf(1, 2, 3)), noSubtrees)
        assertThat(statement.sql.split("EXISTS (SELECT 1 FROM media_tag").size - 1).isEqualTo(1)
        assertThat(statement.args).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `excluded tags become NOT EXISTS`() {
        val statement = SearchSql.build(SearchQuery(noneTags = listOf(9)), noSubtrees)
        assertThat(statement.sql).contains("NOT EXISTS (SELECT 1 FROM media_tag")
        assertThat(statement.args).containsExactly(9L)
    }

    @Test
    fun `subtree expansion widens a tag filter to its descendants`() {
        val expand: (Long) -> List<Long> = { if (it == 1L) listOf(1, 2, 3) else listOf(it) }

        val expanded = SearchSql.build(SearchQuery(allTags = listOf(1)), expand)
        assertThat(expanded.args).containsExactly(1L, 2L, 3L)

        val exact = SearchSql.build(
            SearchQuery(allTags = listOf(1), expandSubtrees = false),
            expand,
        )
        assertThat(exact.args).containsExactly(1L)
    }

    @Test
    fun `tag filters use EXISTS rather than a join, so no DISTINCT is needed`() {
        // A join to media_tag would multiply rows per matching tag and force a DISTINCT
        // over the whole result — a temp B-tree instead of an index scan at 150k rows.
        val statement = SearchSql.build(SearchQuery(allTags = listOf(1), anyTags = listOf(2)), noSubtrees)
        assertThat(statement.sql).doesNotContain("DISTINCT")
        assertThat(statement.sql).doesNotContain("JOIN")
    }

    @Test
    fun `media type and untagged filters are expressed on indexed columns`() {
        assertThat(SearchSql.build(SearchQuery(mediaType = MediaTypeFilter.Videos), noSubtrees).sql)
            .contains("m.is_video = 1")
        assertThat(SearchSql.build(SearchQuery(mediaType = MediaTypeFilter.Photos), noSubtrees).sql)
            .contains("m.is_video = 0")
        assertThat(SearchSql.build(SearchQuery(untaggedOnly = true), noSubtrees).sql)
            .contains("NOT EXISTS (SELECT 1 FROM media_tag mt WHERE mt.media_id = m.id)")
    }

    @Test
    fun `every sort breaks ties on id so paging stays stable`() {
        SortOrder.entries.forEach { order ->
            val sql = SearchSql.build(SearchQuery(sort = order), noSubtrees).sql
            assertThat(sql).contains("ORDER BY")
            assertThat(sql).contains("m.id")
        }
    }

    @Test
    fun `the count query keeps the filters and drops the ordering`() {
        val query = SearchQuery(text = "kyoto", allTags = listOf(1), mediaType = MediaTypeFilter.Photos)
        val count = SearchSql.buildCount(query, noSubtrees)

        assertThat(count.sql).startsWith("SELECT COUNT(*) FROM media m")
        assertThat(count.sql).doesNotContain("ORDER BY")
        assertThat(count.sql).contains("media_fts MATCH")
        assertThat(count.args).isEqualTo(SearchSql.build(query, noSubtrees).args)
    }

    // --- FTS expression ---------------------------------------------------------------

    @Test
    fun `search text becomes prefix terms so results appear while typing`() {
        assertThat(SearchSql.matchExpression("kyo")).isEqualTo("kyo*")
        assertThat(SearchSql.matchExpression("kyoto temple")).isEqualTo("kyoto* temple*")
    }

    @Test
    fun `FTS syntax characters are stripped instead of being executed`() {
        // Passed through raw, each of these is either a syntax error or a query the user
        // did not ask for.
        assertThat(SearchSql.matchExpression("\"best\"")).isEqualTo("best*")
        assertThat(SearchSql.matchExpression("a OR b")).isEqualTo("a* OR* b*")
        assertThat(SearchSql.matchExpression("-kyoto")).isEqualTo("kyoto*")
        assertThat(SearchSql.matchExpression("tag:travel")).isEqualTo("tag* travel*")
        assertThat(SearchSql.matchExpression("^start")).isEqualTo("start*")
    }

    @Test
    fun `non-latin text survives tokenisation`() {
        // Splitting on "not a letter or digit" keeps CJK intact, since those are letters.
        assertThat(SearchSql.matchExpression("京都")).isEqualTo("京都*")
    }

    @Test
    fun `blank or punctuation-only text produces no clause at all`() {
        assertThat(SearchSql.matchExpression("")).isNull()
        assertThat(SearchSql.matchExpression("   ")).isNull()
        assertThat(SearchSql.matchExpression("!!! ???")).isNull()

        // ...and the builder then leaves the FTS join out entirely rather than matching
        // everything or nothing by accident.
        assertThat(SearchSql.build(SearchQuery(text = "   "), noSubtrees).sql)
            .doesNotContain("media_fts")
    }

    // --- Serialisation ----------------------------------------------------------------

    @Test
    fun `a query round-trips through json`() {
        val query = SearchQuery(
            text = "kyoto",
            allTags = listOf(1, 2),
            noneTags = listOf(3),
            mediaType = MediaTypeFilter.Videos,
            bucketIds = listOf(7),
            takenFrom = 1_000,
            takenTo = 2_000,
            untaggedOnly = true,
            sort = SortOrder.Largest,
        )
        assertThat(SearchQuery.decode(query.encode())).isEqualTo(query)
    }

    @Test
    fun `a saved search written by a future build degrades instead of crashing`() {
        // Unknown keys are ignored, and anything unparseable falls back to "everything" —
        // a saved search must never be able to break the screen that lists them.
        val fromTheFuture = """{"text":"kyoto","somethingNew":42}"""
        assertThat(SearchQuery.decode(fromTheFuture).text).isEqualTo("kyoto")
        assertThat(SearchQuery.decode("not json at all")).isEqualTo(SearchQuery())
    }

    @Test
    fun `isEmpty knows the difference between no filter and a filter`() {
        assertThat(SearchQuery().isEmpty).isTrue()
        assertThat(SearchQuery(text = "a").isEmpty).isFalse()
        assertThat(SearchQuery(untaggedOnly = true).isEmpty).isFalse()
        assertThat(SearchQuery(sort = SortOrder.Largest).isEmpty).isTrue() // sorting is not a filter
    }

    // --- Filter sheet cycling ---------------------------------------------------------

    @Test
    fun `tapping a tag cycles off, include, exclude and back`() {
        var query = SearchQuery()
        assertThat(query.stateOf(5)).isEqualTo(TagFilterState.Off)

        query = query.cycleTag(5)
        assertThat(query.stateOf(5)).isEqualTo(TagFilterState.Include)

        query = query.cycleTag(5)
        assertThat(query.stateOf(5)).isEqualTo(TagFilterState.Exclude)
        // Required and excluded are mutually exclusive; a tag in both can never match.
        assertThat(query.allTags).doesNotContain(5L)

        query = query.cycleTag(5)
        assertThat(query.stateOf(5)).isEqualTo(TagFilterState.Off)
        assertThat(query.noneTags).isEmpty()
    }

    @Test
    fun `the date range label reads naturally at every combination`() {
        val day = 24L * 60 * 60 * 1000
        assertThat(SearchQuery().dateRangeLabel()).isEqualTo("Any date")
        assertThat(SearchQuery(takenFrom = day).dateRangeLabel()).startsWith("From ")
        assertThat(SearchQuery(takenTo = day).dateRangeLabel()).startsWith("Until ")
        // A single-day range should read as one date, not "2 Jan – 2 Jan".
        assertThat(SearchQuery(takenFrom = day, takenTo = day + day - 1).dateRangeLabel())
            .doesNotContain("–")
    }
}

package com.galleryorganizer.domain.search

/** A parameterised SQL statement — never string-interpolated user input. */
data class SqlStatement(val sql: String, val args: List<Any>)

/**
 * Turns a [SearchQuery] into SQL against `media`.
 *
 * Two rules run through all of this:
 *
 * - **Everything the user typed is a bound argument.** Nothing is interpolated into the
 *   statement, so a filename with a quote in it cannot break the query.
 * - **Tag conditions are `EXISTS` subqueries, not joins.** A join to `media_tag` would
 *   multiply rows per matching tag and force a `DISTINCT` over the whole result — which,
 *   at 150k items, means a temp B-tree instead of an index scan.
 */
object SearchSql {

    /**
     * @param subtreeIds resolves a tag id to itself plus its descendants when
     *   [SearchQuery.expandSubtrees] is on. Resolved by the caller (it needs the tag
     *   table) so this stays a pure function.
     */
    fun build(query: SearchQuery, subtreeIds: (Long) -> List<Long>): SqlStatement {
        val where = ArrayList<String>()
        val args = ArrayList<Any>()

        if (!query.includeMissing) where += "m.is_missing = 0"

        when (query.mediaType) {
            MediaTypeFilter.Photos -> where += "m.is_video = 0"
            MediaTypeFilter.Videos -> where += "m.is_video = 1"
            MediaTypeFilter.Any -> Unit
        }

        if (query.bucketIds.isNotEmpty()) {
            where += "m.bucket_id IN (${placeholders(query.bucketIds.size)})"
            args.addAll(query.bucketIds)
        }

        // Hidden folders are dropped when the user has not explicitly asked for a folder;
        // asking for one is a stronger signal than a standing "hide this" preference.
        if (query.excludedBucketIds.isNotEmpty() && query.bucketIds.isEmpty()) {
            where += "m.bucket_id NOT IN (${placeholders(query.excludedBucketIds.size)})"
            args.addAll(query.excludedBucketIds)
        }

        query.takenFrom?.let {
            where += "m.date_taken >= ?"
            args += it
        }
        query.takenTo?.let {
            where += "m.date_taken <= ?"
            args += it
        }

        matchExpression(query.text)?.let { match ->
            where += "m.id IN (SELECT rowid FROM media_fts WHERE media_fts MATCH ?)"
            args += match
        }

        // AND: one EXISTS per required tag, so an item must satisfy each independently.
        for (tagId in query.allTags) {
            val ids = query.resolve(tagId, subtreeIds)
            where += "EXISTS (SELECT 1 FROM media_tag mt WHERE mt.media_id = m.id " +
                "AND mt.tag_id IN (${placeholders(ids.size)}))"
            args.addAll(ids)
        }

        // OR: a single EXISTS over the union.
        if (query.anyTags.isNotEmpty()) {
            val ids = query.anyTags.flatMap { query.resolve(it, subtreeIds) }.distinct()
            where += "EXISTS (SELECT 1 FROM media_tag mt WHERE mt.media_id = m.id " +
                "AND mt.tag_id IN (${placeholders(ids.size)}))"
            args.addAll(ids)
        }

        // NOT: a single NOT EXISTS over the union.
        if (query.noneTags.isNotEmpty()) {
            val ids = query.noneTags.flatMap { query.resolve(it, subtreeIds) }.distinct()
            where += "NOT EXISTS (SELECT 1 FROM media_tag mt WHERE mt.media_id = m.id " +
                "AND mt.tag_id IN (${placeholders(ids.size)}))"
            args.addAll(ids)
        }

        if (query.untaggedOnly) {
            where += "NOT EXISTS (SELECT 1 FROM media_tag mt WHERE mt.media_id = m.id)"
        }

        val sql = buildString {
            append("SELECT m.* FROM media m")
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where.joinToString(" AND "))
            }
            append(" ORDER BY ")
            append(query.sort.orderBy)
        }
        return SqlStatement(sql, args)
    }

    /** The same filter, counted. */
    fun buildCount(query: SearchQuery, subtreeIds: (Long) -> List<Long>): SqlStatement {
        val statement = build(query, subtreeIds)
        return SqlStatement("SELECT COUNT(*) FROM media m${bodyOf(statement)}", statement.args)
    }

    /**
     * Just the `date_taken` of every matching item, in the grid's own order.
     *
     * This is what the date slider runs on. The obvious shape — `GROUP BY` the month in SQL
     * — was measured at over half a second for 150,000 rows, because `strftime(...,
     * 'localtime')` runs per row and consults the time zone database each time, and the
     * grouped expression cannot use an index so SQLite sorts the whole thing in a temp
     * B-tree. Reading the bare timestamps instead is a covering-index scan of the index the
     * grid already relies on, and folding them into months in Kotlin costs one boundary
     * calculation per month rather than per row. Same answer, roughly seven times faster.
     *
     * See `foldMonths`.
     */
    fun buildDates(query: SearchQuery, subtreeIds: (Long) -> List<Long>): SqlStatement {
        val statement = build(query, subtreeIds)
        val sql = "SELECT m.date_taken FROM media m${bodyOf(statement)} ORDER BY ${query.sort.orderBy}"
        return SqlStatement(sql, statement.args)
    }

    /** The `WHERE ...` tail of a built statement, without its `SELECT` or `ORDER BY`. */
    private fun bodyOf(statement: SqlStatement): String =
        statement.sql.substringAfter("SELECT m.* FROM media m").substringBefore(" ORDER BY ")

    private fun SearchQuery.resolve(tagId: Long, subtreeIds: (Long) -> List<Long>): List<Long> =
        if (expandSubtrees) subtreeIds(tagId).ifEmpty { listOf(tagId) } else listOf(tagId)

    private fun placeholders(count: Int): String =
        if (count == 0) "NULL" else List(count) { "?" }.joinToString(", ")

    /**
     * Turns what the user typed into an FTS4 MATCH expression.
     *
     * User text is never passed to MATCH as-is: FTS4 treats `"`, `*`, `-`, `^`, `:` and
     * `NEAR`/`OR`/`AND` as syntax, so a search for `photo "best"` would be a syntax error
     * rather than a search. Instead the input is tokenised on anything that is not a letter
     * or digit — which keeps CJK text working, since those are letters — and each token is
     * turned into a prefix term. Prefix matching is what makes search feel live while
     * typing: "kyo" should already find "Kyoto".
     *
     * @return null when there is nothing searchable, so the caller can skip the clause
     *   rather than matching everything or nothing by accident.
     */
    fun matchExpression(text: String): String? {
        val tokens = text
            .split { !it.isLetterOrDigit() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // Space between terms is AND in FTS4's default syntax, which is what people expect
        // from a search box.
        return tokens.joinToString(" ") { "$it*" }
    }

    private inline fun String.split(isDelimiter: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (char in this) {
            if (isDelimiter(char)) {
                if (current.isNotEmpty()) {
                    out += current.toString()
                    current.clear()
                }
            } else {
                current.append(char)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private val SortOrder.orderBy: String
        get() = when (this) {
            // id breaks ties so paging stays stable when many items share a timestamp.
            SortOrder.NewestFirst -> "m.date_taken DESC, m.id DESC"
            SortOrder.OldestFirst -> "m.date_taken ASC, m.id ASC"
            SortOrder.RecentlyAdded -> "m.date_first_indexed DESC, m.id DESC"
            SortOrder.Largest -> "m.size DESC, m.id DESC"
        }
}

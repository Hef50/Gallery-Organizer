package com.galleryorganizer.data.repo

import androidx.paging.PagingSource
import androidx.sqlite.db.SimpleSQLiteQuery
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.db.entity.SavedSearchEntity
import com.galleryorganizer.domain.search.SearchQuery
import com.galleryorganizer.domain.search.SearchSql
import com.galleryorganizer.domain.search.SqlStatement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A saved search with its query already parsed. */
data class SavedSearch(
    val id: Long,
    val name: String,
    val query: SearchQuery,
    val pinned: Boolean,
)

class SearchRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mediaDao get() = db.mediaDao()
    private val savedDao get() = db.savedSearchDao()

    /**
     * Subtree expansion needs the tag table, so it is resolved here and handed to the
     * pure SQL builder. Cached per call: a query with several tag filters would otherwise
     * run the recursive CTE once per filter per page.
     */
    private suspend fun subtreeResolver(query: SearchQuery): (Long) -> List<Long> {
        if (!query.expandSubtrees) return { listOf(it) }
        val needed = (query.allTags + query.anyTags + query.noneTags).distinct()
        val cache = needed.associateWith { db.tagDao().subtreeIds(it) }
        return { tagId -> cache[tagId] ?: listOf(tagId) }
    }

    /** The SQL for a query, with subtrees already resolved. */
    suspend fun statementFor(query: SearchQuery): SqlStatement =
        SearchSql.build(query, subtreeResolver(query))

    suspend fun pagingSource(query: SearchQuery): PagingSource<Int, MediaEntity> {
        val statement = statementFor(query)
        return mediaDao.pagingSourceRaw(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))
    }

    suspend fun observeCount(query: SearchQuery): Flow<Int> {
        val statement = SearchSql.buildCount(query, subtreeResolver(query))
        return mediaDao.observeCountRaw(
            SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()),
        )
    }

    /**
     * Every matching id, for "select all these results".
     *
     * Bounded on purpose: selecting 150k items would produce a `Set<Long>` the selection
     * bar cannot do anything sensible with, and a bulk tag of that size deserves to be an
     * explicit decision rather than an accidental one.
     */
    suspend fun idsMatching(query: SearchQuery, limit: Int = SELECT_ALL_LIMIT): List<Long> {
        val statement = SearchSql.build(query, subtreeResolver(query))
        return mediaDao.idsForRaw(
            SimpleSQLiteQuery(
                statement.sql.replace("SELECT m.* FROM media m", "SELECT m.id FROM media m") +
                    " LIMIT $limit",
                statement.args.toTypedArray(),
            ),
        )
    }

    // --- Saved searches ---------------------------------------------------------------

    fun observeSaved(): Flow<List<SavedSearch>> = savedDao.observeAll().map { rows ->
        rows.map { it.toSavedSearch() }
    }

    suspend fun save(name: String, query: SearchQuery): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A saved search needs a name" }
        val timestamp = now()
        return savedDao.insert(
            SavedSearchEntity(
                name = clean,
                queryJson = query.encode(),
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
    }

    suspend fun update(id: Long, name: String? = null, query: SearchQuery? = null) {
        val existing = savedDao.byId(id) ?: return
        savedDao.update(
            existing.copy(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
                queryJson = query?.encode() ?: existing.queryJson,
                updatedAt = now(),
            ),
        )
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        val existing = savedDao.byId(id) ?: return
        savedDao.update(existing.copy(pinned = pinned, updatedAt = now()))
    }

    suspend fun delete(id: Long) = savedDao.deleteById(id)

    suspend fun byId(id: Long): SavedSearch? = savedDao.byId(id)?.toSavedSearch()

    suspend fun all(): List<SavedSearch> = savedDao.all().map { it.toSavedSearch() }

    private fun SavedSearchEntity.toSavedSearch() = SavedSearch(
        id = id,
        name = name,
        query = SearchQuery.decode(queryJson),
        pinned = pinned,
    )

    companion object {
        const val SELECT_ALL_LIMIT = 10_000
    }
}

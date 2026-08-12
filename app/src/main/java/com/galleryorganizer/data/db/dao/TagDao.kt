package com.galleryorganizer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.galleryorganizer.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** A tag plus how many items currently carry it. */
data class TagWithCount(
    val id: Long,
    val name: String,
    @androidx.room.ColumnInfo(name = "parent_id") val parentId: Long,
    val color: Int?,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
)

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun byId(id: Long): TagEntity?

    @Query("SELECT * FROM tag WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TagEntity>

    /** `name` is declared `COLLATE NOCASE`, so this match is case-insensitive. */
    @Query("SELECT * FROM tag WHERE parent_id = :parentId AND name = :name LIMIT 1")
    suspend fun byNameUnder(parentId: Long, name: String): TagEntity?

    @Query("SELECT * FROM tag ORDER BY parent_id, name COLLATE NOCASE")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM tag ORDER BY parent_id, name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE parent_id = :parentId ORDER BY name COLLATE NOCASE")
    suspend fun children(parentId: Long): List<TagEntity>

    /**
     * Tag list with live item counts, for the tag manager. The counts come from a grouped
     * subquery rather than a correlated one so this stays a single table scan of
     * `media_tag` even with a few thousand tags.
     */
    @Query(
        """
        SELECT t.id, t.name, t.parent_id, t.color, COALESCE(c.n, 0) AS item_count
        FROM tag t
        LEFT JOIN (
            SELECT mt.tag_id AS tag_id, COUNT(*) AS n
            FROM media_tag mt JOIN media m ON m.id = mt.media_id
            WHERE m.is_missing = 0
            GROUP BY mt.tag_id
        ) c ON c.tag_id = t.id
        ORDER BY t.parent_id, t.name COLLATE NOCASE
        """,
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    /** Most recently applied tags, for the top row of the bulk tag sheet. */
    @Query("SELECT * FROM tag WHERE last_used_at > 0 ORDER BY last_used_at DESC LIMIT :limit")
    fun observeRecentlyUsed(limit: Int): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag ORDER BY usage_count DESC, name COLLATE NOCASE LIMIT :limit")
    fun observeMostUsed(limit: Int): Flow<List<TagEntity>>

    @Query(
        "UPDATE tag SET last_used_at = :now, usage_count = usage_count + :delta WHERE id IN (:ids)",
    )
    suspend fun touchUsage(ids: List<Long>, now: Long, delta: Int)

    /**
     * Every id in the subtree rooted at [rootId], including [rootId] itself.
     *
     * Search expands a parent tag to its descendants (see OPEN_QUESTIONS.md #4) and the
     * write path never does, so this is deliberately a query rather than something baked
     * into the schema.
     */
    @Query(
        """
        WITH RECURSIVE subtree(id) AS (
            SELECT id FROM tag WHERE id = :rootId
            UNION ALL
            SELECT t.id FROM tag t JOIN subtree s ON t.parent_id = s.id
        )
        SELECT id FROM subtree
        """,
    )
    suspend fun subtreeIds(rootId: Long): List<Long>

    /** Ancestor chain of [tagId], root first, including [tagId] — used to render paths. */
    @Query(
        """
        WITH RECURSIVE chain(id, name, parent_id, depth) AS (
            SELECT id, name, parent_id, 0 FROM tag WHERE id = :tagId
            UNION ALL
            SELECT t.id, t.name, t.parent_id, chain.depth + 1
            FROM tag t JOIN chain ON t.id = chain.parent_id
        )
        SELECT id, name, parent_id, depth FROM chain ORDER BY depth DESC
        """,
    )
    suspend fun ancestorChain(tagId: Long): List<TagPathNode>

    /**
     * Deletes a tag and everything under it. Written out rather than leaning on a foreign
     * key because `parent_id` intentionally has no FK constraint (root tags use 0, not
     * NULL, so the uniqueness index works). `media_tag` rows go away via *its* cascade.
     */
    @Transaction
    suspend fun deleteSubtree(rootId: Long) {
        val ids = subtreeIds(rootId)
        if (ids.isNotEmpty()) deleteByIds(ids)
    }

    @Query("DELETE FROM tag WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM tag")
    suspend fun count(): Int
}

data class TagPathNode(
    val id: Long,
    val name: String,
    @androidx.room.ColumnInfo(name = "parent_id") val parentId: Long,
    val depth: Int,
)

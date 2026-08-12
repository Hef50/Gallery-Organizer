package com.galleryorganizer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.galleryorganizer.data.db.entity.MediaTagCrossRef
import com.galleryorganizer.data.db.entity.TagEntity
import com.galleryorganizer.data.db.entity.TagSource
import kotlinx.coroutines.flow.Flow

/** One row of "which of the selected items carry this tag". */
data class TagCoverage(
    @androidx.room.ColumnInfo(name = "tag_id") val tagId: Long,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
)

@Dao
interface MediaTagDao {

    /**
     * IGNORE, not REPLACE: if an item already carries a tag manually, a later automatic
     * pass must not silently downgrade `source` to `auto`. Promotions are explicit.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(rows: List<MediaTagCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<MediaTagCrossRef>)

    @Query("DELETE FROM media_tag WHERE tag_id = :tagId AND media_id IN (:mediaIds)")
    suspend fun removeTagFrom(tagId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM media_tag WHERE media_id IN (:mediaIds)")
    suspend fun clearTagsFor(mediaIds: List<Long>)

    @Query("SELECT * FROM media_tag WHERE media_id = :mediaId")
    suspend fun rowsFor(mediaId: Long): List<MediaTagCrossRef>

    @Query("SELECT * FROM media_tag")
    suspend fun allRows(): List<MediaTagCrossRef>

    @Query(
        """
        SELECT t.* FROM tag t
        JOIN media_tag mt ON mt.tag_id = t.id
        WHERE mt.media_id = :mediaId
        ORDER BY t.name COLLATE NOCASE
        """,
    )
    fun observeTagsFor(mediaId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tag t
        JOIN media_tag mt ON mt.tag_id = t.id
        WHERE mt.media_id = :mediaId
        ORDER BY t.name COLLATE NOCASE
        """,
    )
    suspend fun tagsFor(mediaId: Long): List<TagEntity>

    /** Tag names for a set of items, used to rebuild their FTS rows. */
    @Query(
        """
        SELECT mt.media_id AS mediaId, t.name AS name FROM media_tag mt
        JOIN tag t ON t.id = mt.tag_id
        WHERE mt.media_id IN (:mediaIds)
        """,
    )
    suspend fun tagNamesFor(mediaIds: List<Long>): List<MediaTagName>

    /**
     * How many of [mediaIds] already carry each tag. Drives the tri-state checkboxes in
     * the bulk tag sheet: 0 = unchecked, [mediaIds].size = checked, anything between =
     * indeterminate.
     */
    @Query(
        """
        SELECT tag_id, COUNT(*) AS item_count FROM media_tag
        WHERE media_id IN (:mediaIds) GROUP BY tag_id
        """,
    )
    suspend fun coverageFor(mediaIds: List<Long>): List<TagCoverage>

    @Query("SELECT COUNT(*) FROM media_tag WHERE tag_id = :tagId")
    suspend fun itemCountFor(tagId: Long): Int

    @Query("SELECT COUNT(*) FROM media_tag")
    suspend fun count(): Int

    /** Promote automatic tags on an item to manual, so nothing later overwrites them. */
    @Query("UPDATE media_tag SET source = :to WHERE media_id IN (:mediaIds) AND source = :from")
    suspend fun changeSource(mediaIds: List<Long>, from: TagSource, to: TagSource)
}

data class MediaTagName(val mediaId: Long, val name: String)

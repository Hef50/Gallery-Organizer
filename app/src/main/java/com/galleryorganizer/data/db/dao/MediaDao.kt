package com.galleryorganizer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.galleryorganizer.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * A narrow projection of the columns the indexer needs to decide whether a row has
 * changed. Selecting whole [MediaEntity] rows for 150k items just to compare a timestamp
 * would be a lot of wasted allocation.
 */
data class MediaIndexRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "mediastore_id") val mediaStoreId: Long,
    @androidx.room.ColumnInfo(name = "date_modified") val dateModified: Long,
    @androidx.room.ColumnInfo(name = "content_hash") val contentHash: String?,
    @androidx.room.ColumnInfo(name = "date_first_indexed") val dateFirstIndexed: Long,
    @androidx.room.ColumnInfo(name = "is_missing") val isMissing: Boolean,
)

/** Just enough to open and hash a file. */
data class MediaHashTarget(
    val id: Long,
    val uri: String,
    val size: Long,
    @androidx.room.ColumnInfo(name = "date_taken") val dateTaken: Long,
)

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: MediaEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<MediaEntity>): List<Long>

    @Update
    suspend fun updateAll(items: List<MediaEntity>)

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT * FROM media WHERE content_hash = :hash LIMIT 1")
    suspend fun byContentHash(hash: String): MediaEntity?

    @Query(
        """
        SELECT id, mediastore_id, date_modified, content_hash, date_first_indexed, is_missing
        FROM media WHERE mediastore_id IN (:mediaStoreIds)
        """,
    )
    suspend fun indexRowsFor(mediaStoreIds: List<Long>): List<MediaIndexRow>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM media WHERE is_missing = 0")
    fun observePresentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media WHERE content_hash IS NULL AND is_missing = 0")
    suspend fun unhashedCount(): Int

    /**
     * Hash backfill targets, oldest-first so the most recent (and most likely to be
     * tagged next) items are hashed last — the tag write path hashes on demand anyway.
     */
    @Query(
        """
        SELECT id, uri, size, date_taken FROM media
        WHERE content_hash IS NULL AND is_missing = 0
        ORDER BY id ASC LIMIT :limit
        """,
    )
    suspend fun unhashedTargets(limit: Int): List<MediaHashTarget>

    @Query("SELECT id, uri, size, date_taken FROM media WHERE id IN (:ids) AND content_hash IS NULL")
    suspend fun unhashedTargetsByIds(ids: List<Long>): List<MediaHashTarget>

    @Query("UPDATE media SET content_hash = :hash WHERE id = :id")
    suspend fun setContentHash(id: Long, hash: String)

    @Query("UPDATE media SET ocr_text = :text WHERE id = :id")
    suspend fun setOcrText(id: Long, text: String?)

    /**
     * Rows the indexer did not see in this pass. Never deletes — see DECISIONS.md.
     * Scoped by `date_modified` so a partial (resumed) pass cannot flag the whole library.
     */
    @Query(
        """
        UPDATE media SET is_missing = 1
        WHERE is_missing = 0 AND mediastore_id NOT IN (:seenMediaStoreIds)
          AND date_modified <= :throughDateModified
        """,
    )
    suspend fun markMissingOutside(seenMediaStoreIds: List<Long>, throughDateModified: Long): Int

    @Query("UPDATE media SET is_missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<Long>)

    @Query("UPDATE media SET is_missing = 0 WHERE id IN (:ids)")
    suspend fun markPresent(ids: List<Long>)

    @Query("SELECT * FROM media WHERE is_missing = 1 ORDER BY date_taken DESC")
    suspend fun missingItems(): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE is_missing = 1")
    fun observeMissingCount(): Flow<Int>

    /**
     * Hard delete. Only ever called from the explicit "forget missing items" sweep in
     * Settings, behind a confirm dialog. Deletes the database row, never the file.
     */
    @Query("DELETE FROM media WHERE is_missing = 1")
    suspend fun forgetAllMissing(): Int

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteRow(id: Long)

    // --- Duplicate finder (P10) -------------------------------------------------------

    @Query(
        """
        SELECT content_hash FROM media
        WHERE content_hash IS NOT NULL AND is_missing = 0
        GROUP BY content_hash HAVING COUNT(*) > 1
        ORDER BY COUNT(*) DESC
        """,
    )
    suspend fun duplicateHashes(): List<String>

    @Query("SELECT * FROM media WHERE content_hash = :hash AND is_missing = 0 ORDER BY date_added ASC")
    suspend fun byContentHashAll(hash: String): List<MediaEntity>

    // --- Folders (P6/P10) -------------------------------------------------------------

    @Query(
        """
        SELECT bucket_id AS bucketId, bucket_name AS bucketName, COUNT(*) AS itemCount
        FROM media WHERE is_missing = 0
        GROUP BY bucket_id, bucket_name ORDER BY itemCount DESC
        """,
    )
    fun observeBuckets(): Flow<List<BucketSummary>>
}

data class BucketSummary(
    val bucketId: Long,
    val bucketName: String,
    val itemCount: Int,
)

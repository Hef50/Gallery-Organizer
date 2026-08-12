package com.galleryorganizer.data.db.dao

import androidx.paging.PagingSource
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

/** Just enough for the presence sweep to decide whether a row is still backed by a file. */
data class MediaPresenceRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "mediastore_id") val mediaStoreId: Long,
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

    /**
     * The grid, newest first. A Room [androidx.paging.PagingSource] so the UI never holds
     * more than a few screens of rows — at 150k items a `List<MediaEntity>` would be tens
     * of megabytes and several seconds of query time.
     *
     * `date_taken DESC, id DESC` matches `index_media_date_taken`; `id` only breaks ties
     * so that paging is stable when a hundred photos share a timestamp.
     */
    @Query(
        """
        SELECT * FROM media
        WHERE (is_missing = 0 OR :includeMissing)
        ORDER BY date_taken DESC, id DESC
        """,
    )
    fun pagingSourceAll(includeMissing: Boolean): PagingSource<Int, MediaEntity>

    /** Ids in grid order, for "select all" over a bounded result. */
    @Query(
        """
        SELECT id FROM media WHERE is_missing = 0
        ORDER BY date_taken DESC, id DESC LIMIT :limit
        """,
    )
    suspend fun allIdsInGridOrder(limit: Int): List<Long>

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
     * Refreshes only the columns MediaStore owns.
     *
     * Deliberately not `@Update` on the whole entity: `content_hash`, `date_first_indexed`
     * and `ocr_text` belong to the app, and a whole-row update is one forgotten field away
     * from a rescan quietly wiping the OCR text or the identity hash of every item.
     */
    @Query(
        """
        UPDATE media SET
            mediastore_id = :mediaStoreId, uri = :uri, display_name = :displayName,
            relative_path = :relativePath, bucket_id = :bucketId, bucket_name = :bucketName,
            mime = :mime, is_video = :isVideo, size = :size, date_taken = :dateTaken,
            date_modified = :dateModified, date_added = :dateAdded, duration = :duration,
            width = :width, height = :height, orientation = :orientation, is_missing = 0
        WHERE id = :id
        """,
    )
    suspend fun updateFromMediaStore(
        id: Long,
        mediaStoreId: Long,
        uri: String,
        displayName: String,
        relativePath: String,
        bucketId: Long,
        bucketName: String,
        mime: String,
        isVideo: Boolean,
        size: Long,
        dateTaken: Long,
        dateModified: Long,
        dateAdded: Long,
        duration: Long,
        width: Int,
        height: Int,
        orientation: Int,
    )

    /** Paged presence projection for the indexer's missing-file sweep. */
    @Query("SELECT id, mediastore_id, is_missing FROM media ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun presencePage(limit: Int, offset: Int): List<MediaPresenceRow>

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

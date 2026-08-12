package com.galleryorganizer.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.galleryorganizer.data.db.entity.AlbumEntity
import com.galleryorganizer.data.db.entity.AlbumMediaCrossRef
import com.galleryorganizer.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/** An album plus what the shelf needs to draw it, without loading its contents. */
data class AlbumSummary(
    val id: Long,
    val name: String,
    val description: String,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
    /** The chosen cover, or the first photo in the album when none was chosen. */
    @androidx.room.ColumnInfo(name = "cover_uri") val coverUri: String?,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(album: AlbumEntity): Long

    @Update
    suspend fun update(album: AlbumEntity)

    @Delete
    suspend fun delete(album: AlbumEntity)

    @Query("DELETE FROM album WHERE id = :albumId")
    suspend fun deleteById(albumId: Long)

    @Query("SELECT * FROM album WHERE id = :albumId")
    suspend fun byId(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM album WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AlbumEntity?

    /**
     * The album shelf.
     *
     * The cover resolves in SQL — explicit cover if it is still present, otherwise the
     * first member by position — so the UI never has to make a second query per album, and
     * an album whose cover has gone missing still shows a picture rather than a grey box.
     */
    @Query(
        """
        SELECT a.id, a.name, a.description, a.updated_at,
               COALESCE(c.n, 0) AS item_count,
               COALESCE(
                   (SELECT m.uri FROM media m WHERE m.id = a.cover_media_id AND m.is_missing = 0),
                   (SELECT m2.uri FROM album_media am2
                        JOIN media m2 ON m2.id = am2.media_id
                        WHERE am2.album_id = a.id AND m2.is_missing = 0
                        ORDER BY am2.position LIMIT 1)
               ) AS cover_uri
        FROM album a
        LEFT JOIN (
            SELECT am.album_id AS album_id, COUNT(*) AS n
            FROM album_media am JOIN media m ON m.id = am.media_id
            WHERE m.is_missing = 0
            GROUP BY am.album_id
        ) c ON c.album_id = a.id
        ORDER BY a.sort_order, a.updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<AlbumSummary>>

    @Query("SELECT COUNT(*) FROM album")
    suspend fun count(): Int

    @Query("SELECT * FROM album ORDER BY id")
    suspend fun allAlbums(): List<AlbumEntity>

    /** Membership rows for a page of items, so export can ride album data on each item. */
    @Query("SELECT * FROM album_media WHERE media_id IN (:mediaIds)")
    suspend fun membershipsForMany(mediaIds: List<Long>): List<AlbumMediaCrossRef>

    // --- Membership ---------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(rows: List<AlbumMediaCrossRef>): List<Long>

    @Query("DELETE FROM album_media WHERE album_id = :albumId AND media_id IN (:mediaIds)")
    suspend fun removeFrom(albumId: Long, mediaIds: List<Long>)

    @Query("SELECT COALESCE(MAX(position), 0) FROM album_media WHERE album_id = :albumId")
    suspend fun lastPosition(albumId: Long): Long

    @Query("SELECT media_id FROM album_media WHERE album_id = :albumId ORDER BY position")
    suspend fun mediaIdsIn(albumId: Long): List<Long>

    @Query("SELECT album_id FROM album_media WHERE media_id = :mediaId")
    suspend fun albumsContaining(mediaId: Long): List<Long>

    /** Which of a selection is already in each album, for the tri-state "add to album" list. */
    @Query(
        """
        SELECT album_id AS albumId, COUNT(*) AS itemCount FROM album_media
        WHERE media_id IN (:mediaIds) GROUP BY album_id
        """,
    )
    suspend fun coverageFor(mediaIds: List<Long>): List<AlbumCoverage>

    @Query("UPDATE album_media SET position = :position WHERE album_id = :albumId AND media_id = :mediaId")
    suspend fun setPosition(albumId: Long, mediaId: Long, position: Long)

    @Query("SELECT media_id AS mediaId, position FROM album_media WHERE album_id = :albumId ORDER BY position")
    suspend fun positionsIn(albumId: Long): List<AlbumPosition>

    /**
     * An album's contents, in the user's order. Paged like everything else — an album can
     * hold as many photos as the user likes.
     */
    @Transaction
    @Query(
        """
        SELECT m.* FROM media m
        JOIN album_media am ON am.media_id = m.id
        WHERE am.album_id = :albumId AND m.is_missing = 0
        ORDER BY am.position
        """,
    )
    fun pagingSourceFor(albumId: Long): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT m.* FROM media m
        JOIN album_media am ON am.media_id = m.id
        WHERE am.album_id = :albumId AND m.is_missing = 0
        ORDER BY am.position LIMIT :limit
        """,
    )
    suspend fun previewFor(albumId: Long, limit: Int): List<MediaEntity>
}

data class AlbumCoverage(val albumId: Long, val itemCount: Int)

data class AlbumPosition(val mediaId: Long, val position: Long)

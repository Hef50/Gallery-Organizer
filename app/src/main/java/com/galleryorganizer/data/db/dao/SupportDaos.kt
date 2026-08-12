package com.galleryorganizer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.galleryorganizer.data.db.entity.IndexStateEntity
import com.galleryorganizer.data.db.entity.MediaFtsEntity
import com.galleryorganizer.data.db.entity.SavedSearchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaFtsDao {

    /**
     * FTS4 has no UPSERT, so a rebuild is delete-then-insert. Both halves always run
     * inside the caller's transaction — see
     * [com.galleryorganizer.data.repo.FtsMaintenance].
     */
    @Query("DELETE FROM media_fts WHERE rowid IN (:mediaIds)")
    suspend fun deleteRows(mediaIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRows(rows: List<MediaFtsEntity>)

    @Query("DELETE FROM media_fts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM media_fts")
    suspend fun count(): Int

    /**
     * Ids whose text matches. Kept separate from the media query so the search layer can
     * intersect it with structured filters without one enormous join.
     */
    @Query("SELECT rowid FROM media_fts WHERE media_fts MATCH :match")
    suspend fun matchIds(match: String): List<Long>
}

@Dao
interface IndexStateDao {

    @Query("SELECT value FROM index_state WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: IndexStateEntity)

    suspend fun put(key: String, value: String) = put(IndexStateEntity(key, value))

    @Query("DELETE FROM index_state WHERE key = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM index_state")
    suspend fun all(): List<IndexStateEntity>

    @Query("SELECT value FROM index_state WHERE key = :key")
    fun observe(key: String): Flow<String?>
}

@Dao
interface SavedSearchDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(search: SavedSearchEntity): Long

    @Update
    suspend fun update(search: SavedSearchEntity)

    @Delete
    suspend fun delete(search: SavedSearchEntity)

    @Query("DELETE FROM saved_search WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_search WHERE id = :id")
    suspend fun byId(id: Long): SavedSearchEntity?

    @Query("SELECT * FROM saved_search ORDER BY pinned DESC, sort_order ASC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_search ORDER BY sort_order ASC, name COLLATE NOCASE")
    suspend fun all(): List<SavedSearchEntity>

    @Query("SELECT COUNT(*) FROM saved_search")
    suspend fun count(): Int
}

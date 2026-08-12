package com.galleryorganizer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.galleryorganizer.data.db.entity.LabelSuggestionEntity
import com.galleryorganizer.data.db.entity.SuggestionStatus
import kotlinx.coroutines.flow.Flow

/** A label and how many pending items carry it — the unit the review screen works in. */
data class SuggestionGroup(
    val label: String,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
    @androidx.room.ColumnInfo(name = "avg_confidence") val averageConfidence: Float,
)

@Dao
interface SuggestionDao {

    /**
     * IGNORE, so a re-scan cannot resurrect a label the user already rejected or quietly
     * reset one they accepted.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(rows: List<LabelSuggestionEntity>)

    /**
     * The review queue, grouped by label. Reviewing "Beach — 340 photos" as one decision is
     * the only way this is usable at all; item-by-item would be 150,000 decisions.
     */
    @Query(
        """
        SELECT label, COUNT(*) AS item_count, AVG(confidence) AS avg_confidence
        FROM label_suggestion WHERE status = 'pending'
        GROUP BY label HAVING COUNT(*) > 0
        ORDER BY item_count DESC, label COLLATE NOCASE
        """,
    )
    fun observePendingGroups(): Flow<List<SuggestionGroup>>

    @Query("SELECT media_id FROM label_suggestion WHERE label = :label AND status = 'pending'")
    suspend fun pendingMediaFor(label: String): List<Long>

    @Query("UPDATE label_suggestion SET status = :status WHERE label = :label AND status = 'pending'")
    suspend fun setStatusForLabel(label: String, status: SuggestionStatus)

    @Query(
        """
        UPDATE label_suggestion SET status = :status
        WHERE label = :label AND media_id IN (:mediaIds)
        """,
    )
    suspend fun setStatusFor(label: String, mediaIds: List<Long>, status: SuggestionStatus)

    @Query("SELECT COUNT(*) FROM label_suggestion WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM label_suggestion")
    suspend fun count(): Int

    @Query("SELECT * FROM label_suggestion WHERE media_id = :mediaId")
    suspend fun forMedia(mediaId: Long): List<LabelSuggestionEntity>

    /** Forgets rejections, so a later scan may offer them again. */
    @Query("DELETE FROM label_suggestion WHERE status = 'rejected'")
    suspend fun clearRejected(): Int
}

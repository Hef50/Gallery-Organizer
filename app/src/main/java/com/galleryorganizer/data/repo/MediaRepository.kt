package com.galleryorganizer.data.repo

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.dao.BucketSummary
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.media.ContentHasher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Reads and identity maintenance over the media table. Tagging lives in [TagRepository]. */
class MediaRepository(
    private val db: AppDatabase,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mediaDao get() = db.mediaDao()

    fun observePresentCount(): Flow<Int> = mediaDao.observePresentCount()

    fun observeMissingCount(): Flow<Int> = mediaDao.observeMissingCount()

    fun observeBuckets(): Flow<List<BucketSummary>> = mediaDao.observeBuckets()

    suspend fun byId(id: Long): MediaEntity? = mediaDao.byId(id)

    suspend fun byIds(ids: List<Long>): List<MediaEntity> = mediaDao.byIds(ids)

    suspend fun unhashedCount(): Int = mediaDao.unhashedCount()

    /**
     * Hashes up to [limit] not-yet-hashed items. Returns how many were hashed, so the
     * backfill worker can loop until it returns 0.
     */
    suspend fun hashNextBatch(limit: Int): Int = withContext(io) {
        val targets = mediaDao.unhashedTargets(limit)
        var hashed = 0
        for (target in targets) {
            val hash = ContentHasher.hashOf(
                resolver,
                Uri.parse(target.uri),
                target.size,
                target.dateTaken,
            ) ?: continue
            mediaDao.setContentHash(target.id, hash)
            hashed++
        }
        hashed
    }

    /**
     * Hashes [mediaIds] right now if they are not hashed yet.
     *
     * Called on the first tag write for an item: that is the moment identity starts to
     * matter, because a tag is the thing that has to survive the file moving. Items whose
     * file cannot be opened are skipped silently — the tag is still written, and the
     * backfill worker will try again later.
     */
    suspend fun ensureHashed(mediaIds: List<Long>): Int = withContext(io) {
        val targets = mediaDao.unhashedTargetsByIds(mediaIds)
        if (targets.isEmpty()) return@withContext 0

        val hashes = targets.mapNotNull { target ->
            ContentHasher.hashOf(resolver, Uri.parse(target.uri), target.size, target.dateTaken)
                ?.let { target.id to it }
        }
        db.withTransaction {
            hashes.forEach { (id, hash) -> mediaDao.setContentHash(id, hash) }
        }
        hashes.size
    }

    /** Items sharing a content hash — the P10 duplicate finder. */
    suspend fun duplicateGroups(): List<List<MediaEntity>> = withContext(io) {
        mediaDao.duplicateHashes().map { mediaDao.byContentHashAll(it) }.filter { it.size > 1 }
    }

    suspend fun missingItems(): List<MediaEntity> = mediaDao.missingItems()

    /**
     * The "forget missing items" sweep from Settings. Removes database rows only — it
     * cannot and must not touch files. Behind a confirm dialog in the UI.
     */
    suspend fun forgetMissing(): Int = db.withTransaction { mediaDao.forgetAllMissing() }
}

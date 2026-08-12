package com.galleryorganizer.data.repo

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.dao.AlbumSummary
import com.galleryorganizer.data.db.entity.AlbumEntity
import com.galleryorganizer.data.db.entity.AlbumMediaCrossRef
import com.galleryorganizer.data.db.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/** Whether every, some or none of a selection is already in an album. */
enum class AlbumCheckState { None, Some, All }

/**
 * Hand-made albums: ordered, curated collections that are nobody's business but the user's.
 *
 * See [AlbumEntity] for why these are not tags. The rule from [TagRepository] holds here
 * too: one user gesture is one transaction, so adding 500 selected photos to an album
 * either all happens or none of it does.
 */
class AlbumRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val albumDao get() = db.albumDao()

    fun observeAlbums(): Flow<List<AlbumSummary>> = albumDao.observeAll()

    fun pagingSourceFor(albumId: Long): PagingSource<Int, MediaEntity> =
        albumDao.pagingSourceFor(albumId)

    suspend fun byId(albumId: Long): AlbumEntity? = albumDao.byId(albumId)

    suspend fun previewFor(albumId: Long, limit: Int = 4): List<MediaEntity> =
        albumDao.previewFor(albumId, limit)

    suspend fun mediaIdsIn(albumId: Long): List<Long> = albumDao.mediaIdsIn(albumId)

    /**
     * Creates the album, or returns the existing one with that name.
     *
     * Idempotent for the same reason `ensureTag` is: "create an album and put these photos
     * in it" is one gesture from the user's side, and it must not fail halfway because the
     * name was already taken.
     */
    suspend fun ensureAlbum(name: String, description: String = ""): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An album needs a name" }
        val timestamp = now()
        return db.withTransaction {
            albumDao.byName(clean)?.id ?: albumDao.insert(
                AlbumEntity(
                    name = clean,
                    description = description.trim(),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
        }
    }

    suspend fun rename(albumId: Long, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "An album needs a name" }
        db.withTransaction {
            val album = albumDao.byId(albumId) ?: return@withTransaction
            albumDao.update(album.copy(name = clean, updatedAt = now()))
        }
    }

    suspend fun setDescription(albumId: Long, description: String) = db.withTransaction {
        val album = albumDao.byId(albumId) ?: return@withTransaction
        albumDao.update(album.copy(description = description.trim(), updatedAt = now()))
    }

    suspend fun setCover(albumId: Long, mediaId: Long?) = db.withTransaction {
        val album = albumDao.byId(albumId) ?: return@withTransaction
        albumDao.update(album.copy(coverMediaId = mediaId, updatedAt = now()))
    }

    /** Deletes the album. Membership goes with it via cascade; photos and tags do not. */
    suspend fun delete(albumId: Long) = db.withTransaction { albumDao.deleteById(albumId) }

    // --- Membership ---------------------------------------------------------------------

    /**
     * Appends [mediaIds] to the end of the album, in the order given.
     *
     * Conflicts are IGNOREd, so adding a photo that is already there is a no-op rather than
     * a duplicate row or a silent reorder — a photo appears in an album once, where the user
     * first put it.
     *
     * @return how many were actually added.
     */
    suspend fun addTo(albumId: Long, mediaIds: Collection<Long>): Int {
        if (mediaIds.isEmpty()) return 0
        val timestamp = now()
        return db.withTransaction {
            var position = albumDao.lastPosition(albumId)
            val rows = mediaIds.map { mediaId ->
                position += AlbumMediaCrossRef.POSITION_STEP
                AlbumMediaCrossRef(albumId, mediaId, position, timestamp)
            }
            val added = rows.chunked(WRITE_CHUNK)
                .sumOf { chunk -> albumDao.addAll(chunk).count { it != -1L } }
            albumDao.byId(albumId)?.let { albumDao.update(it.copy(updatedAt = timestamp)) }
            added
        }
    }

    suspend fun removeFrom(albumId: Long, mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        db.withTransaction {
            mediaIds.toList().chunked(WRITE_CHUNK).forEach { albumDao.removeFrom(albumId, it) }
            albumDao.byId(albumId)?.let { albumDao.update(it.copy(updatedAt = now())) }
        }
    }

    suspend fun setMembership(albumId: Long, mediaIds: Collection<Long>, member: Boolean): Int =
        if (member) addTo(albumId, mediaIds) else { removeFrom(albumId, mediaIds); 0 }

    /** Tri-state coverage for the "add to album" sheet, mirroring [TagRepository.coverage]. */
    suspend fun coverage(mediaIds: Collection<Long>): Map<Long, AlbumCheckState> {
        if (mediaIds.isEmpty()) return emptyMap()
        val total = mediaIds.size
        val counts = HashMap<Long, Int>()
        mediaIds.toList().chunked(WRITE_CHUNK).forEach { chunk ->
            albumDao.coverageFor(chunk).forEach { row ->
                counts[row.albumId] = (counts[row.albumId] ?: 0) + row.itemCount
            }
        }
        return counts.mapValues { (_, count) ->
            when {
                count >= total -> AlbumCheckState.All
                count > 0 -> AlbumCheckState.Some
                else -> AlbumCheckState.None
            }
        }
    }

    /**
     * Moves the item at [from] to [to] in the album's order.
     *
     * Positions are sparse, so the common case rewrites exactly one row: the moved item gets
     * the midpoint between its new neighbours. When a gap has been used up — which takes
     * about ten moves into the same slot — the whole album is renumbered at
     * [AlbumMediaCrossRef.POSITION_STEP] intervals and the move retried. Renumbering an
     * album of a few thousand rows is a single transaction and rare enough not to matter;
     * renumbering on *every* drag would be the thing that makes reordering feel slow.
     */
    suspend fun move(albumId: Long, from: Int, to: Int): Boolean = db.withTransaction {
        val ids = albumDao.mediaIdsIn(albumId)
        if (from !in ids.indices || to !in ids.indices || from == to) return@withTransaction false

        val moved = ids[from]
        val reordered = ids.toMutableList().apply { removeAt(from); add(to, moved) }
        val before = reordered.getOrNull(to - 1)
        val after = reordered.getOrNull(to + 1)

        val positions = albumDao.positionsIn(albumId).associate { it.mediaId to it.position }
        val low = before?.let { positions[it] } ?: 0L
        val high = after?.let { positions[it] } ?: (low + AlbumMediaCrossRef.POSITION_STEP * 2)

        if (high - low > 1) {
            albumDao.setPosition(albumId, moved, low + (high - low) / 2)
        } else {
            reordered.forEachIndexed { index, id ->
                albumDao.setPosition(albumId, id, (index + 1) * AlbumMediaCrossRef.POSITION_STEP)
            }
        }
        albumDao.byId(albumId)?.let { albumDao.update(it.copy(updatedAt = now())) }
        true
    }

    private companion object {
        /** Keeps `IN (...)` clauses under SQLite's bound-variable ceiling. */
        const val WRITE_CHUNK = 500
    }
}

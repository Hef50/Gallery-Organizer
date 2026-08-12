package com.galleryorganizer.data.repo

import androidx.room.withTransaction
import com.galleryorganizer.data.db.AppDatabase
import com.galleryorganizer.data.db.IndexStateKeys
import com.galleryorganizer.data.db.entity.MediaEntity
import com.galleryorganizer.data.media.MediaCollection
import com.galleryorganizer.data.media.MediaStoreItem
import com.galleryorganizer.data.media.MediaStoreSource
import com.galleryorganizer.data.media.ScanCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reported to the notification and the UI while a pass runs. */
data class IndexProgress(
    val collection: MediaCollection,
    val inserted: Int,
    val updated: Int,
    val scanned: Int,
)

data class IndexResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val scanned: Int = 0,
    val markedMissing: Int = 0,
    val restored: Int = 0,
    val completed: Boolean = false,
) {
    operator fun plus(other: IndexResult) = IndexResult(
        inserted = inserted + other.inserted,
        updated = updated + other.updated,
        scanned = scanned + other.scanned,
        markedMissing = markedMissing + other.markedMissing,
        restored = restored + other.restored,
        completed = completed && other.completed,
    )
}

/**
 * Brings the database in step with MediaStore.
 *
 * The three properties that matter at 150k items:
 *
 * - **Incremental.** Each collection carries a `(date_modified, _id)` watermark in
 *   `index_state`. A pass only ever looks at rows past it, so a launch after a quiet day
 *   reads a handful of rows, not the whole library. There is no full rescan on launch.
 * - **Resumable.** Rows and the advanced watermark are committed in the *same*
 *   transaction, every [chunkSize] items. Being killed mid-pass costs at most one chunk,
 *   and the next run picks up exactly where this one stopped — including partway through
 *   a single second's worth of timestamps, which is why the cursor carries `_id` too.
 * - **Chunked.** Nothing accumulates across the whole library except the presence
 *   sweep's id array.
 */
class MediaIndexer(
    private val source: MediaStoreSource,
    private val db: AppDatabase,
    private val fts: FtsMaintenance = FtsMaintenance(db),
    private val now: () -> Long = System::currentTimeMillis,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {

    private val mediaDao get() = db.mediaDao()
    private val indexState get() = db.indexStateDao()

    /**
     * Runs one pass over both collections and then reconciles which files are still
     * present.
     *
     * @param sweepMissing when false, the presence sweep is skipped. It costs a full
     *   id-only query of the library, so it is worth doing on a scheduled pass and not on
     *   the "something changed, catch up" pass.
     */
    suspend fun runPass(
        sweepMissing: Boolean = true,
        onProgress: suspend (IndexProgress) -> Unit = {},
    ): IndexResult {
        var result = IndexResult(completed = true)
        for (collection in MediaCollection.entries) {
            result += scanCollection(collection, onProgress)
        }
        if (sweepMissing) {
            result += reconcilePresence()
        }
        indexState.put(IndexStateKeys.LAST_FULL_PASS_AT, now().toString())
        indexState.put(IndexStateKeys.INITIAL_INDEX_COMPLETE, "1")
        return result
    }

    private suspend fun scanCollection(
        collection: MediaCollection,
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexResult {
        val key = collection.watermarkKey
        val start = ScanCursor.decode(indexState.get(key))
        var inserted = 0
        var updated = 0
        var scanned = 0

        source.scan(collection, start, chunkSize) { chunk ->
            // Cancellation is checked between chunks rather than mid-transaction: a
            // half-written chunk with an advanced watermark would skip items forever.
            currentCoroutineContext().ensureActive()

            val counts = commitChunk(chunk, key)
            inserted += counts.first
            updated += counts.second
            scanned += chunk.size
            onProgress(IndexProgress(collection, inserted, updated, scanned))
        }
        return IndexResult(inserted = inserted, updated = updated, scanned = scanned, completed = true)
    }

    /** @return inserted to updated counts. */
    private suspend fun commitChunk(chunk: List<MediaStoreItem>, watermarkKey: String): Pair<Int, Int> =
        db.withTransaction {
            val existing = mediaDao.indexRowsFor(chunk.map { it.mediaStoreId })
                .associateBy { it.mediaStoreId }

            val inserts = ArrayList<MediaEntity>(chunk.size)
            val updatedIds = ArrayList<Long>()

            for (item in chunk) {
                val known = existing[item.mediaStoreId]
                if (known == null) {
                    inserts += item.toEntity(dateFirstIndexed = now())
                    continue
                }
                // Unchanged rows are skipped entirely. On an incremental pass that is
                // usually the whole chunk, and rewriting them would churn the FTS index
                // for nothing.
                if (known.dateModified == item.dateModified && !known.isMissing) continue

                // Only MediaStore's own columns are refreshed, so content_hash,
                // date_first_indexed and ocr_text survive a rescan untouched.
                mediaDao.updateFromMediaStore(
                    id = known.id,
                    mediaStoreId = item.mediaStoreId,
                    uri = item.uri,
                    displayName = item.displayName,
                    relativePath = item.relativePath,
                    bucketId = item.bucketId,
                    bucketName = item.bucketName,
                    mime = item.mime,
                    isVideo = item.isVideo,
                    size = item.size,
                    dateTaken = item.dateTaken,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                    duration = item.duration,
                    width = item.width,
                    height = item.height,
                    orientation = item.orientation,
                )
                updatedIds += known.id
            }

            val newIds = if (inserts.isEmpty()) emptyList() else mediaDao.insertAll(inserts)

            val rebuild = newIds + updatedIds
            if (rebuild.isNotEmpty()) fts.rebuild(rebuild)

            // The watermark advances inside the same transaction as the rows it covers.
            // Split across two transactions, a crash in between would either skip items
            // or re-scan them forever.
            chunk.lastOrNull()?.let {
                indexState.put(watermarkKey, ScanCursor(it.dateModified, it.mediaStoreId).encode())
            }

            inserts.size to updatedIds.size
        }

    /**
     * Flags rows whose file MediaStore no longer reports, and un-flags ones that came
     * back. Rows are never deleted — see DECISIONS.md.
     *
     * Done as a set difference in memory rather than a giant `NOT IN (...)`: SQLite caps
     * bound variables at 999 by default, so a 150k-id `IN` clause is not expressible, and
     * chunking it would need a full table scan per chunk. A sorted `LongArray` of 150k ids
     * is about 1.2 MB, which is a fine trade for one pass.
     */
    private suspend fun reconcilePresence(): IndexResult {
        val live = HashSet<Long>()
        for (collection in MediaCollection.entries) {
            source.allIds(collection).forEach { live += it }
        }
        // An empty library is indistinguishable from a query that failed (revoked
        // permission, unmounted volume), and flagging everything missing on a fluke would
        // be alarming. Only trust emptiness if the database is empty too.
        if (live.isEmpty() && mediaDao.count() > 0) return IndexResult()

        val nowMissing = ArrayList<Long>()
        val nowPresent = ArrayList<Long>()
        var offset = 0
        while (true) {
            val page = mediaDao.presencePage(limit = PRESENCE_PAGE, offset = offset)
            if (page.isEmpty()) break
            for (row in page) {
                val isLive = row.mediaStoreId in live
                if (!isLive && !row.isMissing) nowMissing += row.id
                if (isLive && row.isMissing) nowPresent += row.id
            }
            offset += page.size
            if (page.size < PRESENCE_PAGE) break
        }

        db.withTransaction {
            nowMissing.chunked(chunkSize).forEach { mediaDao.markMissing(it) }
            nowPresent.chunked(chunkSize).forEach { mediaDao.markPresent(it) }
        }
        return IndexResult(markedMissing = nowMissing.size, restored = nowPresent.size)
    }

    /** Wipes the watermarks so the next pass re-reads everything. Tags are untouched. */
    suspend fun resetWatermarks() {
        MediaCollection.entries.forEach { indexState.remove(it.watermarkKey) }
    }

    suspend fun hasCompletedInitialIndex(): Boolean =
        indexState.get(IndexStateKeys.INITIAL_INDEX_COMPLETE) == "1"

    companion object {
        /** Per the brief: batch DB writes in transactions of about 500 rows. */
        const val DEFAULT_CHUNK_SIZE = 500
        private const val PRESENCE_PAGE = 2_000

        private val MediaCollection.watermarkKey: String
            get() = when (this) {
                MediaCollection.Images -> IndexStateKeys.IMAGE_WATERMARK
                MediaCollection.Videos -> IndexStateKeys.VIDEO_WATERMARK
            }
    }
}

internal fun MediaStoreItem.toEntity(
    id: Long = 0,
    contentHash: String? = null,
    dateFirstIndexed: Long,
) = MediaEntity(
    id = id,
    contentHash = contentHash,
    mediaStoreId = mediaStoreId,
    uri = uri,
    displayName = displayName,
    relativePath = relativePath,
    bucketId = bucketId,
    bucketName = bucketName,
    mime = mime,
    isVideo = isVideo,
    size = size,
    dateTaken = dateTaken,
    dateModified = dateModified,
    dateAdded = dateAdded,
    dateFirstIndexed = dateFirstIndexed,
    duration = duration,
    width = width,
    height = height,
    orientation = orientation,
    isMissing = false,
)

/** Rethrows cancellation, swallows everything else. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

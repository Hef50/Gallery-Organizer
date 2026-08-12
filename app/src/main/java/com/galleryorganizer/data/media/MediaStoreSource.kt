package com.galleryorganizer.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/** The two collections the app indexes. Kept separate, each with its own watermark. */
enum class MediaCollection(val isVideo: Boolean) {
    Images(isVideo = false),
    Videos(isVideo = true),
    ;

    val contentUri: Uri
        get() = when (this) {
            Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
}

/** One row as MediaStore reports it, before any of the app's own bookkeeping. */
data class MediaStoreItem(
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val bucketId: Long,
    val bucketName: String,
    val mime: String,
    val isVideo: Boolean,
    val size: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val duration: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
)

/**
 * Where a pass got to. Ordering is `(date_modified, _id)` ascending, and the cursor is
 * that exact pair, so a resumed pass restarts on the *next* row rather than re-reading a
 * whole second of timestamps or — worse — skipping the rows that share the last second.
 */
data class ScanCursor(val dateModified: Long, val mediaStoreId: Long) {

    fun encode(): String = "$dateModified:$mediaStoreId"

    companion object {
        val Start = ScanCursor(0L, 0L)

        fun decode(raw: String?): ScanCursor {
            val parts = raw?.split(':') ?: return Start
            if (parts.size != 2) return Start
            val dateModified = parts[0].toLongOrNull() ?: return Start
            val id = parts[1].toLongOrNull() ?: return Start
            return ScanCursor(dateModified, id)
        }
    }
}

/**
 * Reads the media library. An interface so the indexer can be tested against a fake
 * without a device — see `FakeMediaStoreSource` in the unit tests.
 */
interface MediaStoreSource {

    /**
     * Streams every item strictly after [after], oldest-modification first, handing them
     * to [onChunk] in batches of [chunkSize].
     *
     * The cursor stays open across the callback on purpose: the alternative is a fresh
     * `LIMIT`/`OFFSET` query per batch, which re-sorts the whole collection every time and
     * turns a 150k-item pass into an O(n²) crawl.
     */
    suspend fun scan(
        collection: MediaCollection,
        after: ScanCursor,
        chunkSize: Int,
        onChunk: suspend (List<MediaStoreItem>) -> Unit,
    )

    /** Every id currently in [collection], sorted ascending. Used by the presence sweep. */
    suspend fun allIds(collection: MediaCollection): LongArray
}

/** The real thing. */
class ContentResolverMediaStoreSource(
    private val resolver: ContentResolver,
) : MediaStoreSource {

    override suspend fun scan(
        collection: MediaCollection,
        after: ScanCursor,
        chunkSize: Int,
        onChunk: suspend (List<MediaStoreItem>) -> Unit,
    ) {
        val selection = "(${MediaStore.MediaColumns.DATE_MODIFIED} > ?) OR " +
            "(${MediaStore.MediaColumns.DATE_MODIFIED} = ? AND ${MediaStore.MediaColumns._ID} > ?)"
        val args = arrayOf(
            after.dateModified.toString(),
            after.dateModified.toString(),
            after.mediaStoreId.toString(),
        )
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC, ${MediaStore.MediaColumns._ID} ASC"

        resolver.query(collection.contentUri, PROJECTION, selection, args, sort)?.use { cursor ->
            val reader = CursorReader(cursor, collection)
            val batch = ArrayList<MediaStoreItem>(chunkSize)
            while (cursor.moveToNext()) {
                batch += reader.read()
                if (batch.size >= chunkSize) {
                    onChunk(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onChunk(batch.toList())
        }
    }

    override suspend fun allIds(collection: MediaCollection): LongArray {
        val ids = ArrayList<Long>(1024)
        resolver.query(
            collection.contentUri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            "${MediaStore.MediaColumns._ID} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) ids += cursor.getLong(idColumn)
        }
        return ids.toLongArray()
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
        )
    }
}

/**
 * Column indices are resolved once per query rather than per row. At 150k rows the
 * difference between `getColumnIndexOrThrow` per column per row and this is seconds.
 */
internal class CursorReader(private val cursor: Cursor, private val collection: MediaCollection) {
    private val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    private val name = cursor.index(MediaStore.MediaColumns.DISPLAY_NAME)
    private val path = cursor.index(MediaStore.MediaColumns.RELATIVE_PATH)
    private val bucketId = cursor.index(MediaStore.MediaColumns.BUCKET_ID)
    private val bucketName = cursor.index(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
    private val mime = cursor.index(MediaStore.MediaColumns.MIME_TYPE)
    private val size = cursor.index(MediaStore.MediaColumns.SIZE)
    private val dateTaken = cursor.index(MediaStore.MediaColumns.DATE_TAKEN)
    private val dateModified = cursor.index(MediaStore.MediaColumns.DATE_MODIFIED)
    private val dateAdded = cursor.index(MediaStore.MediaColumns.DATE_ADDED)
    private val duration = cursor.index(MediaStore.MediaColumns.DURATION)
    private val width = cursor.index(MediaStore.MediaColumns.WIDTH)
    private val height = cursor.index(MediaStore.MediaColumns.HEIGHT)
    private val orientation = cursor.index(MediaStore.MediaColumns.ORIENTATION)

    fun read(): MediaStoreItem {
        val itemId = cursor.getLong(id)
        val modified = cursor.getLongOr(dateModified, 0L)
        return MediaStoreItem(
            mediaStoreId = itemId,
            uri = ContentUris.withAppendedId(collection.contentUri, itemId).toString(),
            displayName = cursor.getStringOr(name, "").ifEmpty { "item-$itemId" },
            relativePath = cursor.getStringOr(path, ""),
            bucketId = cursor.getLongOr(bucketId, 0L),
            bucketName = cursor.getStringOr(bucketName, ""),
            mime = cursor.getStringOr(mime, if (collection.isVideo) "video/*" else "image/*"),
            isVideo = collection.isVideo,
            size = cursor.getLongOr(size, 0L),
            // A lot of non-camera files have no DATE_TAKEN at all. Falling back to
            // DATE_MODIFIED (seconds -> millis) keeps them from piling up at the epoch and
            // swamping the bottom of a date-sorted grid.
            dateTaken = cursor.getLongOr(dateTaken, 0L).takeIf { it > 0 } ?: (modified * 1000L),
            dateModified = modified,
            dateAdded = cursor.getLongOr(dateAdded, 0L),
            duration = cursor.getLongOr(duration, 0L),
            width = cursor.getLongOr(width, 0L).toInt(),
            height = cursor.getLongOr(height, 0L).toInt(),
            orientation = cursor.getLongOr(orientation, 0L).toInt(),
        )
    }
}

private fun Cursor.index(column: String): Int = getColumnIndex(column)

private fun Cursor.getLongOr(index: Int, fallback: Long): Long =
    if (index < 0 || isNull(index)) fallback else getLong(index)

private fun Cursor.getStringOr(index: Int, fallback: String): String =
    if (index < 0 || isNull(index)) fallback else getString(index) ?: fallback
